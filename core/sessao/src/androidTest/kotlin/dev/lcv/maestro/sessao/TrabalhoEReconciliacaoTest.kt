package dev.lcv.maestro.sessao

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O worker e a reconciliação, com o `work-testing` oficial: o serviço em
 * primeiro plano sobe antes de qualquer chamada paga, todo desfecho é
 * `Result.success()`, uma parada do sistema é rotulada sob a cerca, e a
 * reconciliação da abertura só reenfileira o que não tinha chamada paga em
 * voo (decisão 16).
 */
@RunWith(AndroidJUnit4::class)
class TrabalhoEReconciliacaoTest {

    private val t = BancoDeTeste()
    private val d = DeliberacaoDeTeste(t)
    private val contexto: Context = t.contexto
    private val sequencia = ArrayList<String>()

    /** Um `ForegroundUpdater` que só registra a ordem e o tipo do serviço. */
    private val atualizador = ForegroundUpdater { _, _, info ->
        sequencia += "primeiroPlano:${info.foregroundServiceType}"
        Concluido
    }

    private val grafo = object : GrafoDaSessao {
        override val sessoes: RepositorioDeSessoes get() = t.sessoes
        override val notificacao: Notificacao = Notificacao(contexto)
        override fun deliberacao(parar: () -> Boolean, aoAvancar: suspend (Progresso) -> Unit): Deliberacao = Deliberacao(
            sessoes = t.sessoes, retomada = t.retomada, ponto = t.ponto, artefatos = t.artefatos, anexos = d.anexos,
            chamador = { provedor, pedido, restante ->
                sequencia += "chamada:${provedor.agente}"
                d.chamador.chamar(provedor, pedido, restante)
            },
            auditoria = d.auditoria, relogio = d.relogio, parar = parar, aoAvancar = aoAvancar,
        )
    }

    @Before
    fun iniciar() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder().setWorkerFactory(FabricaDeTrabalhos { grafo }).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun fechar() = t.fechar()

    private fun worker(id: String): TrabalhoDaSessao = TestListenableWorkerBuilder<TrabalhoDaSessao>(contexto)
        .setInputData(workDataOf(TrabalhoDaSessao.CHAVE_DA_SESSAO to id))
        .setWorkerFactory(FabricaDeTrabalhos { grafo })
        .setForegroundUpdater(atualizador)
        .build()

    @Test
    fun trabalhoSobeOPrimeiroPlanoAntesDaChamadaEDevolveSucesso() {
        val id = t.sessoes.criar(t.entrada(agentes = listOf(Provedor.CLAUDE, Provedor.CODEX))).id
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())

        val resultado = worker(id).startWork().get(60, TimeUnit.SECONDS)

