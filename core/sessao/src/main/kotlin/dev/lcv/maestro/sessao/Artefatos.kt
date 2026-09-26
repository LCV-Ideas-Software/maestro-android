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
    val conteudoMd: String,
    val relatorioDeRevisao: String?,
    val auditoriaDeLinks: List<LinhaDeLink>,
    val custoUsd: BigDecimal?,
    val artefatoAnteriorId: String?,
    val modelo: String? = null,
)

/**
 * `buildArtifactMarkdown` e `artifactMatchesCurrentText`
 * (`sessions.ts:839-870, 3035-3050`): o artefato gravado é markdown com
 * cabeçalho, relatório, auditoria de links e o texto; a retomada lê o texto
 * de volta do mesmo formato. As duas funções são testadas em par.
 */
public object MarkdownDoArtefato {
    private const val DELIMITADOR_GERADO = "\n```\n\n## Current Text\n\n"
    private const val INICIO_DA_AUDITORIA = "\n## Link Audit\n\n```json\n"
    private const val DELIMITADOR_LEGADO = "\n## Current Text\n\n"

    /** `sanitizeText(buildArtifactMarkdown(...), 500_000)`: o teto do web para o markdown inteiro. */
    public const val MAX_PONTOS_DE_CODIGO: Int = 500_000

    /**
     * O markdown que o web gravaria depois do `sanitizeText`, ou
     * [IntegridadeDeLinks.Falha] quando o saneamento **mudaria o conteúdo**:
     * um NUL no texto ou o teto de 500 000 pontos de código. O web apaga o
     * NUL e corta só no artefato, e o texto aceito gravado ao lado — que é
     * o que a retomada compara e hasheia — fica diferente: a sessão morreria
     * em `paused_resume_state_invalid` na primeira retomada. Aqui o
     * checkpoint falha fechado, e quem aceita o texto do provedor o saneia
     * antes (decisão de 25/09/2026, achado do Codex na #67). Só o `trim`
     * das pontas, que a leitura de volta também faz, é aplicado.
     */
    public fun gravavel(markdown: String): String {
        if (markdown.contains('\u0000')) throw IntegridadeDeLinks.Falha("Artifact markdown contains a NUL character.")
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
            entrada.conteudoMd,
            "",
        ).joinToString("\n")
    }

    /**
     * O texto que o artefato carrega, aparado como o web apara (`trim` do
     * JavaScript), ou `null` quando o markdown não tem a seção. Procura o
     * delimitador gerado depois do bloco da auditoria; sem ele, o legado.
     */
    public fun textoAtual(markdown: String): String? {
        val texto = markdown.replace("\r\n", "\n")
        val inicioDaAuditoria = texto.indexOf(INICIO_DA_AUDITORIA)
        val marcadorGerado = if (inicioDaAuditoria >= 0) {
            texto.indexOf(DELIMITADOR_GERADO, inicioDaAuditoria + INICIO_DA_AUDITORIA.length)
        } else {
            -1
        }
        val corpo = if (marcadorGerado >= 0) {
            texto.substring(marcadorGerado + DELIMITADOR_GERADO.length)
        } else {
            val legado = texto.indexOf(DELIMITADOR_LEGADO)
            if (legado < 0) return null
            texto.substring(legado + DELIMITADOR_LEGADO.length)
        }
        return TrimJs.aparar(corpo)
    }

    /** `artifactMatchesCurrentText`: o texto do artefato é o [esperado] aparado (vazio nunca casa). */
    public fun casaCom(artefato: ArtefatoEntidade, esperado: String): Boolean {
        val aparado = TrimJs.aparar(esperado)
        if (aparado.isEmpty()) return false
        val atual = textoAtual(artefato.conteudoMd) ?: return false
        return atual == aparado.replace("\r\n", "\n")
    }
}

/** `createArtifact` (`sessions.ts:872-921`) e as leituras (`:3079-3098`). */
public class RepositorioDeArtefatos(
    private val banco: BancoDaSessao,
    private val relogio: () -> Instant,
) {
    /** Insere a linha; quem já está numa transação (a retomada, o checkpoint) chama daqui de dentro. */
    public fun criar(entrada: EntradaDeArtefato): ArtefatoEntidade {
        val conteudoMd = MarkdownDoArtefato.gravavel(MarkdownDoArtefato.montar(entrada))
        val linha = ArtefatoEntidade(
            id = "artifact-${UUID.randomUUID()}",
            sessaoId = entrada.sessaoId,
            ciclo = entrada.ciclo,
            turno = entrada.turno,
            agente = entrada.agente.agente,
            papel = entrada.papel,
            status = entrada.status,
            titulo = Texto.sanear(entrada.titulo, 240),
            conteudoMd = conteudoMd,
            relatorioDeRevisaoJson = Texto.sanear(entrada.relatorioDeRevisao?.takeIf { it.isNotEmpty() } ?: "{}", 120_000),
            auditoriaDeLinksJson = FormatoDeLinks.serializarLinhas(entrada.auditoriaDeLinks),
            custoUsd = entrada.custoUsd ?: BigDecimal.ZERO,
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

    public companion object {
        /** As linhas gravadas em `auditoriaDeLinksJson`; o que não parseia é lista vazia (`parseJson(…, [])`). */
        public fun lerAuditoria(texto: String?): List<LinhaDeLink> {
            val raiz = Json.tolerante(texto) ?: return emptyList()
            if (!raiz.isArray) return emptyList()
            return raiz.mapNotNull { FormatoDeLinks.lerLinha(Json.ESTRITO.writeValueAsString(it)) }
        }
    }
}
