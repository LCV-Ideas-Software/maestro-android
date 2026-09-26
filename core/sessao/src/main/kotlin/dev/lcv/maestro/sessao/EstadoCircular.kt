package dev.lcv.maestro.sessao

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.PromptsDaSessao
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.time.Instant

/** `CircularReviewState` (`sessions.ts:145-159`), já validado. */
public data class EstadoDaRevisaoCircular(
    val runId: String,
    val artefatoDeCustodiaId: String,
    val autorDaCustodia: Provedor,
    val sha256DaCustodia: String,
    val rodada: Int,
    val indiceDoTurno: Int,
    val escala: List<Provedor>,
    val agentesValidosDaRodada: List<Provedor>,
    val aprovacoesEstaveis: List<Provedor>,
    val turnoDoArtefato: Int,
    val artefatoAnteriorId: String,
    val atualizadoEm: String?,
)

/** O JSON gravado em `estadoCircularJson`, lido e ainda não validado (o web só faz o *cast*). */
public class EstadoPersistido internal constructor(internal val no: ObjectNode)

/** `CircularResumeProgress`: o que a retomada restaura antes do primeiro turno. */
public data class ProgressoDaRetomada(
    val rodada: Int,
    val indiceDoTurno: Int,
    val agentesValidos: Set<Provedor>,
    val aprovacoesEstaveis: Set<Provedor>,
    val artefatoDeCustodiaId: String,
    val turnoDoArtefato: Int,
    val artefatoAnteriorId: String,
    val relatorios: List<PromptsDaSessao.RelatorioDeTurno>,
)

/** O que `serializeCircularReviewState` recebe (`sessions.ts:3281-3310`): a custódia viva do runner. */
public data class ProgressoCircular(
    val autorAtual: Provedor,
    val textoAtual: String,
    val artefatoDeCustodiaId: String?,
    val artefatoAnteriorId: String?,
    val rodada: Int,
    val indiceDoTurno: Int,
    val escala: List<Provedor>,
    val agentesValidos: Set<Provedor>,
    val aprovacoesEstaveis: Set<Provedor>,
    val turnoDoArtefato: Int,
)

/**
 * As funções puras da custódia circular (`sessions.ts:3022-3310`), com as
 * mensagens do web. O que toca o banco fica em [Retomada]; aqui entram
 * as linhas já carregadas ou um carregador injetado.
 *
 * Aparar é o `trim` do JavaScript ([TrimJs]) nas duas pontas, serializar e
 * validar, e o hash é SHA-256 sobre UTF-8, como o `TextEncoder` (emenda A9).
 */
public object EstadoCircular {
    public const val VERSAO_DO_ESQUEMA: Int = 2

    /** `parsePersistedCircularState`: vazio ou `{}` é "sem estado"; o resto tem de ser um objeto JSON. */
    public fun ler(bruto: String?): EstadoPersistido? {
        val aparado = TrimJs.aparar(bruto ?: "")
        if (aparado.isEmpty() || aparado == "{}") return null
        val no = try {
            Json.ESTRITO.readTree(aparado)
        } catch (erro: JacksonException) {
            throw IntegridadeDeLinks.Falha("Circular custody state contains invalid JSON: ${erro.message?.lineSequence()?.first()}")
        }
        if (no == null || !no.isObject) throw IntegridadeDeLinks.Falha("Circular custody state must be an object.")
        return EstadoPersistido(no as ObjectNode)
    }

    /** `parseJson(circular_state_json, null)` das projeções: `null` quando não parseia ou não valida. */
    public fun lerTolerante(bruto: String?): EstadoPersistido? = try {
        ler(bruto)
    } catch (erro: IntegridadeDeLinks.Falha) {
        null
    }

