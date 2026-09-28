package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.ValorJson
import dev.lcv.maestro.provedores.Pedido
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado as RespostaDoProvedor
import dev.lcv.maestro.provedores.Uso
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration as DuracaoKt
import kotlinx.coroutines.CompletableDeferred

/**
 * A deliberação sobre o Room em arquivo de [BancoDeTeste], com o chamador e
 * a auditoria substituídos por dublês combinados: cada chamada paga vem de
 * uma fila por provedor, e a auditoria responde pelo texto. O relógio é
 * próprio e avança por [deslocamento], para o teto de tempo ser provado sem
 * espera real (especificação, seção 8).
 */
internal class DeliberacaoDeTeste(val t: BancoDeTeste) {

    /** Uma resposta combinada; [antes] segura a chamada até ser completado (para o cancelamento em voo). */
    class Combinada(val resposta: RespostaDoProvedor, val antes: CompletableDeferred<Unit>? = null, val lancar: Throwable? = null)

    val filas = HashMap<Provedor, ArrayDeque<Combinada>>()
    val chamadas = ArrayList<Pair<Provedor, Pedido>>()
    var deslocamento: Duration = Duration.ZERO
    val base: Instant = Instant.parse("2026-09-25T12:00:00Z")
    val relogio: () -> Instant = { base.plus(deslocamento) }
    var sementes: ArrayDeque<Long> = ArrayDeque()
    var parar: () -> Boolean = { false }
    val progressos = ArrayList<Progresso>()

    /** A auditoria por texto: `null` aprova; o contador diz quantas vezes o motor foi chamado. */
    var auditar: (String) -> AuditoriaFinal.Falha? = { null }
    var auditorias = 0
    var textosAuditados = ArrayList<String>()

    val chamador = Chamador { provedor, pedido, _ ->
        chamadas += provedor to pedido
        val proxima = filas[provedor]?.removeFirstOrNull() ?: error("sem resposta combinada para ${provedor.agente} (chamada ${chamadas.size})")
        proxima.antes?.await()
        proxima.lancar?.let { throw it }
        proxima.resposta
    }

    val auditoria = AuditoriaDaSessao { _, texto, _ ->
        auditorias += 1
        textosAuditados += texto
        auditar(texto)
    }

    val anexos = AnexosDaSessao(t.banco, File(t.contexto.cacheDir, "anexos-${t.arquivo.name}"), t.relogio)

    fun deliberacao(): Deliberacao = Deliberacao(
        sessoes = t.sessoes,
        retomada = t.retomada,
        ponto = t.ponto,
        artefatos = t.artefatos,
        anexos = anexos,
        chamador = chamador,
        auditoria = auditoria,
        relogio = relogio,
        semente = { sementes.removeFirstOrNull() ?: 0L },
        parar = { parar() },
        aoAvancar = { progressos += it },
    )

    fun responde(provedor: Provedor, vararg respostas: Combinada) {
        filas.getOrPut(provedor) { ArrayDeque() }.addAll(respostas)
    }

    fun responde(provedor: Provedor, vararg respostas: RespostaDoProvedor) {
        responde(provedor, *respostas.map { Combinada(it) }.toTypedArray())
    }

    fun mensagens(id: String): List<String> = t.mensagens(id)

    fun eventos(id: String): List<EventoDaSessao> = t.sessoes.eventos(id)

    companion object {
        val USO = Uso(tokensDeEntrada = 1_000, tokensDeSaida = 500)

        fun concluida(texto: String, uso: Uso = USO) = RespostaDoProvedor.Concluida(texto, uso)

        fun rascunho(texto: String = TEXTO_A) = concluida(texto)

        /** `MAESTRO_STATUS: READY` com custódia inalterada: uma aprovação estável. */
        fun pronto(revisor: Provedor = Provedor.CODEX) = concluida(
            "MAESTRO_STATUS: READY\n<maestro_revision_report>\n" +
                "{ \"reviewer\": \"${revisor.agente}\", \"status\": \"READY\", \"custody\": \"unchanged\", \"changes\": [] }\n" +
                "</maestro_revision_report>",
        )

        /** Sem a linha `MAESTRO_STATUS`: o web lê `NOT_READY`; com a custódia inalterada, é violação de contrato. */
        fun semStatus(revisor: Provedor = Provedor.CODEX) = concluida(
            "<maestro_revision_report>\n" +
                "{ \"reviewer\": \"${revisor.agente}\", \"custody\": \"unchanged\", \"changes\": [] }\n" +
                "</maestro_revision_report>",
        )

        /** `NOT_READY` sem texto revisado: contrato violado (o revisor tem de corrigir ou aprovar). */
        fun naoProntoSemMudanca(revisor: Provedor = Provedor.CODEX) = concluida(
            "MAESTRO_STATUS: NOT_READY\n<maestro_revision_report>\n" +
                "{ \"reviewer\": \"${revisor.agente}\", \"status\": \"NOT_READY\", \"custody\": \"unchanged\", \"changes\": [] }\n" +
                "</maestro_revision_report>",
        )

        const val TEXTO_A = "Alpha aprovado.\n\nBeta aprovado."
        const val TEXTO_B = "Alpha aprovado.\n\nBeta reescrito pelo revisor."

        /** Uma revisão legítima de [TEXTO_A] para [TEXTO_B]: o segundo bloco declarado, com o registro de procedência. */
        fun revisado(revisor: Provedor = Provedor.CODEX, status: String = "NOT_READY", texto: String = TEXTO_B) = concluida(
            "MAESTRO_STATUS: $status\n<maestro_revision_report>\n" +
                """{
                  "reviewer": "${revisor.agente}", "status": "$status", "custody": "revised",
                  "changes": [{"passage": "Beta", "reason": "clareza", "protocol_basis": "§1", "required": true}],
                  "changed_blocks": [{"block_id": "B0002", "protocol_basis": "§1"}],
                  "revised_block_origins": [
                    {"prefix": "Alpha aprovado.", "origin": "B0001"},
                    {"prefix": "Beta reescrito pelo revisor.", "origin": "B0002"}
                  ]
                }""" +
                "\n</maestro_revision_report>\n<maestro_final_text>\n$texto\n</maestro_final_text>",
        )

        /** A revisão inversa, de [TEXTO_B] para [TEXTO_A]: com [revisado], duas custódias que nunca convergem. */
        fun revisadoDeVolta(revisor: Provedor, status: String = "NOT_READY") = concluida(
            "MAESTRO_STATUS: $status\n<maestro_revision_report>\n" +
                """{
                  "reviewer": "${revisor.agente}", "status": "$status", "custody": "revised",
                  "changes": [{"passage": "Beta", "reason": "clareza", "protocol_basis": "§1", "required": true}],
                  "changed_blocks": [{"block_id": "B0002", "protocol_basis": "§1"}],
                  "revised_block_origins": [
                    {"prefix": "Alpha aprovado.", "origin": "B0001"},
                    {"prefix": "Beta aprovado.", "origin": "B0002"}
                  ]
                }""" +
                "\n</maestro_revision_report>\n<maestro_final_text>\n$TEXTO_A\n</maestro_final_text>",
        )

        fun falha(portao: String, motivo: String): AuditoriaFinal.Falha =
            AuditoriaFinal.Falha(motivo, ValorJson.objeto("gate" to ValorJson.texto(portao), "policy" to ValorJson.texto("teste")))

        val SEM_TEMPO: DuracaoKt? = null
    }
}
