package dev.lcv.maestro

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A verificação da abertura e a inicialização do WorkManager sob a decisão 25 estendida (#80). */
class ReconciliacaoDaAberturaTest {

    @Test
    fun `a falha do WorkManager de armazenamento da o motivo da causa, e o resto segue adiante`() {
        val abertura = ReconciliacaoDaAbertura {}
        // O usuário ainda bloqueado: sem causa. E uma causa que não é armazenamento. As duas seguem adiante (#81).
        assertFailsWith<IllegalStateException> { abertura.falhouNoWorkManager(IllegalStateException("usuário bloqueado")) }
        assertFailsWith<IllegalStateException> {
            abertura.falhouNoWorkManager(IllegalStateException("embrulho", IllegalArgumentException("outra coisa")))
        }
        assertNull(abertura.falhaDoWorkManager.value)
        abertura.falhouNoWorkManager(IllegalStateException("embrulho", IOException("disco")))
        assertEquals("disco", abertura.falhaDoWorkManager.value?.motivo)
    }

    @Test
    fun `a verificacao que falha no armazenamento vira falha, e a seguinte a apaga ja ao recomecar`() = runTest {
        var disco: IOException? = IOException("disco")
        val porta = CompletableDeferred<Unit>()
        val abertura = ReconciliacaoDaAbertura { disco?.let { throw it } ?: porta.await() }
        abertura.executar()
        assertEquals("disco", abertura.falhaDaReconciliacao.value?.motivo)
        disco = null
        val seguinte = launch { abertura.executar() }
        runCurrent()
        // Ainda correndo: a falha da tentativa anterior já não vale.
        assertNull(abertura.falhaDaReconciliacao.value)
        porta.complete(Unit)
        seguinte.join()
        assertNull(abertura.falhaDaReconciliacao.value)
    }

    @Test
    fun `o que nao e armazenamento segue adiante`() = runTest {
        val abertura = ReconciliacaoDaAbertura { throw IllegalArgumentException("outra coisa") }
        assertFailsWith<IllegalArgumentException> { abertura.executar() }
    }
}
