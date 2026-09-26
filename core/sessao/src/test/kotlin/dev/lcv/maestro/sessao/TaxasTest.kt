package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `sanitizeRates` e `hasPositiveRates`, regra a regra. */
class TaxasTest {

    private fun bd(valor: String) = BigDecimal(valor)

    @Test
    fun `valor positivo abaixo do padrao sobe para o padrao e acima fica`() {
        val saneadas = Taxas.sanear(mapOf(Provedor.CLAUDE to Custo.Taxas(bd("1"), bd("80"))))
        assertEquals(bd("10"), saneadas.getValue(Provedor.CLAUDE).entradaPorMilhao)
        assertEquals(bd("80"), saneadas.getValue(Provedor.CLAUDE).saidaPorMilhao)
    }

    @Test
    fun `ausente, zero e negativo caem no padrao`() {
        val saneadas = Taxas.sanear(mapOf(Provedor.GROK to Custo.Taxas(bd("0"), bd("-1")), Provedor.DEEPSEEK to null))
        assertEquals(Taxas.PADRAO.getValue(Provedor.GROK).entradaPorMilhao, saneadas.getValue(Provedor.GROK).entradaPorMilhao)
        assertEquals(Taxas.PADRAO.getValue(Provedor.GROK).saidaPorMilhao, saneadas.getValue(Provedor.GROK).saidaPorMilhao)
        assertEquals(bd("1.32"), saneadas.getValue(Provedor.DEEPSEEK).entradaPorMilhao)
        assertEquals(Provedor.entries.toSet(), saneadas.keys)
    }

    @Test
    fun `taxa por mil requisicoes fica se positiva, senao o padrao`() {
        val saneadas = Taxas.sanear(mapOf(Provedor.PERPLEXITY to Custo.Taxas(bd("1"), bd("5"), bd("0")), Provedor.CLAUDE to Custo.Taxas(bd("20"), bd("60"), bd("3"))))
        assertEquals(bd("14"), saneadas.getValue(Provedor.PERPLEXITY).requisicoesPorMil)
        assertEquals(bd("3"), saneadas.getValue(Provedor.CLAUDE).requisicoesPorMil)
        assertEquals(BigDecimal.ZERO, saneadas.getValue(Provedor.GEMINI).requisicoesPorMil)
    }

    @Test
    fun `positivas exige entrada e saida maiores que zero`() {
        assertTrue(Taxas.positivas(Custo.Taxas(bd("0.01"), bd("0.01"))))
        assertFalse(Taxas.positivas(Custo.Taxas(bd("0"), bd("1"))))
        assertFalse(Taxas.positivas(Custo.Taxas(bd("1"), null)))
        assertFalse(Taxas.positivas(null))
    }

    @Test
    fun `json gravado volta igual e o que nao parseia vira o padrao`() {
        val json = Taxas.paraJson(Taxas.PADRAO)
        assertTrue("\"claude\":{\"input_usd_per_million\":10,\"output_usd_per_million\":50,\"request_usd_per_1k\":0}" in json)
        assertEquals(Taxas.PADRAO, Taxas.lerJson(json))
        assertEquals(Taxas.PADRAO, Taxas.lerJson("lixo"))
        assertEquals(Taxas.PADRAO, Taxas.lerJson(null))
    }

    @Test
    fun `leitura bruta aceita numero em texto e nao saneia`() {
        val bruto = Taxas.lerBruto("{\"codex\":{\"input_usd_per_million\":\"0.5\",\"output_usd_per_million\":\"abc\"}}")
        assertEquals(bd("0.5"), bruto.getValue(Provedor.CODEX)!!.entradaPorMilhao)
        assertNull(bruto.getValue(Provedor.CODEX)!!.saidaPorMilhao)
        assertNull(bruto[Provedor.CLAUDE])
        assertFalse(Taxas.positivas(bruto[Provedor.CODEX]))
    }
}