    /**
     * `validatePersistedCircularState` (`sessions.ts:3118-3185`), na mesma
     * ordem de verificações; [carregarArtefato] é o `loadSessionArtifact`
     * da sessão. Lança [IntegridadeDeLinks.Falha] com a mensagem do web.
     */
    public fun validar(
        sessao: SessaoEntidade,
        estado: EstadoPersistido,
        carregarArtefato: (id: String) -> ArtefatoEntidade?,
    ): EstadoDaRevisaoCircular {
        val no = estado.no
        val versao = no.get("schema_version")
        if (inteiro(versao) != VERSAO_DO_ESQUEMA) {
            throw IntegridadeDeLinks.Falha("Unsupported circular custody schema version: ${comoJs(versao)}.")
        }
        if (texto(no.get("run_id")) != sessao.id) throw IntegridadeDeLinks.Falha("Circular custody run_id does not match the session.")
        val autor = Agentes.porChave(texto(no.get("current_draft_author_key")))
            ?: throw IntegridadeDeLinks.Falha("Circular custody state contains an unknown draft author.")
        if (autor.agente != sessao.autorAtual) throw IntegridadeDeLinks.Falha("Circular custody author does not match the session row.")
        val escala = agentes(no.get("round_roster"))
        val validos = agentes(no.get("valid_round_agents"))
        val estaveis = agentes(no.get("stable_serial_approval_agents"))
        if (escala == null || validos == null || estaveis == null) {
            throw IntegridadeDeLinks.Falha("Circular custody progress contains an unknown reviewer.")
        }
        if (escala.toSet().size != escala.size) throw IntegridadeDeLinks.Falha("Circular custody state contains duplicate roster members.")
        if ((validos + estaveis).any { it !in escala }) {
            throw IntegridadeDeLinks.Falha("Circular custody progress references a reviewer outside its roster.")
        }
        val rodada = inteiro(no.get("round"))
        val indiceDoTurno = inteiro(no.get("turn_index"))
        val turnoDoArtefato = inteiro(no.get("artifact_turn"))
        val custodiaId = texto(no.get("current_draft_artifact"))?.takeIf { it.isNotEmpty() }
        val anteriorId = texto(no.get("previous_artifact_id"))?.takeIf { it.isNotEmpty() }
        if (rodada == null || rodada < 1 || indiceDoTurno == null || indiceDoTurno < 0 ||
            turnoDoArtefato == null || turnoDoArtefato < 1 || custodiaId == null || anteriorId == null
        ) {
            throw IntegridadeDeLinks.Falha("Circular custody progress contains invalid counters or artifact references.")
        }
        val custodia = carregarArtefato(custodiaId)
        val anterior = carregarArtefato(anteriorId)
        if (custodia == null || !custodiaAceita(custodia)) {
            throw IntegridadeDeLinks.Falha("Circular custody references a missing or rejected artifact.")
        }
        if (anterior == null) throw IntegridadeDeLinks.Falha("Circular custody chain references a missing previous artifact.")
        if (custodia.agente != autor.agente || custodia.turno > turnoDoArtefato || anterior.turno > turnoDoArtefato) {
            throw IntegridadeDeLinks.Falha("Circular custody artifact author or turn does not match persisted state.")
        }
        val textoDaLinha = TrimJs.aparar(sessao.textoAtual)
        if (!MarkdownDoArtefato.casaCom(custodia, textoDaLinha)) {
            throw IntegridadeDeLinks.Falha("Circular custody artifact text does not match the session row.")
        }
        val sha256 = texto(no.get("current_draft_sha256"))
        if (FormatoDoRegistro.sha256(textoDaLinha) != sha256) {
            throw IntegridadeDeLinks.Falha("Circular custody draft hash does not match the accepted artifact.")
        }
        return EstadoDaRevisaoCircular(
            runId = sessao.id,
            artefatoDeCustodiaId = custodiaId,
            autorDaCustodia = autor,
            sha256DaCustodia = sha256!!,
            rodada = rodada,
            indiceDoTurno = indiceDoTurno,
            escala = escala,
            agentesValidosDaRodada = validos,
            aprovacoesEstaveis = estaveis,
            turnoDoArtefato = turnoDoArtefato,
            artefatoAnteriorId = anteriorId,
            atualizadoEm = texto(no.get("updated_at")),
        )
    }

