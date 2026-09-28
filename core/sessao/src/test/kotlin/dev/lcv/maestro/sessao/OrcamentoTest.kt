package dev.lcv.maestro.sessao

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** O orçamento agregado de seis horas por 24 horas do `dataSync` (seção 4.1), sobre as linhas de `execucoes`. */
class OrcamentoTest {

    private val agora: Instant = Instant.parse("2026-09-27T12:00:00Z")

    private fun execucao(inicio: Instant, fim: Instant?) = ExecucaoEntidade(
        sessaoId = "android-1",
        inicio = FormatoDeInstante.iso(inicio),
        fim = fim?.let(FormatoDeInstante::iso),
    )

    @Test
    fun `sem execucoes restam as seis horas`() {
        assertEquals(Duration.ofHours(6), Orcamento.restanteNaJanela(emptyList(), agora))
    }

    @Test
    fun `uma execucao inteira na janela desconta o que durou`() {
        val execucoes = listOf(execucao(agora.minus(Duration.ofHours(3)), agora.minus(Duration.ofHours(1))))
        assertEquals(Duration.ofHours(4), Orcamento.restanteNaJanela(execucoes, agora))
    }

    @Test
    fun `so a parte dentro da janela conta`() {
        // Começou 30 h atrás e terminou 22 h atrás: só as 2 h dentro da janela de 24 h contam.
        val execucoes = listOf(execucao(agora.minus(Duration.ofHours(30)), agora.minus(Duration.ofHours(22))))
        assertEquals(Duration.ofHours(4), Orcamento.restanteNaJanela(execucoes, agora))
    }

    @Test
    fun `execucao aberta conta ate agora`() {
        val execucoes = listOf(execucao(agora.minus(Duration.ofMinutes(90)), null))
        assertEquals(Duration.ofMinutes(270), Orcamento.restanteNaJanela(execucoes, agora))
    }

    @Test
    fun `gasto acima do teto e zero, nunca negativo`() {
        val execucoes = listOf(execucao(agora.minus(Duration.ofHours(7)), agora))
        assertEquals(Duration.ZERO, Orcamento.restanteNaJanela(execucoes, agora))
    }

    @Test
    fun `data ilegivel conta como zero`() {
        val execucoes = listOf(
            ExecucaoEntidade(sessaoId = "android-1", inicio = "ontem", fim = null),
            ExecucaoEntidade(sessaoId = "android-1", inicio = FormatoDeInstante.iso(agora.minus(Duration.ofHours(1))), fim = "logo"),
        )
        assertEquals(Duration.ofHours(6), Orcamento.restanteNaJanela(execucoes, agora))
    }
}
