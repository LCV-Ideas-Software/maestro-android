package dev.lcv.maestro.sessao

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import java.math.BigDecimal
import kotlin.coroutines.cancellation.CancellationException

/** O que o worker precisa e de onde vem: em produção, a [Fabrica]; em teste, dublês sobre o mesmo Room. */
public interface GrafoDaSessao {
    public val sessoes: RepositorioDeSessoes
    public val notificacao: Notificacao
    public fun deliberacao(parar: () -> Boolean, aoAvancar: suspend (Progresso) -> Unit): Deliberacao
}

/**
 * A `WorkerFactory` oficial: é assim que um worker recebe dependências sem
 * localizador global (`Configuration.Builder().setWorkerFactory`). O `:app`
 * a instala na sua `Configuration.Provider`; os testes, no
 * `TestListenableWorkerBuilder`.
 *
 * O grafo é resolvido **quando um worker nasce**, não quando a fábrica é
 * criada: a configuração do WorkManager — que carrega esta fábrica — é lida
 * na primeira chamada a `WorkManager.getInstance`, e uma fábrica que
 * capturasse o grafo pronto amarraria essa configuração à construção da
 * [Fabrica] (revisão cruzada de 28/09/2026 sobre o plano do `:app`, emenda
 * A1). A [Fabrica], por sua vez, só toca o WorkManager depois de instalada.
 */
public class FabricaDeTrabalhos(private val grafo: () -> GrafoDaSessao) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == TrabalhoDaSessao::class.java.name) TrabalhoDaSessao(appContext, workerParameters, grafo()) else null
}

/**
 * A deliberação no aparelho (especificação, seção 4.1): um `CoroutineWorker`
 * com `setForeground()` do tipo `dataSync` **antes** de qualquer chamada
 * paga. O trabalho termina com `Result.success()` em todo desfecho da
 * [Deliberacao]: uma pausa ou um erro é a sessão parada num status retomável,
 * nunca uma nova tentativa do WorkManager, que repetiria um rascunho pago.
 *
 * Uma parada pelo sistema — teto do `dataSync`, cota de jobs, rede perdida,
 * cancelamento — chega como cancelamento da corrotina: o estado já está no
 * checkpoint do último turno, e o que se faz aqui é só **rotular** a pausa
 * com `getStopReason()`, sob a cerca da execução, da melhor forma possível
 * (seção 4.1: rótulo, não salvaguarda). O WorkManager reenfileira o
 * trabalho parado, e a segunda execução passa por `preparar` — que recusa
 * seguir sobre uma chamada paga sem resultado (decisão 16).
 */
public class TrabalhoDaSessao(
    contexto: Context,
    parametros: WorkerParameters,
    private val grafo: GrafoDaSessao,
) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result {
        val sessaoId = inputData.getString(CHAVE_DA_SESSAO) ?: return Result.failure()
        try {
            setForeground(grafo.notificacao.primeiroPlano(sessaoId, Progresso(rodada = 1, agente = null, custoObservadoUsd = BigDecimal.ZERO)))
        } catch (erro: IllegalStateException) {
            // O sistema não deixou subir o serviço em primeiro plano: nada foi pago nem
            // escrito; a reconciliação da abertura marca a sessão como interrompida.
            return Result.failure()
        }
        val deliberacao = grafo.deliberacao(
            parar = { isStopped },
            aoAvancar = { progresso ->
                try {
                    setForeground(grafo.notificacao.primeiroPlano(sessaoId, progresso))
                } catch (erro: IllegalStateException) {
                    // A notificação é espelho; a tela é a superfície canônica (seção 4.1).
                }
            },
        )
        try {
            deliberacao.executar(sessaoId)
        } catch (cancelada: CancellationException) {
            rotular(deliberacao, sessaoId)
            throw cancelada
        }
        return Result.success()
    }

    private fun rotular(deliberacao: Deliberacao, sessaoId: String) {
        val execucao = deliberacao.execucaoEmCurso ?: return
        val motivo = Agendador.rotuloDaParada(stopReason)
        val evento = EventoDaSessao(
            em = FormatoDeInstante.iso(java.time.Instant.now()),
            status = EventoDaSessao.BLOQUEADO,
            mensagem = "$MENSAGEM_PARADA_PELO_SISTEMA$motivo",
        )
        try {
            // O rótulo não é desfecho da chamada paga: o marcador dela fica (decisão 16).
            grafo.sessoes.anotar(sessaoId, evento, execucao = execucao, limpaChamada = false)
        } catch (erro: RuntimeException) {
            // Melhor esforço: o processo pode estar morrendo.
        }
    }

    public companion object {
        public const val CHAVE_DA_SESSAO: String = "sessaoId"
        public const val MENSAGEM_PARADA_PELO_SISTEMA: String = "Sessao interrompida pelo sistema: "
    }
}
