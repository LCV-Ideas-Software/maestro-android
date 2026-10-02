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
 *
 * O armazenamento que falha ao gravar o cancelamento é uma notificação com o
 * motivo, e o trabalho segue (decisão 25 estendida, #80): parar o trabalho sem
 * a transição é o que a ordem acima proíbe. A recusa sem exceção (a sessão já
 * finalizada ou que mudou de estado no meio) segue como antes da #80: o
 * trabalho é cancelado.
 */
public class CancelamentoDaSessao : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intencao: Intent) {
        if (intencao.action != ACAO) return
        val sessaoId = intencao.getStringExtra(EXTRA_SESSAO) ?: return
        val fabrica = Fabrica.doProcesso ?: return
        val pendente = goAsync()
        Thread {
            try {
                cancelar(sessaoId, fabrica.sessoes, fabrica.agendador) { motivo -> fabrica.notificacao.cancelamentoFalhou(sessaoId, motivo) }
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

        /**
         * O corpo do receptor, com as dependências à parte para o teste: a transição no Room e, só depois de ela
         * voltar sem exceção, o cancelamento do trabalho. O armazenamento que falha vai a [avisar] com o motivo, e o
         * trabalho não é cancelado; o que não é armazenamento segue adiante.
         */
        public fun cancelar(sessaoId: String, sessoes: RepositorioDeSessoes, agendador: Agendador, avisar: (motivo: String) -> Unit) {
            try {
                sessoes.cancelar(sessaoId)
            } catch (erro: Exception) {
                avisar(motivoDeArmazenamento(erro))
                return
            }
            agendador.cancelar(sessaoId)
        }
    }
}
