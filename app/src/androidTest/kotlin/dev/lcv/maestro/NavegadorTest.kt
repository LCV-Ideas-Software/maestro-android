package dev.lcv.maestro

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O navegador do sistema da captura assistida: o disparo que o Android recusa volta como a falha
 * que o registro de passagem anota, e não derruba o aplicativo. Nenhum navegador abre: o contexto
 * é um dublê que só guarda ou recusa o pedido.
 */
@RunWith(AndroidJUnit4::class)
class NavegadorTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    private fun contextoQue(recusa: RuntimeException?, pedidos: MutableList<Intent> = mutableListOf()) = object : ContextWrapper(contexto) {
        override fun startActivity(intent: Intent) {
            recusa?.let { throw it }
            pedidos += intent
        }
    }

    @Test
    fun oPedidoEUmActionViewNavegavelSoComAUrl() {
        val pedidos = mutableListOf<Intent>()
        assertNull(Navegador.DO_SISTEMA.abrir(contextoQue(null, pedidos), "https://exemplo.org/a"))
        val pedido = pedidos.single()
        assertEquals(Intent.ACTION_VIEW, pedido.action)
        assertEquals("https://exemplo.org/a", pedido.dataString)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), pedido.categories)
    }

    @Test
    fun semNavegadorAFalhaVoltaComoTexto() {
        assertEquals(
            "failed to open system default browser: nenhum aplicativo",
            Navegador.DO_SISTEMA.abrir(contextoQue(ActivityNotFoundException("nenhum aplicativo")), "https://exemplo.org/a"),
        )
    }

    @Test
    fun oDisparoBarradoPorPoliticaVoltaComoTexto() {
        // Achado do Codex na #78: uma política ou um perfil de trabalho faz o `startActivity` lançar `SecurityException`.
        assertEquals(
            "failed to open system default browser: bloqueado pela política",
            Navegador.DO_SISTEMA.abrir(contextoQue(SecurityException("bloqueado pela política")), "https://exemplo.org/a"),
        )
    }
}
