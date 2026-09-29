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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
 * grava, byte a byte. Aqui ele é **derivado** da linha do artefato na hora de
 * exibir ou exportar ([doArtefato]); não é gravado nem lido de volta.
 */
public object MarkdownDoArtefato {

    /** `sanitizeText(buildArtifactMarkdown(...), 500_000)`: o teto do web para o markdown inteiro. */
    public const val MAX_PONTOS_DE_CODIGO: Int = 500_000

    /** `sanitizeText(review_report, 120_000)`: o teto do web para o relatório; aqui recusado, não cortado. */
    public const val MAX_PONTOS_DO_RELATORIO: Int = 120_000

    /**
     * O teto da linha do artefato em bytes UTF-8 (texto aceito + relatório +
     * auditoria): metade do `CursorWindow` de 2 MiB do Android, que é o que uma
     * linha pode ter para um `SELECT *` conseguir carregá-la. Um provedor devolve
     * no máximo 64 mil tokens (~256 KB); acima do teto é um erro, não um caso.
     */
    public const val MAX_BYTES_DA_LINHA: Int = 1_048_576

    /**
     * O teto do texto aceito em bytes UTF-8, metade de [MAX_BYTES_DA_LINHA]:
     * o mesmo texto vai para `artefatos.textoAceito`, `sessoes.textoAtual` e,
     * no fim, `sessoes.textoFinal`, e a linha da sessão — com o protocolo de
     * até 640 KB e o pedido de até 160 KB — tem de continuar abaixo dos 2 MiB
     * do `CursorWindow` (decisão do operador de 27/09/2026).
     */
    public const val MAX_BYTES_DO_TEXTO: Int = MAX_BYTES_DA_LINHA / 2

    /**
     * O texto aceito que cabe, ou [IntegridadeDeLinks.Falha]: vazio (nada
     * aceito é texto nenhum — a retomada o recusaria, então o checkpoint o
     * recusa antes de o pagar de novo) ou acima de [MAX_BYTES_DO_TEXTO].
     */
    public fun conferirTexto(texto: String) {
        if (texto.isEmpty()) throw IntegridadeDeLinks.Falha("Accepted text is empty.")
        if (texto.toByteArray(Charsets.UTF_8).size > MAX_BYTES_DO_TEXTO) throw IntegridadeDeLinks.Falha("Accepted text exceeds $MAX_BYTES_DO_TEXTO bytes.")
    }

    /** A linha que caberia no banco, ou [IntegridadeDeLinks.Falha] acima de [MAX_BYTES_DA_LINHA]. */
    public fun conferirLinha(textoAceito: String, relatorio: String, auditoriaJson: String) {
        val bytes = textoAceito.toByteArray(Charsets.UTF_8).size.toLong() + relatorio.toByteArray(Charsets.UTF_8).size + auditoriaJson.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_BYTES_DA_LINHA) throw IntegridadeDeLinks.Falha("Artifact row exceeds $MAX_BYTES_DA_LINHA bytes.")
    }

    /** O markdown do web a partir da linha gravada (o `content_md` que o web guardaria). */
    public fun doArtefato(linha: ArtefatoEntidade): String = TrimJs.aparar(
        montar(
            EntradaDeArtefato(
                sessaoId = linha.sessaoId, ciclo = linha.ciclo, turno = linha.turno,
                agente = Agentes.sanear(linha.agente, Provedor.CLAUDE), papel = linha.papel, status = linha.status,
                titulo = linha.titulo, texto = linha.textoAceito, relatorioDeRevisao = linha.relatorioDeRevisaoJson,
                auditoriaDeLinks = RepositorioDeArtefatos.lerAuditoria(linha.auditoriaDeLinksJson),
                custoUsd = Dinheiro.deE8(linha.custoE8), artefatoAnteriorId = linha.artefatoAnteriorId, modelo = linha.modelo,
            ),
        ),
    )

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
        val relatorioBruto = entrada.relatorioDeRevisao?.takeIf { it.isNotEmpty() } ?: "{}"
        // Nada se corta: um relatório acima do teto do web é recusado, não serrado no
        // meio do JSON; a linha tem de caber no banco; e o markdown — renderizado da
        // própria linha, exatamente como o leitor o receberá — tem de caber no teto do
        // web; `bytesDoConteudo` (o `content_bytes` do web) mede essa mesma renderização.
        if (EspacoUnicode.contarPontosDeCodigo(relatorioBruto) > MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO) {
            throw IntegridadeDeLinks.Falha("Artifact revision report exceeds ${MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO} code points.")
        }
        val relatorio = Texto.sanear(relatorioBruto, MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO)
        val auditoriaJson = FormatoDeLinks.serializarLinhas(entrada.auditoriaDeLinks)
        MarkdownDoArtefato.conferirLinha(textoAceito, relatorio, auditoriaJson)
        MarkdownDoArtefato.conferirTexto(textoAceito)
        val semMedida = ArtefatoEntidade(
            id = "artifact-${UUID.randomUUID()}",
            sessaoId = entrada.sessaoId,
            ciclo = entrada.ciclo,
            turno = entrada.turno,
            agente = entrada.agente.agente,
            papel = entrada.papel,
            status = entrada.status,
            titulo = titulo,
            textoAceito = textoAceito,
            relatorioDeRevisaoJson = relatorio,
            auditoriaDeLinksJson = auditoriaJson,
            custoE8 = Dinheiro.paraE8Observado(entrada.custoUsd ?: BigDecimal.ZERO),
            modelo = entrada.modelo?.takeIf { it.isNotEmpty() },
            artefatoAnteriorId = entrada.artefatoAnteriorId?.takeIf { it.isNotEmpty() },
            criadoEm = FormatoDeInstante.iso(relogio()),
        )
        val markdown = MarkdownDoArtefato.conferir(MarkdownDoArtefato.doArtefato(semMedida))
        val linha = semMedida.copy(bytesDoConteudo = markdown.toByteArray(Charsets.UTF_8).size.toLong())
        banco.artefatos().inserir(linha)
        return linha
    }

    public fun daSessao(sessaoId: String): List<ArtefatoEntidade> = banco.artefatos().daSessao(sessaoId)

    /** A lista dos autos, observada: reemite quando um artefato entra, não a cada custo ou evento da sessão. */
    public fun observarResumos(sessaoId: String): Flow<List<ResumoDoArtefato>> =
        banco.artefatos().observarResumos(sessaoId).map { linhas -> linhas.map(ResumoDoArtefato::de) }

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
