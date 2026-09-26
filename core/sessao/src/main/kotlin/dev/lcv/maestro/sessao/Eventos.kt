package dev.lcv.maestro.sessao

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal

/**
 * `SessionEvent` (`sessions.ts:196-209`): uma linha do jornal da sessão. O
 * campo `link_audit` leva as linhas do motor de links do porte, não o
 * `LinkAuditResult` legado do web (emenda A6); `final_audit` é guardado como
 * chegou, porque é contexto estruturado que a tela só exibe.
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

/**
 * O jornal (`events_json`): "porta como está — lista serializada com leitura
 * estrita" (especificação, seção 4.2). A leitura estrita é
 * `parseSessionEventsStrict` (`sessions.ts:685-699`), com as mensagens do
 * web; a tolerante é o `parseJson(events_json, [])` das projeções.
 *
 * O acréscimo de um evento acontece no nível do nó JSON, nunca pelo modelo
 * tipado: um campo que este código não conhece sobrevive ao checkpoint
 * seguinte (emenda A10).
 */
public object Jornal {

    /** `parseSessionEventsStrict`, ou [IntegridadeDeLinks.Falha] com a mensagem do web. */
    public fun lerEstrito(texto: String): List<EventoDaSessao> {
        val raiz = try {
            Json.ESTRITO.readTree(texto)
        } catch (erro: JacksonException) {
            throw IntegridadeDeLinks.Falha("Session event journal contains invalid JSON: ${erro.message?.lineSequence()?.first()}")
        }
        if (raiz == null || !raiz.isArray) throw IntegridadeDeLinks.Falha("Session event journal must be a JSON array.")
        return raiz.map { no ->
            lerEvento(no) ?: throw IntegridadeDeLinks.Falha("Session event journal contains an event without at, status and message.")
        }
    }

    /** `parseJson(events_json, [])`: o que não parseia é lista vazia; evento malformado é pulado. */
    public fun lerTolerante(texto: String?): List<EventoDaSessao> {
        val raiz = Json.tolerante(texto) ?: return emptyList()
        if (!raiz.isArray) return emptyList()
        return raiz.mapNotNull(::lerEvento)
    }

    /** O jornal com [evento] no fim, preservando cada evento anterior como está gravado. */
    public fun anexar(texto: String, evento: EventoDaSessao): String {
        val raiz = try {
            Json.ESTRITO.readTree(texto)
        } catch (erro: JacksonException) {
            throw IntegridadeDeLinks.Falha("Session event journal contains invalid JSON: ${erro.message?.lineSequence()?.first()}")
        }
        if (raiz == null || !raiz.isArray) throw IntegridadeDeLinks.Falha("Session event journal must be a JSON array.")
        (raiz as ArrayNode).add(paraNo(evento))
        return Json.ESTRITO.writeValueAsString(raiz)
    }

    public fun serializar(eventos: List<EventoDaSessao>): String {
        val lista = Json.ESTRITO.createArrayNode()
        eventos.forEach { lista.add(paraNo(it)) }
        return Json.ESTRITO.writeValueAsString(lista)
    }

    internal fun paraNo(evento: EventoDaSessao): ObjectNode {
        val no = Json.ESTRITO.createObjectNode()
        no.put("at", evento.em)
        evento.agente?.let { no.put("agent", it.agente) }
        evento.papel?.let { no.put("role", it) }
        no.put("status", evento.status)
        no.put("message", evento.mensagem)
        evento.custoUsd?.let { no.put("cost_usd", it) }
        evento.fonteDoCusto?.let { no.put("cost_source", if (it == Custo.Fonte.PROVEDOR) "provider" else "estimate") }
        evento.modelo?.let { no.put("model", it) }
        evento.auditoriaDeLinks?.let { linhas ->
            no.set<JsonNode>("link_audit", Json.ESTRITO.readTree(FormatoDeLinks.serializarLinhas(linhas)))
        }
        evento.auditoriaFinal?.let { no.set<JsonNode>("final_audit", it.deepCopy()) }
        return no
    }

    private fun lerEvento(no: JsonNode): EventoDaSessao? {
        if (!no.isObject) return null
        val em = no.get("at")?.takeIf { it.isTextual }?.textValue() ?: return null
        val status = no.get("status")?.takeIf { it.isTextual }?.textValue() ?: return null
        val mensagem = no.get("message")?.takeIf { it.isTextual }?.textValue() ?: return null
        if (status !in EventoDaSessao.STATUS) return null
        val auditoria = no.get("link_audit")?.takeIf { it.isArray }?.mapNotNull { linha ->
            FormatoDeLinks.lerLinha(Json.ESTRITO.writeValueAsString(linha))
        }
        return EventoDaSessao(
            em = em,
            status = status,
            mensagem = mensagem,
            agente = no.get("agent")?.takeIf { it.isTextual }?.let { Agentes.porChave(it.textValue()) },
            papel = no.get("role")?.takeIf { it.isTextual }?.textValue(),
            custoUsd = no.get("cost_usd")?.takeIf { it.isNumber }?.decimalValue(),
            fonteDoCusto = when (no.get("cost_source")?.takeIf { it.isTextual }?.textValue()) {
                "provider" -> Custo.Fonte.PROVEDOR
                "estimate" -> Custo.Fonte.ESTIMATIVA
                else -> null
            },
            modelo = no.get("model")?.takeIf { it.isTextual }?.textValue(),
            auditoriaDeLinks = auditoria,
            auditoriaFinal = no.get("final_audit")?.takeIf { it.isObject }?.deepCopy(),
        )
    }
}
