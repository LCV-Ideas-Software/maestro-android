package dev.lcv.maestro.ui

import java.math.BigDecimal
import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** `formatBytes`, `eventDate` e os valores em dólar, contra o que o web mostra. */
class FormatosTest {

    @Test
    fun `formatBytes nas fronteiras do web`() {
        assertEquals("0 B", Formatos.bytes(0))
        assertEquals("0 B", Formatos.bytes(-5))
        assertEquals("1 B", Formatos.bytes(1))
        assertEquals("1023 B", Formatos.bytes(1023))
        assertEquals("1.0 KB", Formatos.bytes(1024))
        assertEquals("1.5 KB", Formatos.bytes(1536))
        // 1048575 / 1024 = 1023,999…: `toFixed(1)` dá 1024.0, ainda em KB.
        assertEquals("1024.0 KB", Formatos.bytes(1_048_575))
        assertEquals("1.00 MB", Formatos.bytes(1_048_576))
        assertEquals("2.50 MB", Formatos.bytes(2_621_440))
    }

    @Test
    fun `eventDate no formato brasileiro e o texto cru quando nao e data`() {
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        assertEquals("28/09/2026, 07:30:05", Formatos.dataDoEvento("2026-09-28T10:30:05.000Z", saoPaulo))
        assertEquals("ontem", Formatos.dataDoEvento("ontem", saoPaulo))
        assertEquals("", Formatos.dataDoEvento("", saoPaulo))
    }

    @Test
    fun `dolar com as casas do toFixed`() {
        assertEquals("5.00", Formatos.usd(BigDecimal("5"), 2))
        assertEquals("0.0124", Formatos.usd(BigDecimal("0.01235000"), 4))
        assertEquals("0.00", Formatos.usd(BigDecimal.ZERO, 2))
    }

    @Test
    fun `duracao em horas e minutos`() {
        assertEquals("0 min", Formatos.duracao(Duration.ZERO))
        assertEquals("45 min", Formatos.duracao(Duration.ofMinutes(45)))
        assertEquals("6 h", Formatos.duracao(Duration.ofHours(6)))
        assertEquals("5 h 30 min", Formatos.duracao(Duration.ofMinutes(330).plusSeconds(59)))
    }
}