    /** `restorePersistedCircularProgress` (`sessions.ts:3187-3220`). */
    public fun restaurar(
        estado: EstadoDaRevisaoCircular,
        escalaAtual: List<Provedor>,
        autorAtual: Provedor,
        relatorios: List<PromptsDaSessao.RelatorioDeTurno>,
    ): ProgressoDaRetomada {
        val mesmaEscala = estado.escala == escalaAtual
        var rodada = maxOf(1, estado.rodada)
        var indiceDoTurno = estado.indiceDoTurno
        var cruzouRodada = false
        while (escalaAtual.isNotEmpty() && indiceDoTurno >= escalaAtual.size) {
            rodada += 1
            indiceDoTurno -= escalaAtual.size
            cruzouRodada = true
        }
        if (!mesmaEscala) indiceDoTurno = 0
        val validos = if (cruzouRodada || !mesmaEscala) {
            emptySet()
        } else {
            estado.agentesValidosDaRodada.filter { it in escalaAtual }.toSet()
        }
        val estaveis = estado.aprovacoesEstaveis.filter { it in escalaAtual && it != autorAtual }.toSet()
        return ProgressoDaRetomada(
            rodada = rodada,
            indiceDoTurno = indiceDoTurno,
            agentesValidos = validos,
            aprovacoesEstaveis = estaveis,
            artefatoDeCustodiaId = estado.artefatoDeCustodiaId,
            turnoDoArtefato = estado.turnoDoArtefato,
            artefatoAnteriorId = estado.artefatoAnteriorId,
            relatorios = relatorios,
        )
    }

    /**
     * `reconstructLegacyCircularProgress` (`sessions.ts:3222-3279`): uma
     * sessão de antes do estado persistido. Sem artefato nenhum, cria o de
     * recuperação por [criarArtefato]; com artefatos que não casam, falha.
     */
    public fun reconstruirLegado(
        sessao: SessaoEntidade,
        autorAtual: Provedor,
        textoAtual: String,
        artefatos: List<ArtefatoEntidade>,
        criarArtefato: (EntradaDeArtefato) -> ArtefatoEntidade,
    ): ProgressoDaRetomada {
        var turnoDoArtefato = artefatos.maxOfOrNull { it.turno } ?: 0
        var custodia = artefatos.lastOrNull {
            custodiaAceita(it) && it.agente == autorAtual.agente && MarkdownDoArtefato.casaCom(it, textoAtual)
        }
        if (custodia == null) {
            if (artefatos.isNotEmpty()) {
                throw IntegridadeDeLinks.Falha("Legacy circular custody cannot be reconstructed from the existing artifacts.")
            }
            turnoDoArtefato += 1
            custodia = criarArtefato(
                EntradaDeArtefato(
                    sessaoId = sessao.id,
                    ciclo = 0,
                    turno = turnoDoArtefato,
                    agente = autorAtual,
                    papel = "draft",
                    status = "ready",
                    titulo = sessao.titulo,
                    conteudoMd = textoAtual,
                    relatorioDeRevisao = relatorioDeRecuperacao(autorAtual),
                    auditoriaDeLinks = emptyList(),
                    custoUsd = null,
                    artefatoAnteriorId = null,
                ),
            )
        }
        val anterior = artefatos.lastOrNull(::cadeiaAceita) ?: custodia
        return ProgressoDaRetomada(
            rodada = maxOf(1, anterior.ciclo.takeIf { it != 0 } ?: 1),
            indiceDoTurno = 0,
            agentesValidos = emptySet(),
            aprovacoesEstaveis = emptySet(),
            artefatoDeCustodiaId = custodia.id,
            turnoDoArtefato = maxOf(turnoDoArtefato, custodia.turno),
            artefatoAnteriorId = anterior.id,
            relatorios = artefatos.mapNotNull(::relatorioDeliberativo),
        )
    }

