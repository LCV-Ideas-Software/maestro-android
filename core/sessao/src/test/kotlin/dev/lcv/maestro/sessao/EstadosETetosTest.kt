package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Os conjuntos de estado, o teto sobre a sessão inteira e o formato de instante. */
class EstadosETetosTest {

    @Test
    fun `os treze estados do web mais o de autenticacao sao retomaveis`() {
        assertEquals(14, Estados.RETOMAVEIS.size)
        assertTrue(Estados.ERRO in Estados.RETOMAVEIS)
        assertTrue(Estados.AGUARDANDO_AUTENTICACAO in Estados.RETOMAVEIS)
        assertTrue(Estados.CANCELADA in Estados.RETOMAVEIS)
        assertEquals(setOf("queued", "running"), Estados.ATIVOS)
        assertTrue(Estados.ATIVOS.none { it in Estados.RETOMAVEIS })
        assertEquals("Sessao pausada aguardando autenticacao do usuario.", Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO)
    }

    @Test
    fun `o teto vale sobre o acumulado da sessao inteira, no limite exato e um centavo acima`() {
        val teto = BigDecimal("5.00")
        val acumuladoDaSessao = BigDecimal("4.90")
        assertIs<Custo.Admissao.Permitida>(Custo.admitir(acumuladoDaSessao, BigDecimal("0.10"), teto))
        assertIs<Custo.Admissao.AcimaDoTeto>(Custo.admitir(acumuladoDaSessao, BigDecimal("0.11"), teto))
        // Sem linha de base por execução: o que já foi gasto antes da retomada conta.
        assertIs<Custo.Admissao.AcimaDoTeto>(Custo.admitir(acumuladoDaSessao, BigDecimal("4.00"), teto))
    }

    @Test
    fun `dinheiro nas colunas e inteiro de dez a menos oito, arredondado para cima`() {
        assertEquals(500_000_000L, Dinheiro.paraE8(BigDecimal("5")))
        assertEquals(1L, Dinheiro.paraE8(BigDecimal("0.000000001")))
        assertEquals(BigDecimal("0.00012345"), Dinheiro.deE8(12345L))
        assertEquals(BigDecimal("1.32000000"), Dinheiro.deE8(Dinheiro.paraE8(BigDecimal("1.32"))))
    }

    @Test
    fun `instante sai como toISOString e le o que gravou`() {
        assertEquals("2026-09-25T12:00:00.000Z", FormatoDeInstante.iso(Instant.parse("2026-09-25T12:00:00Z")))
        assertEquals("2026-09-25T12:00:00.500Z", FormatoDeInstante.iso(Fixtures.AGORA))
        assertEquals(Fixtures.AGORA, FormatoDeInstante.ler(Fixtures.ISO_AGORA))
        assertNull(FormatoDeInstante.ler("ontem"))
        assertNull(FormatoDeInstante.ler(""))
    }
}
