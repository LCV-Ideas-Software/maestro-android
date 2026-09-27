package dev.lcv.maestro.sessao

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `remainingSessionMs` e `sessionTimeExhausted`: o corte canônico de 2 s. */
class OrcamentoDeTempoTest {

    private val ancora = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `sem limite nao ha restante nem esgotamento`() {
        assertNull(OrcamentoDeTempo.restanteMs(ancora, null, ancora.plusSeconds(999_999)))
        assertNull(OrcamentoDeTempo.restanteMs(ancora, 0, ancora))
        assertNull(OrcamentoDeTempo.restanteMs(ancora, -5, ancora))
        assertFalse(OrcamentoDeTempo.esgotado(ancora, null, ancora.plusSeconds(999_999)))
    }

    @Test
    fun `restante e o limite menos o decorrido, nunca negativo`() {
        assertEquals(60_000L, OrcamentoDeTempo.restanteMs(ancora, 1, ancora))
        assertEquals(1_000L, OrcamentoDeTempo.restanteMs(ancora, 1, ancora.plusMillis(59_000)))
        assertEquals(0L, OrcamentoDeTempo.restanteMs(ancora, 1, ancora.plusSeconds(120)))
    }

    @Test
    fun `esgotado e restar menos de dois segundos`() {
        assertFalse(OrcamentoDeTempo.esgotado(ancora, 1, ancora.plusMillis(58_000)))
        assertTrue(OrcamentoDeTempo.esgotado(ancora, 1, ancora.plusMillis(58_001)))
        assertTrue(OrcamentoDeTempo.esgotado(ancora, 1, ancora.plusSeconds(120)))
    }

    @Test
    fun `a ancora e quem decide, o mesmo agora muda de resposta`() {
        val agora = ancora.plusSeconds(3600)
        assertTrue(OrcamentoDeTempo.esgotado(ancora, 30, agora))
        assertFalse(OrcamentoDeTempo.esgotado(agora, 30, agora))
    }
}
