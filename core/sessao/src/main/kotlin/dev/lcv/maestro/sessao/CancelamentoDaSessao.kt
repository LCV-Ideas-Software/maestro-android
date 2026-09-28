package dev.lcv.maestro.sessao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * O toque em "cancelar" na notificação: a transição no Room vem primeiro
 * (`cancelar`: `blocked_cancelled`, evento e execução fechada, sob o portão),
 * e só depois o trabalho do WorkManager é cancelado para a chamada em voo
 * parar logo. A ordem importa: parar o trabalho sem a transição deixaria a
 * linha `running`, e a reconciliação da abertura a retomaria e pagaria de
 * novo (revisão cruzada de 27/09/2026, emenda A3). O worker, por sua vez,
 * já trata `blocked_cancelled` como parada em cada releitura.
 *
 * O receptor não é exportado e só age com a [Fabrica] instalada pelo
 * aplicativo; o `Application` é criado antes de qualquer receptor.
 */
public class CancelamentoDaSessao : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intencao: Intent) {
        if (intencao.action != ACAO) return
        val sessaoId = intencao.getStringExtra(EXTRA_SESSAO) ?: return
        val fabrica = Fabrica.doProcesso ?: return
        val pendente = goAsync()
        Thread {
            try {
                fabrica.sessoes.cancelar(sessaoId)
                fabrica.agendador.cancelar(sessaoId)
            } finally {
                pendente.finish()
            }
        }.start()
    }

    public companion object {
        public const val ACAO: String = "dev.lcv.maestro.sessao.CANCELAR"
        public const val EXTRA_SESSAO: String = "sessaoId"

        public fun intencao(contexto: Context, sessaoId: String): Intent =
            Intent(contexto, CancelamentoDaSessao::class.java).setAction(ACAO).putExtra(EXTRA_SESSAO, sessaoId)
    }
}
