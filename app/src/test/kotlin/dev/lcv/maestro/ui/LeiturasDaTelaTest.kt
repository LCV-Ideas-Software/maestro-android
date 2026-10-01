package dev.lcv.maestro.ui

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** A regra "aviso e segue" das leituras de tela (decisão 25 do operador, estendida em 30/09/2026, #80). */
@OptIn(ExperimentalCoroutinesApi::class)
class LeiturasDaTelaTest {

    private val avisos = mutableListOf<Mensagem>()
    private val leituras = LeiturasDaTela { avisos += it }

    /** Uma fonte que falha com [quebrado] ligado a cada assinatura, e senão entrega o que [valores] tiver. */
    private class Fonte {
        var quebrado = true
        var assinaturas = 0
        val valores = Channel<String>(Channel.UNLIMITED)
        val fluxo = flow {
            assinaturas++
            if (quebrado) throw IOException("disco")
            for (valor in valores) {
                if (valor == "falha") throw IOException("disco de novo")
                emit(valor)
            }
        }
    }

    @Test
    fun `a leitura que falha antes de ler entrega o vazio com o motivo e so le de novo na volta da tela`() = runTest {
        val fonte = Fonte()
        val lidas = mutableListOf<Lida<String>>()
        val coleta = launch { leituras.observar(fonte.fluxo, "vazio").toList(lidas) }
        advanceUntilIdle()
        assertEquals(listOf(Lida("vazio", "disco")), lidas)
        assertEquals(listOf(LeiturasDaTela.avisoDeLeitura("disco")), avisos)
        // A abertura não é volta, e o disco consertado sem volta não relê: nada de laço.
        leituras.voltou()
        fonte.quebrado = false
        advanceUntilIdle()
        assertEquals(1, fonte.assinaturas)
        leituras.voltou()
        fonte.valores.send("lido")
        advanceUntilIdle()
        assertEquals(2, fonte.assinaturas)
        assertEquals(Lida("lido"), lidas.last())
        coleta.cancel()
    }

    @Test
    fun `a leitura que falha depois de ler nao troca o que a tela mostrava`() = runTest {
        val fonte = Fonte().apply { quebrado = false }
        val lidas = mutableListOf<Lida<String>>()
        val coleta = launch { leituras.observar(fonte.fluxo, "vazio").toList(lidas) }
        fonte.valores.send("primeiro")
        fonte.valores.send("falha")
        advanceUntilIdle()
        assertEquals(listOf(Lida("primeiro")), lidas)
        assertEquals(listOf(LeiturasDaTela.avisoDeLeitura("disco de novo")), avisos)
        coleta.cancel()
    }

    @Test
    fun `o que ja foi mostrado vale para a observacao recriada do mesmo conteudo`() = runTest {
        val leu = AtomicBoolean(true)
        val lidas = mutableListOf<Lida<String>>()
        val coleta = launch { leituras.observar(Fonte().fluxo, "vazio", leu).toList(lidas) }
        advanceUntilIdle()
        assertTrue(lidas.isEmpty())
        coleta.cancel()
    }

    @Test
    fun `varias falhas na mesma volta dao um aviso e um toque reabre o aviso`() = runTest {
        leituras.falhou(IOException("disco"))
        leituras.falhou(IOException("disco"))
        assertEquals(1, avisos.size)
        leituras.pedido()
        leituras.falhou(IOException("disco"))
        assertEquals(2, avisos.size)
        leituras.voltou()
        leituras.voltou()
        leituras.falhou(IOException("disco"))
        assertEquals(3, avisos.size)
    }

    @Test
    fun `o aviso sai com a mensagem de quem pediu`() = runTest {
        val propria = Mensagem.Literal("própria")
        leituras.falhou(IOException("disco")) { propria }
        assertEquals(listOf<Mensagem>(propria), avisos)
    }

    @Test
    fun `o que nao e armazenamento e o cancelamento seguem adiante sem aviso`() = runTest {
        assertFailsWith<IllegalStateException> { leituras.falhou(IllegalStateException("outra coisa")) }
        assertFailsWith<CancellationException> { leituras.falhou(CancellationException("saiu")) }
        assertFailsWith<IllegalStateException> {
            leituras.observar(flow<String> { throw IllegalStateException("outra coisa") }, "vazio").toList()
        }
        assertTrue(avisos.isEmpty())
    }

    @Test
    fun `a volta corrente comeca em zero e so a partir da segunda chamada anda`() {
        assertEquals(0, leituras.volta.value)
        leituras.voltou()
        assertEquals(0, leituras.volta.value)
        leituras.voltou()
        assertEquals(1, leituras.volta.value)
    }
}