        assertEquals(ListenableWorker.Result.success(), resultado)
        assertEquals("primeiroPlano:${android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC}", sequencia.first())
        assertEquals(listOf("chamada:claude", "chamada:codex"), sequencia.filter { it.startsWith("chamada") })
        // A notificação é atualizada a cada checkpoint (rascunho e turno).
        assertEquals(3, sequencia.count { it.startsWith("primeiroPlano") })
        assertEquals(Estados.CONVERGIDA, t.sessoes.carregar(id)!!.status)
    }

    @Test
    fun pausaEErroTambemSaoSucessoDoTrabalho() {
        // Uma pausa por custo e um erro inesperado: nenhum dos dois é nova tentativa do WorkManager.
        val pausada = t.sessoes.criar(t.entrada().copy(tetoDeCustoUsd = java.math.BigDecimal("0.01"))).id
        assertEquals(ListenableWorker.Result.success(), worker(pausada).startWork().get(60, TimeUnit.SECONDS))
        assertEquals(Estados.LIMITE_DE_CUSTO, t.sessoes.carregar(pausada)!!.status)

        val comErro = t.sessoes.criar(t.entrada()).id
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.rascunho(), lancar = IllegalStateException("disco cheio")))
        assertEquals(ListenableWorker.Result.success(), worker(comErro).startWork().get(60, TimeUnit.SECONDS))
        assertEquals(Estados.ERRO, t.sessoes.carregar(comErro)!!.status)

        val ausente = worker("android-inexistente").startWork().get(60, TimeUnit.SECONDS)
        assertEquals(ListenableWorker.Result.success(), ausente)
    }

    @Test
    fun paradaDoSistemaRotulaAPausaSobACercaESegundaExecucaoNaoPagaDeNovo() {
        val id = t.sessoes.criar(t.entrada(agentes = listOf(Provedor.CLAUDE, Provedor.CODEX))).id
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        val portao = CompletableDeferred<Unit>()
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(), antes = portao))
        val trabalho = worker(id)
        val futuro = trabalho.startWork()
        val inicio = System.currentTimeMillis()
        while (sequencia.count { it.startsWith("chamada") } < 2 && System.currentTimeMillis() - inicio < 10_000) Thread.sleep(20)
        assertEquals(2, sequencia.count { it.startsWith("chamada") })

        // O sistema para o worker (rede perdida) com a chamada ao Codex em voo: o WorkManager chama `stop(motivo)`
        // e cancela o futuro do trabalho (o `CoroutineWorker` 2.12 não cancela a corrotina sozinho no `onStopped`).
        trabalho.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        futuro.cancel(true)
        portao.complete(Unit)
        // O futuro fecha na hora; o rótulo é gravado pela corrotina cancelada logo depois: espera-se por ele.
        val fim = System.currentTimeMillis()
        while (!d.mensagens(id).last().startsWith(TrabalhoDaSessao.MENSAGEM_PARADA_PELO_SISTEMA) && System.currentTimeMillis() - fim < 10_000) Thread.sleep(20)
        assertTrue(futuro.isDone)
        val desfechoDoFuturo = runCatching { futuro.get() }.exceptionOrNull()?.toString()

        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, linha.status)
        assertEquals(
            "futuro=$desfechoDoFuturo stopReason=${trabalho.stopReason} eventos=${d.mensagens(id)}",
            "${TrabalhoDaSessao.MENSAGEM_PARADA_PELO_SISTEMA}rede desconectada",
            d.mensagens(id).last(),
        )
        assertEquals("codex", t.banco.execucoes().uma(linha.execucaoAtual!!)!!.chamadaEmVoo)

        // O WorkManager reenfileira: a segunda execução recusa seguir sobre a chamada paga sem resultado.
        sequencia.clear()
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        assertEquals(ListenableWorker.Result.success(), worker(id).startWork().get(60, TimeUnit.SECONDS))
        assertTrue(sequencia.none { it.startsWith("chamada") })
        assertEquals(Estados.ERRO, t.sessoes.carregar(id)!!.status)
        assertEquals(RepositorioDeSessoes.mensagemDeChamadaIndeterminada(Provedor.CODEX), t.sessoes.carregar(id)!!.erro)
    }

    @Test
    fun reconciliacaoReenfileiraSoQuemNaoTinhaChamadaEmVoo() = runBlocking(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(contexto)
        val agendador = Agendador(workManager)
        val pastaDeEvidencias = File(contexto.cacheDir, "evidencias-${t.arquivo.name}")
        val evidencias = ArmazemDeEvidenciasEmArquivo(t.banco, pastaDeEvidencias, t.relogio)
        val reconciliacao = Reconciliacao(t.banco, t.sessoes, t.retomada, agendador, { BancoDeTeste.TODAS_AS_CHAVES }, evidencias, d.anexos, d.relogio)

        // A: reivindicada por um worker que morreu, sem chamada em voo.
        val a = t.sessoes.criar(t.entrada()).id
        val execucaoA = (t.retomada.preparar(a) as Preparacao.Nova).execucao
        // B: idem, mas morreu com a chamada ao Codex em voo.
        val b = t.sessoes.criar(t.entrada()).id
        val execucaoB = (t.retomada.preparar(b) as Preparacao.Nova).execucao
        assertTrue(t.sessoes.marcarChamadaEmVoo(execucaoB, Provedor.CODEX))
        // C: viva no WorkManager (na fila, esperando a rede).
        val c = t.sessoes.criar(t.entrada()).id
        agendador.enfileirar(c)
        assertTrue(agendador.viva(c))
        val execucaoC = (t.retomada.preparar(c) as Preparacao.Nova).execucao
        // Um arquivo órfão de evidência e um de anexo.
        pastaDeEvidencias.mkdirs()
        val orfao = File(pastaDeEvidencias, "orfao-abc").apply { writeText("x") }

        val reenfileiradas = reconciliacao.naAbertura()

        assertEquals(listOf(a), reenfileiradas)
        val linhaA = t.sessoes.carregar(a)!!
        assertEquals(Estados.NA_FILA, linhaA.status)
        assertNull(linhaA.execucaoAtual)
        assertTrue(agendador.viva(a))
        assertEquals(RepositorioDeSessoes.MOTIVO_INTERROMPIDA, t.banco.execucoes().uma(execucaoA)!!.motivoDaParada)
        assertTrue(d.mensagens(a).contains(RepositorioDeSessoes.MENSAGEM_INTERROMPIDA))

        val linhaB = t.sessoes.carregar(b)!!
        assertEquals(Estados.ERRO, linhaB.status)
        assertEquals(RepositorioDeSessoes.mensagemDeChamadaIndeterminada(Provedor.CODEX), linhaB.erro)
        assertFalse(agendador.viva(b))
        assertNotNull(t.banco.execucoes().uma(execucaoB)!!.fim)

        val linhaC = t.sessoes.carregar(c)!!
        assertEquals(Estados.RODANDO, linhaC.status)
        assertEquals(execucaoC, linhaC.execucaoAtual)
        assertFalse(orfao.exists())

        // Uma segunda abertura não toca em nada: A e C têm trabalho vivo, B não está ativa.
        assertTrue(reconciliacao.naAbertura().isEmpty())
        assertEquals(Estados.NA_FILA, t.sessoes.carregar(a)!!.status)
    }

    @Test
    fun reconciliacaoNaoReenfileiraQuandoORetomarERecusado() = runBlocking(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(contexto)
        val agendador = Agendador(workManager)
        val evidencias = ArmazemDeEvidenciasEmArquivo(t.banco, File(contexto.cacheDir, "ev-${t.arquivo.name}"), t.relogio)
        // Sem chave para o Codex: `pedir` recusa, a sessão fica em `error`, retomável pelo operador.
        val semChave = Provedor.entries.associateWith { it != Provedor.CODEX }
        val reconciliacao = Reconciliacao(t.banco, t.sessoes, t.retomada, agendador, { semChave }, evidencias, d.anexos, d.relogio)
        val id = t.sessoes.criar(t.entrada()).id
        t.retomada.preparar(id)

        assertTrue(reconciliacao.naAbertura().isEmpty())

        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.ERRO, linha.status)
        assertFalse(agendador.viva(id))
        assertEquals("${Reconciliacao.MENSAGEM_RETOMADA_RECUSADA}Agentes indisponiveis para retomada: Codex.", d.mensagens(id).last())
    }

    @Test
    fun cancelamentoEntreALeituraEAEscritaNaoEDesfeitoPelaReconciliacao() = runBlocking(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(contexto)
        val id = t.sessoes.criar(t.entrada()).id
        t.retomada.preparar(id)
        // O operador cancela exatamente entre a pergunta ao WorkManager e a escrita da reconciliação.
        var cancelou = false
        val agendador = object : Agendador(workManager) {
            override fun viva(sessaoId: String): Boolean {
                if (sessaoId == id && !cancelou) {
                    cancelou = true
                    assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
                }
                return false
            }
        }
        val evidencias = ArmazemDeEvidenciasEmArquivo(t.banco, File(contexto.cacheDir, "ev2-${t.arquivo.name}"), t.relogio)
        val reconciliacao = Reconciliacao(t.banco, t.sessoes, t.retomada, agendador, { BancoDeTeste.TODAS_AS_CHAVES }, evidencias, d.anexos, d.relogio)

        assertTrue(reconciliacao.naAbertura().isEmpty())

        assertEquals(Estados.CANCELADA, t.sessoes.carregar(id)!!.status)
        assertFalse(agendador.viva(id))
        assertEquals(RepositorioDeSessoes.MENSAGEM_CANCELADA, d.mensagens(id).last())
    }

    @Test
    fun agendadorMantemUmTrabalhoPorSessao() {
        val workManager = WorkManager.getInstance(contexto)
        val agendador = Agendador(workManager)
        val primeiro = agendador.enfileirar("android-x")
        val segundo = agendador.enfileirar("android-x")
        val infos = workManager.getWorkInfosForUniqueWork(Agendador.nome("android-x")).get()
        assertEquals(1, infos.size)
        assertEquals(primeiro, infos.single().id)
        assertTrue(segundo != primeiro)
        assertTrue(agendador.viva("android-x"))
        agendador.cancelar("android-x")
        assertFalse(agendador.viva("android-x"))
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(primeiro).get()!!.state)
    }

    /** Um `ListenableFuture<Void>` já concluído, sem Guava. */
    private object Concluido : ListenableFuture<Void> {
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = true
        override fun get(): Void? = null
        override fun get(timeout: Long, unit: TimeUnit): Void? = null
        override fun addListener(listener: Runnable, executor: Executor) = executor.execute(listener)
    }

    private companion object {
        @Suppress("unused")
        val ID_QUALQUER: UUID = UUID.randomUUID()

        @Suppress("unused")
        fun info(info: ForegroundInfo): Int = info.foregroundServiceType
    }
}
