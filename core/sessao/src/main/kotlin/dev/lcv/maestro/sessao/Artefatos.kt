package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.EspacoUnicode
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/** `ArtifactInput` (`sessions.ts:220-234`), com as linhas do motor de links do porte (emenda A6). */
public data class EntradaDeArtefato(
    val sessaoId: String,
    val ciclo: Int,
    val turno: Int,
    val agente: Provedor,
    /** `draft` ou `revision`. */
    val papel: String,
    val status: String,
    val titulo: String,
    /** O texto do turno; o que fica aceito é a forma canônica dele ([EstadoCircular.textoCanonico]). */
    val texto: String,
    val relatorioDeRevisao: String?,
    val auditoriaDeLinks: List<LinhaDeLink>,
    val custoUsd: BigDecimal?,
    val artefatoAnteriorId: String?,
    val modelo: String? = null,
)

/**
 * `buildArtifactMarkdown` (`sessions.ts:839-870`): o markdown que o web
 * grava, byte a byte. Aqui ele é **derivado** — serve para exibir e exportar
 * e nunca é lido de volta; o texto aceito vive na coluna própria do artefato.
 */
public object MarkdownDoArtefato {

    /** `sanitizeText(buildArtifactMarkdown(...), 500_000)`: o teto do web para o markdown inteiro. */
    public const val MAX_PONTOS_DE_CODIGO: Int = 500_000

    /**
     * O markdown como o web o grava (`sanitizeText`: aparado nas pontas), ou
     * [IntegridadeDeLinks.Falha] acima do teto — o web cortaria em silêncio e
     * a exportação mostraria um texto pela metade (achado do Codex na #67).
     */
    public fun conferir(markdown: String): String {
        if (EspacoUnicode.contarPontosDeCodigo(markdown) > MAX_PONTOS_DE_CODIGO) {
            throw IntegridadeDeLinks.Falha("Artifact markdown exceeds $MAX_PONTOS_DE_CODIGO code points.")
        }
        return TrimJs.aparar(markdown)
    }

    /** `falhas` do motor (`IntegridadeDeLinks.kt`): `tom` de erro ou bloqueio; o web contava `!ok`. */
    public fun contarInvalidos(auditoria: List<LinhaDeLink>): Int = auditoria.count { it.tom == "error" || it.tom == "blocked" }

    public fun montar(entrada: EntradaDeArtefato): String {
        val custo = (entrada.custoUsd ?: BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP).toPlainString()
        return listOf(
            "# Maestro AI Artifact - ${entrada.titulo}",
            "",
            "- Session: ${entrada.sessaoId}",
            "- Cycle: ${entrada.ciclo}",
            "- Turn: ${entrada.turno}",
            "- Agent: ${entrada.agente.rotulo}",
            "- Role: ${entrada.papel}",
            "- Status: ${entrada.status}",
            "- Model: ${entrada.modelo?.takeIf { it.isNotEmpty() } ?: "unknown"}",
            "- Cost USD: $custo",
            "- Previous artifact: ${entrada.artefatoAnteriorId?.takeIf { it.isNotEmpty() } ?: "none"}",
            "- Invalid links: ${contarInvalidos(entrada.auditoriaDeLinks)}",
            "",
            "## Revision Report",
            "",
            entrada.relatorioDeRevisao?.takeIf { it.isNotEmpty() } ?: "{}",
            "",
            "## Link Audit",
            "",
            "```json",
            FormatoDeLinks.serializarLinhas(entrada.auditoriaDeLinks),
            "```",
            "",
            "## Current Text",
            "",
            entrada.texto,
            "",
        ).joinToString("\n")
    }
}

/**
 * `createArtifact` (`sessions.ts:872-921`) e as leituras (`:3079-3098`). A
 * inserção é interna: o único caminho que grava um artefato de sessão é o
 * checkpoint ([PontoDeRetomada]), na mesma transação da custódia.
 */
public class RepositorioDeArtefatos(
    private val banco: BancoDaSessao,
    private val relogio: () -> Instant,
) {
    /** A linha gravada; [EntradaDeArtefato.texto] é canonizado uma vez e é o que a custódia compara. */
    internal fun inserir(entrada: EntradaDeArtefato): ArtefatoEntidade {
        val textoAceito = EstadoCircular.textoCanonico(entrada.texto)
        val titulo = Texto.sanear(entrada.titulo, 240)
        val relatorio = Texto.sanear(entrada.relatorioDeRevisao?.takeIf { it.isNotEmpty() } ?: "{}", 120_000)
        // O markdown é montado das colunas já saneadas e nunca é cortado: ou cabe, ou o artefato é recusado.
        val conteudoMd = MarkdownDoArtefato.conferir(MarkdownDoArtefato.montar(entrada.copy(titulo = titulo, relatorioDeRevisao = relatorio, texto = textoAceito)))
        val linha = ArtefatoEntidade(
            id = "artifact-${UUID.randomUUID()}",
            sessaoId = entrada.sessaoId,
            ciclo = entrada.ciclo,
            turno = entrada.turno,
            agente = entrada.agente.agente,
            papel = entrada.papel,
            status = entrada.status,
            titulo = titulo,
            textoAceito = textoAceito,
            conteudoMd = conteudoMd,
            relatorioDeRevisaoJson = relatorio,
            auditoriaDeLinksJson = FormatoDeLinks.serializarLinhas(entrada.auditoriaDeLinks),
            custoE8 = Dinheiro.paraE8(entrada.custoUsd ?: BigDecimal.ZERO),
            modelo = entrada.modelo?.takeIf { it.isNotEmpty() },
            artefatoAnteriorId = entrada.artefatoAnteriorId?.takeIf { it.isNotEmpty() },
            bytesDoConteudo = conteudoMd.toByteArray(Charsets.UTF_8).size.toLong(),
            criadoEm = FormatoDeInstante.iso(relogio()),
        )
        banco.artefatos().inserir(linha)
        return linha
    }

    public fun daSessao(sessaoId: String): List<ArtefatoEntidade> = banco.artefatos().daSessao(sessaoId)

    public fun um(sessaoId: String, id: String): ArtefatoEntidade? = banco.artefatos().um(sessaoId, id)

    public fun turnoMaximo(sessaoId: String): Int = banco.artefatos().turnoMaximo(sessaoId)

    public companion object {
        /** As linhas gravadas em `auditoriaDeLinksJson`; o que não parseia é lista vazia (`parseJson(…, [])`). */
        public fun lerAuditoria(texto: String?): List<LinhaDeLink> {
            val raiz = Json.tolerante(texto) ?: return emptyList()
            if (!raiz.isArray) return emptyList()
            return raiz.mapNotNull { FormatoDeLinks.lerLinha(Json.ESTRITO.writeValueAsString(it)) }
        }
    }
}
