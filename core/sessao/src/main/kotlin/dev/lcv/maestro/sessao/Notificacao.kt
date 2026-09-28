package dev.lcv.maestro.sessao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import androidx.work.ForegroundInfo
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.sessao.Agentes.rotulo

/**
 * A notificação do serviço em primeiro plano (especificação, seção 4.1): a
 * rodada, o agente da vez, o custo acumulado e a ação de cancelar. É
 * espelho da tela, não a superfície canônica: negada `POST_NOTIFICATIONS`,
 * ela some da gaveta e nada de essencial se perde.
 *
 * A ação de cancelar **não** é o `createCancelPendingIntent` do WorkManager:
 * ele só para o trabalho e deixa a linha `running`, e a reconciliação a
 * retomaria e pagaria de novo. O toque vai a [CancelamentoDaSessao], que
 * grava `blocked_cancelled` no Room primeiro e só então para o trabalho
 * (revisão cruzada de 27/09/2026, emenda A3).
 */
public class Notificacao(
    private val contexto: Context,
    /** O destino do toque na notificação: a tela da sessão, que o `:app` sabe abrir. */
    private val abrirSessao: ((sessaoId: String) -> PendingIntent)? = null,
) {

    private fun canal() {
        val gerente = contexto.getSystemService(NotificationManager::class.java)
        val canal = NotificationChannel(CANAL, contexto.getString(R.string.maestro_sessao_canal), NotificationManager.IMPORTANCE_LOW)
        canal.description = contexto.getString(R.string.maestro_sessao_canal_descricao)
        gerente.createNotificationChannel(canal)
    }

    public fun primeiroPlano(sessaoId: String, progresso: Progresso): ForegroundInfo {
        canal()
        val cancelar = PendingIntent.getBroadcast(
            contexto,
            sessaoId.hashCode(),
            CancelamentoDaSessao.intencao(contexto, sessaoId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val texto = contexto.getString(
            R.string.maestro_sessao_progresso,
            progresso.rodada,
            progresso.agente?.rotulo ?: contexto.getString(R.string.maestro_sessao_sem_agente),
            Custo.paraExibir(progresso.custoObservadoUsd).toPlainString(),
        )
        val notificacao = Notification.Builder(contexto, CANAL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(contexto.getString(R.string.maestro_sessao_titulo))
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { abrirSessao?.let { setContentIntent(it(sessaoId)) } }
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(contexto, android.R.drawable.ic_menu_close_clear_cancel),
                    contexto.getString(R.string.maestro_sessao_cancelar),
                    cancelar,
                ).build(),
            )
            .build()
        return ForegroundInfo(idDaNotificacao(sessaoId), notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    public companion object {
        public const val CANAL: String = "maestro.sessao"

        /** Um id por sessão: a notificação é atualizada no lugar a cada checkpoint. */
        public fun idDaNotificacao(sessaoId: String): Int = sessaoId.hashCode()
    }
}
