package dev.lcv.maestro

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.Fabrica
import dev.lcv.maestro.sessao.FabricaDeTrabalhos
import dev.lcv.maestro.sessao.FormatoDeInstante
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.SessaoEntidade
import dev.lcv.maestro.sessao.Taxas
import dev.lcv.maestro.sessao.TrabalhoDaSessao
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O arranque real do processo (plano do `:app`, emendas A1 e A11): o
 * `Application` instalou a fábrica do processo, a configuração que o
 * WorkManager leu na inicialização sob demanda é a dele — com a fábrica de
 * trabalhos do `:core:sessao` —, e a entrada em primeiro plano reconcilia a
 * sessão que ficou "rodando" sem trabalho vivo. Usa o grafo de produção.
 */
@RunWith(AndroidJUnit4::class)
class AplicacaoTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val aplicativo = contexto.maestro

    @Test
    fun oApplicationInstalaAFabricaEAConfiguracaoDoWorkManagerEADele() {
        assertSame(aplicativo.grafo, Fabrica.doProcesso)
        assertTrue(WorkManager.getInstance(contexto).configuration.workerFactory is FabricaDeTrabalhos)
    }

    /**
     * Emenda A1: o worker da sessão nasce pela fábrica que o WorkManager do
     * processo leu na inicialização sob demanda, com o grafo instalado. A
     * fábrica padrão não saberia criá-lo (o construtor dele pede o grafo), e um
     * fornecedor que não resolvesse o grafo lançaria aqui.
     */
    @Test
    fun aFabricaDoWorkManagerDoProcessoCriaOTrabalhoDaSessao() {
        val trabalho = TestListenableWorkerBuilder<TrabalhoDaSessao>(contexto)
            .setWorkerFactory(WorkManager.getInstance(contexto).configuration.workerFactory)
            .build()
        assertEquals(TrabalhoDaSessao::class, trabalho::class)
    }

    private fun esperar(prazoEmMs: Long = 10_000, condicao: () -> Boolean) {
        val limite = System.nanoTime() + prazoEmMs * 1_000_000
        while (!condicao()) {
            check(System.nanoTime() < limite) { "a condição não se cumpriu em $prazoEmMs ms" }
            Thread.sleep(50)
        }
    }

    @Test
    fun aEntradaEmPrimeiroPlanoReconciliaASessaoSemTrabalhoVivo() {
        // O processo precisa estar fora do primeiro plano para a próxima Activity disparar ON_START:
        // o ProcessLifecycleOwner despacha ON_STOP 700 ms depois da última Activity parar.
        esperar { !ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val grafo = aplicativo.grafo
        val id = "android-aplicacao-${UUID.randomUUID()}"
        val em = FormatoDeInstante.iso(Instant.now())
        grafo.banco.sessoes().inserir(
            SessaoEntidade(
                id = id,
                titulo = "Sessão órfã",
                pedido = "p",
                protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
                agenteInicial = Provedor.CLAUDE.agente,
                liderDoCiclo = Provedor.CLAUDE.agente,
                agentesAtivosJson = RepositorioDeSessoes.agentesJson(listOf(Provedor.CLAUDE, Provedor.CODEX)),
                status = Estados.RODANDO,
                tetoDeCustoE8 = Dinheiro.paraE8(BigDecimal.ONE),
                taxasJson = Taxas.paraJson(Taxas.PADRAO),
                modelosJson = RepositorioDeSessoes.modelosJson(),
                criadaEm = em,
                atualizadaEm = em,
            ),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                esperar { grafo.sessoes.carregar(id)?.status == Estados.ERRO }
            }
            assertEquals(RepositorioDeSessoes.MENSAGEM_INTERROMPIDA, grafo.sessoes.carregar(id)?.erro)
        } finally {
            grafo.banco.openHelper.writableDatabase.execSQL("DELETE FROM sessoes WHERE id = ?", arrayOf(id))
        }
    }
}
