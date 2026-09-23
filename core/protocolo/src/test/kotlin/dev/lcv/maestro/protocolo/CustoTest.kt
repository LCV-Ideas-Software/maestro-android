package dev.lcv.maestro.protocolo

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A regra do teto da seção 7.1. Cada caso fixa uma escolha que uma
 * implementação diferente faria diferente sem que nada mais caísse.
 */
class CustoTest {

    private fun bd(valor: String) = BigDecimal(valor)

    private val taxas = Custo.Taxas(entradaPorMilhao = bd("3"), saidaPorMilhao = bd("15"))

    @Test
    fun `estimativa soma entrada pelo tamanho do prompt e a saida inteira`() {
        // 400 unidades UTF-16 → 100 tokens; 100 × 3 / 1e6 + 1000 × 15 / 1e6.
        assertEquals(bd("0.01530000"), Custo.estimar("a".repeat(400), 1_000, taxas))
    }

    @Test
    fun `tokens estimados arredondam para cima`() {
        assertEquals(1L, Custo.tokensEstimados("a"))
        assertEquals(2L, Custo.tokensEstimados("a".repeat(5)))
        // Um emoji são duas unidades UTF-16, como no `length` do web.
        assertEquals(1L, Custo.tokensEstimados("😀"))
        assertEquals(0L, Custo.tokensEstimados(""))
    }

    @Test
    fun `estimativa arredonda para cima na escala 8`() {
        // 1 token × 0,000000001 / 1e6 é muito menor que a oitava casa.
        val minusculo = Custo.Taxas(bd("0.000000001"), bd("0.000000001"))

        assertEquals(bd("0.00000001"), Custo.estimar("a", 0, minusculo))
    }

    @Test
    fun `taxa por requisicao entra por mil`() {
        val perplexity = Custo.Taxas(bd("1"), bd("1"), requisicoesPorMil = bd("14"))

        // 0 + 0 + 14 / 1000.
        assertEquals(bd("0.01400000"), Custo.estimar("", 0, perplexity))
    }

    @Test
    fun `sem taxa de entrada ou de saida nao ha estimativa e a chamada nao e admitida`() {
        assertNull(Custo.estimar("x", 10, Custo.Taxas(null, bd("1"))))
        assertNull(Custo.estimar("x", 10, Custo.Taxas(bd("1"), BigDecimal.ZERO)))
        assertNull(Custo.estimar("x", 10, Custo.Taxas(bd("-1"), bd("1"))))
        assertEquals(Custo.Admissao.SemTaxas, Custo.admitir(BigDecimal.ZERO, null, bd("10")))
    }

    @Test
    fun `no limite exato a chamada acontece e o proximo passo reprova`() {
        val teto = bd("1.00000000")

        assertEquals(Custo.Admissao.Permitida, Custo.admitir(bd("0.60000000"), bd("0.40000000"), teto))
        assertEquals(
            Custo.Admissao.AcimaDoTeto(bd("0.60000000"), bd("0.40000001"), teto),
            Custo.admitir(bd("0.60000000"), bd("0.40000001"), teto),
        )
    }

    @Test
    fun `custo abaixo de meio centavo continua somando`() {
        // Somado em 2 casas, cada chamada valeria zero e o teto nunca
        // dispararia. Decisão do operador de 23/09/2026: soma em 8 casas.
        val chamada = assertNotNullObservado(
            Custo.observar(taxas, "", "", tokensDeEntrada = 0, tokensDeSaida = 267),
        ).valor
        assertEquals(bd("0.00400500"), chamada)

        var acumulado = BigDecimal.ZERO
        repeat(250) { acumulado = acumulado.add(chamada) }

        assertTrue(Custo.admitir(acumulado, chamada, bd("1.00")) is Custo.Admissao.AcimaDoTeto)
        assertEquals(bd("0.00"), Custo.paraExibir(chamada))
    }

    @Test
    fun `exibicao arredonda a meio para cima em duas casas`() {
        assertEquals(bd("0.01"), Custo.paraExibir(bd("0.00500000")))
        assertEquals(bd("0.00"), Custo.paraExibir(bd("0.00499999")))
    }

    @Test
    fun `tokens devolvidos pelo provedor contam como observado`() {
        val observado = assertNotNullObservado(Custo.observar(taxas, "p", "r", 100, 200))

        assertEquals(Custo.Fonte.PROVEDOR, observado.fonte)
        assertEquals(bd("0.00330000"), observado.valor)
    }

    @Test
    fun `resposta sem contagem de tokens cai para a estimativa e diz isso`() {
        val observado = assertNotNullObservado(
            Custo.observar(taxas, "a".repeat(400), "b".repeat(80), null, null),
        )

        assertEquals(Custo.Fonte.ESTIMATIVA, observado.fonte)
        // 100 tokens de entrada e 20 de saída, estimados pelo tamanho.
        assertEquals(bd("0.00060000"), observado.valor)
    }

    @Test
    fun `contagem parcial tambem e estimativa`() {
        val observado = assertNotNullObservado(Custo.observar(taxas, "p", "r", 100, null))

        assertEquals(Custo.Fonte.ESTIMATIVA, observado.fonte)
    }

    @Test
    fun `contagem negativa do provedor vale como ausente`() {
        // Somada como veio, -1 000 000 tokens de saída dariam custo negativo e
        // baixariam o acumulado da sessão.
        val observado = assertNotNullObservado(
            Custo.observar(taxas, "a".repeat(400), "b".repeat(80), 100, -1_000_000),
        )

        assertEquals(Custo.Fonte.ESTIMATIVA, observado.fonte)
        // 100 tokens de entrada do provedor; 20 de saída estimados pelo texto.
        assertEquals(bd("0.00060000"), observado.valor)
    }

    @Test
    fun `custo em dolar informado pelo provedor vale como esta`() {
        val observado = assertNotNullObservado(
            Custo.observar(Custo.Taxas(null, null), "p", "r", null, null, custoInformadoUsd = bd("0.123456781")),
        )

        assertEquals(Custo.Fonte.PROVEDOR, observado.fonte)
        // Para cima, como a estimativa: a meio arredondaria para 0,12345678.
        assertEquals(bd("0.12345679"), observado.valor)
    }

    @Test
    fun `custo informado negativo e ignorado`() {
        val observado = assertNotNullObservado(
            Custo.observar(taxas, "p", "r", 100, 200, custoInformadoUsd = bd("-1")),
        )

        assertEquals(bd("0.00330000"), observado.valor)
        assertNull(Custo.observar(Custo.Taxas(null, null), "p", "r", 1, 1, custoInformadoUsd = bd("-1")))
    }

    private fun assertNotNullObservado(observado: Custo.Observado?): Custo.Observado =
        observado ?: kotlin.test.fail("esperava custo observado")
}
