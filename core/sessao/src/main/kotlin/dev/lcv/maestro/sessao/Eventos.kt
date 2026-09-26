package dev.lcv.maestro.sessao

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal

/**
 * `SessionEvent` (`sessions.ts:196-209`): uma linha do jornal da sessão,
 * gravada como linha da tabela `eventos` ([EventoEntidade]). O campo
 * `link_audit` leva as linhas do motor de links do porte, não o
 * `LinkAuditResult` legado do web; `final_audit` é guardado como chegou,
 * porque é contexto estruturado que a tela só exibe.
 */
public data class EventoDaSessao(
    val em: String,
    val status: String,
    val mensagem: String,
    val agente: Provedor? = null,
    /** `draft` ou `revision`. */
    val papel: String? = null,
    val custoUsd: BigDecimal? = null,
    val fonteDoCusto: Custo.Fonte? = null,
    val modelo: String? = null,
    val auditoriaDeLinks: List<LinhaDeLink>? = null,
    val auditoriaFinal: ObjectNode? = null,
) {
    internal fun paraEntidade(sessaoId: String): EventoEntidade = EventoEntidade(
        sessaoId = sessaoId,
        em = em,
        agente = agente?.agente,
        papel = papel,
        status = status,
        mensagem = mensagem,
        custoE8 = custoUsd?.let(Dinheiro::paraE8),
        fonteDoCusto = fonteDoCusto?.let { if (it == Custo.Fonte.PROVEDOR) "provider" else "estimate" },
        modelo = modelo,
        auditoriaDeLinksJson = auditoriaDeLinks?.let(FormatoDeLinks::serializarLinhas),
        auditoriaFinalJson = auditoriaFinal?.let { Json.ESTRITO.writeValueAsString(it) },
    )

    public companion object {
        public const val NA_FILA: String = "queued"
        public const val RODANDO: String = "running"
        public const val PRONTO: String = "ready"
        public const val NAO_PRONTO: String = "not_ready"
        public const val BLOQUEADO: String = "blocked"
        public const val ERRO: String = "error"
        public const val TERMINADO: String = "finished"
        public val STATUS: Set<String> = setOf(NA_FILA, RODANDO, PRONTO, NAO_PRONTO, BLOQUEADO, ERRO, TERMINADO)
    }
}

/** A linha lida de volta; os campos JSON são lidos com tolerância, como as projeções do web (`parseJson`). */
public fun EventoEntidade.paraEvento(): EventoDaSessao = EventoDaSessao(
    em = em,
    status = status,
    mensagem = mensagem,
    agente = Agentes.porChave(agente),
    papel = papel,
    custoUsd = custoE8?.let(Dinheiro::deE8),
    fonteDoCusto = when (fonteDoCusto) {
        "provider" -> Custo.Fonte.PROVEDOR
        "estimate" -> Custo.Fonte.ESTIMATIVA
        else -> null
    },
    modelo = modelo,
    auditoriaDeLinks = auditoriaDeLinksJson?.let(RepositorioDeArtefatos::lerAuditoria),
    auditoriaFinal = auditoriaFinalJson?.let { Json.tolerante(it) }?.takeIf(JsonNode::isObject) as ObjectNode?,
)