    /** `serializeCircularReviewState` (`sessions.ts:3281-3310`), com os mesmos limites mínimos. */
    public fun serializar(runId: String, progresso: ProgressoCircular, agora: Instant): String {
        val no = Json.ESTRITO.createObjectNode()
        no.put("schema_version", VERSAO_DO_ESQUEMA)
        no.put("run_id", runId)
        no.put("current_draft_artifact", progresso.artefatoDeCustodiaId)
        no.put("current_draft_author_key", progresso.autorAtual.agente)
        no.put("current_draft_sha256", FormatoDoRegistro.sha256(TrimJs.aparar(progresso.textoAtual)))
        no.put("round", maxOf(1, progresso.rodada))
        no.put("turn_index", maxOf(0, progresso.indiceDoTurno))
        no.putArray("round_roster").also { lista -> progresso.escala.forEach { lista.add(it.agente) } }
        no.putArray("valid_round_agents").also { lista -> progresso.agentesValidos.forEach { lista.add(it.agente) } }
        no.putArray("stable_serial_approval_agents").also { lista -> progresso.aprovacoesEstaveis.forEach { lista.add(it.agente) } }
        no.put("artifact_turn", maxOf(1, progresso.turnoDoArtefato))
        no.put("previous_artifact_id", progresso.artefatoAnteriorId)
        no.put("updated_at", FormatoDeInstante.iso(agora))
        return Json.ESTRITO.writeValueAsString(no)
    }

    /** `acceptedCustodyArtifact` (`sessions.ts:3052-3057`). */
    public fun custodiaAceita(artefato: ArtefatoEntidade): Boolean {
        val status = artefato.status.lowercase()
        return (artefato.papel == "draft" || artefato.papel == "revision") && (status == "ready" || status == "not_ready")
    }

    /** `acceptedChainArtifact` (`sessions.ts:3059-3064`). */
    public fun cadeiaAceita(artefato: ArtefatoEntidade): Boolean =
        (artefato.papel == "draft" || artefato.papel == "revision") && artefato.status.lowercase() !in setOf("blocked", "error", "running")

    /** `deliberativeArtifactReport` (`sessions.ts:3066-3077`). */
    public fun relatorioDeliberativo(artefato: ArtefatoEntidade): PromptsDaSessao.RelatorioDeTurno? {
        if (artefato.papel != "revision" || !custodiaAceita(artefato)) return null
        val status = artefato.status.uppercase()
        if (status in PromptsDaSessao.STATUS_NAO_DELIBERATIVOS) return null
        return PromptsDaSessao.RelatorioDeTurno(
            nome = Agentes.sanear(artefato.agente, Provedor.CLAUDE).rotulo,
            papel = "review",
            status = status,
            relatorio = artefato.relatorioDeRevisaoJson.takeIf { it.isNotEmpty() },
            artefato = artefato.id,
        )
    }

    /** O relatório do artefato de recuperação legado (`sessions.ts:3248-3253`), na ordem do `JSON.stringify`. */
    internal fun relatorioDeRecuperacao(autor: Provedor): String {
        val no = Json.ESTRITO.createObjectNode()
        no.put("reviewer", autor.agente)
        no.put("role", "legacy_custody_recovery")
        no.put("status", "ready")
        no.put("custody", "recovered")
        return Json.ESTRITO.writeValueAsString(no)
    }

    private fun texto(no: JsonNode?): String? = no?.takeIf { it.isTextual }?.textValue()

    /** `Number.isInteger`: número JSON de valor inteiro (`2.0` conta), dentro de `Int`. */
    private fun inteiro(no: JsonNode?): Int? {
        if (no == null || !no.isNumber) return null
        val valor = no.decimalValue()
        return try {
            valor.intValueExact()
        } catch (erro: ArithmeticException) {
            null
        }
    }

    /** Uma lista de chaves de provedor, ou `null` quando não é lista ou traz alguém desconhecido. */
    private fun agentes(no: JsonNode?): List<Provedor>? {
        if (no == null || !no.isArray) return null
        return no.map { item -> Agentes.porChave(texto(item)) ?: return null }
    }

    /** `String(x)` do JavaScript para a mensagem da versão. */
    private fun comoJs(no: JsonNode?): String = when {
        no == null -> "undefined"
        no.isNull -> "null"
        no.isTextual -> no.textValue()
        no.isNumber -> no.decimalValue().stripTrailingZeros().toPlainString()
        else -> no.toString()
    }
}
