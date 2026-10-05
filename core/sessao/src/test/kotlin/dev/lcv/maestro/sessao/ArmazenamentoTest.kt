package dev.lcv.maestro.sessao

import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * O classificador único da decisão 25 (e da extensão de 30/09/2026, #80). As classes do SQLite são stubs do
 * android.jar na JVM; o teste delas é o `ArmazenamentoNoAparelhoTest`.
 */
class ArmazenamentoTest {

    @Test
    fun `o arquivo que nao grava nem le e armazenamento`() {
        assertEquals("disco", motivoDeArmazenamento(IOException("disco")))
    }

    @Test
    fun `o armazenamento embrulhado pelo get do WorkManager chega desembrulhado`() {
        assertEquals("banco do WorkManager", motivoDeArmazenamento(ExecutionException(IOException("banco do WorkManager"))))
    }

    @Test
    fun `o que nao e armazenamento segue adiante, embrulhado ou nao, e o cancelamento tambem`() {
        val estado = IllegalStateException("outra coisa")
        assertSame(estado, assertFailsWith<IllegalStateException> { motivoDeArmazenamento(estado) })
        val embrulhada = ExecutionException(IllegalStateException("outra coisa"))
        assertSame(embrulhada, assertFailsWith<ExecutionException> { motivoDeArmazenamento(embrulhada) })
        val semCausa = ExecutionException("sem causa", null)
        assertSame(semCausa, assertFailsWith<ExecutionException> { motivoDeArmazenamento(semCausa) })
        val cancelamento = CancellationException("saiu da tela")
        assertSame(cancelamento, assertFailsWith<CancellationException> { motivoDeArmazenamento(cancelamento) })
    }

    // Decisão 26 do operador (01/10/2026, #82): o acesso negado ao documento escolhido no seletor.

    @Test
    fun `o acesso que o provedor nega ao documento escolhido e armazenamento, com o motivo`() {
        assertEquals("negado pelo provedor", motivoDoDocumento(SecurityException("negado pelo provedor")))
        assertEquals("disco", motivoDoDocumento(IOException("disco")))
        val estado = IllegalStateException("outra coisa")
        assertSame(estado, assertFailsWith<IllegalStateException> { motivoDoDocumento(estado) })
    }

    @Test
    fun `fora da fronteira do documento o erro de permissao segue adiante`() {
        val permissao = SecurityException("uma permissão do sistema que falta")
        assertSame(permissao, assertFailsWith<SecurityException> { motivoDeArmazenamento(permissao) })
    }
}
