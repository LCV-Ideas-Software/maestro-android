package dev.lcv.maestro.ui

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O aviso do orçamento de seis horas: abaixo do limite da sessão, ou de uma hora sem limite (seção 4.1, item 3). */
class AvisoDeOrcamentoTest {

    @Test
    fun `com limite avisa quando resta menos que o limite`() {
        assertTrue(AvisoDeOrcamento.mostrar(Duration.ofMinutes(119), 120))
        assertFalse(AvisoDeOrcamento.mostrar(Duration.ofMinutes(120), 120))
        assertFalse(AvisoDeOrcamento.mostrar(Duration.ofHours(6), 300))
    }

    @Test
    fun `sem limite avisa abaixo de uma hora`() {
        assertTrue(AvisoDeOrcamento.mostrar(Duration.ofMinutes(30), null))
        // A fronteira dos dois lados: 59 min 59 s avisa, 60 min não.
        assertTrue(AvisoDeOrcamento.mostrar(Duration.ofSeconds(3599), null))
        assertFalse(AvisoDeOrcamento.mostrar(Duration.ofMinutes(60), null))
    }
}
