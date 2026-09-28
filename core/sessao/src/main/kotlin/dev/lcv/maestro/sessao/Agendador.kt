package dev.lcv.maestro.sessao

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.UUID

/**
 * Um trabalho único por sessão no WorkManager (especificação, seção 4.1):
 * `enqueueUniqueWork("sessao-<id>", KEEP, …)` com rede exigida. `KEEP` é a
 * regra: um segundo pedido enquanto o primeiro vive não cria segunda
 * execução; a reivindicação em `preparar` já protege o banco, e o nome único
 * protege a fila. O WorkManager é a fonte da verdade sobre "existe execução
 * viva" (seção 4.3).
 */
public open class Agendador(private val workManager: WorkManager) {

    public open fun enfileirar(sessaoId: String): UUID {
        val pedido = OneTimeWorkRequestBuilder<TrabalhoDaSessao>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(TrabalhoDaSessao.CHAVE_DA_SESSAO to sessaoId))
            .addTag(ETIQUETA)
            .build()
        workManager.enqueueUniqueWork(nome(sessaoId), ExistingWorkPolicy.KEEP, pedido)
        return pedido.id
    }

    /** Há um `WorkInfo` vivo (na fila, rodando ou bloqueado por restrição) para a sessão. Bloqueante: chamar fora da linha da interface. */
    public open fun viva(sessaoId: String): Boolean = infos(sessaoId).any { it.state in VIVOS }

    /**
     * O motivo da última parada que o WorkManager registrou para a sessão
     * (`WorkInfo.getStopReason()`), ou `null` quando não houve parada ou o
     * trabalho ainda vive. É rótulo, não salvaguarda (seção 4.1).
     */
    public open fun ultimaParada(sessaoId: String): Int? =
        infos(sessaoId).lastOrNull { it.state.isFinished }?.stopReason?.takeIf { it != WorkInfo.STOP_REASON_NOT_STOPPED }

    public open fun cancelar(sessaoId: String) {
        workManager.cancelUniqueWork(nome(sessaoId))
    }

    private fun infos(sessaoId: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(nome(sessaoId)).get()

    public companion object {
        public const val ETIQUETA: String = "maestro.sessao"
        public fun nome(sessaoId: String): String = "sessao-$sessaoId"
        private val VIVOS = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)

        /** O rótulo em texto de um `stopReason`, para o jornal (seção 4.1: `STOP_REASON_TIMEOUT` e `STOP_REASON_QUOTA` são os dois limites). */
        public fun rotuloDaParada(motivo: Int): String = when (motivo) {
            WorkInfo.STOP_REASON_TIMEOUT -> "tempo limite do servico dataSync"
            WorkInfo.STOP_REASON_QUOTA -> "cota de jobs do Android"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelamento pelo aplicativo"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "rede desconectada"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "estado do aparelho"
            WorkInfo.STOP_REASON_USER -> "parada pelo usuario"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "processamento do sistema"
            WorkInfo.STOP_REASON_APP_STANDBY -> "aplicativo em espera"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "restricao de segundo plano"
            else -> "outro ($motivo)"
        }
    }
}
