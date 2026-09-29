package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.lcv.maestro.seguranca.CofreDeChaves
import kotlin.time.Duration.Companion.seconds
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A fábrica de produção não toca o WorkManager ao ser construída: a primeira
 * chamada a `WorkManager.getInstance` pode começar um trabalho pendente, e o
 * worker só nasce direito depois de a fábrica estar instalada.
 */
@RunWith(AndroidJUnit4::class)
class FabricaTest {

    @Test
    fun construirAFabricaNaoInicializaOWorkManager() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        var pedidos = 0
        val fabrica = Fabrica(contexto, CofreDeChaves.criar(contexto, 7.seconds), "teste", workManager = {
            pedidos += 1
            WorkManager.getInstance(contexto)
        })
        try {
            assertEquals(0, pedidos)
            fabrica.reconciliacao
            assertEquals(1, pedidos)
            fabrica.agendador
            assertEquals(1, pedidos)
        } finally {
            fabrica.banco.close()
        }
    }

    /**
     * Emenda A3: o agente de coleta que a auditoria e a busca de evidências usam
     * é montado com o e-mail gravado **naquele momento**, na mesma fábrica, sem
     * reiniciar o processo.
     */
    @Test
    fun oAgenteDeColetaLeOEmailGravadoACadaChamada() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val fabrica = Fabrica(contexto, CofreDeChaves.criar(contexto, 7.seconds), "teste")
        fun gravar(email: String?) = assertTrue(
            fabrica.configuracoes.salvar(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal.ONE, emailDeContato = Campo.Presente(email))) is Resultado.Ok,
        )
        try {
            gravar("pessoa@exemplo.org")
            assertEquals("pessoa@exemplo.org", fabrica.agenteDeColeta().emailDeContato)
            gravar("outra@exemplo.org")
            assertEquals("outra@exemplo.org", fabrica.agenteDeColeta().emailDeContato)
            gravar(null)
            assertNull(fabrica.agenteDeColeta().emailDeContato)
        } finally {
            fabrica.banco.close()
        }
    }
}
