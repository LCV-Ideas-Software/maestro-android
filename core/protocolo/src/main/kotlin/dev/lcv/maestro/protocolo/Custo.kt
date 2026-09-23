package dev.lcv.maestro.protocolo

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Custo de uma chamada a provedor, estimado antes e observado depois, e a
 * regra do teto da sessão.
 *
 * **A fonte é o web** (`sessions.ts`, `estimateCost`, `calculateObservedCost`
 * e `observedCostSource`, linhas 1044–1078 em 23/09/2026), porque é ele que
 * tem as três taxas da seção 7 da especificação. O desktop
 * (`session_controls.rs`) não tem a taxa por requisição da Perplexity.
 *
 * **A regra é a da seção 7.1**, na forma decidida pelo operador em 23/09/2026:
 *
 * - toda conta é `BigDecimal`, exata até o arredondamento final;
 * - estimativa **e** custo observado vão para a escala interna de 8 casas
 *   arredondando **para cima** (`CEILING`), e é nessa escala que se soma e se
 *   compara — somar em 2 casas faria uma chamada de US$ 0,004 valer zero, e o
 *   teto nunca dispararia;
 * - `acumulado + estimativa <= teto`: igualdade **permite** a chamada;
 * - 2 casas com `HALF_UP` servem só para exibir ([paraExibir]).
 *
 * **Afastamentos declarados do web.**
 *
 * - Tokens estimados são `ceil(length / 4)` sobre unidades UTF-16, como no
 *   web. O Rust conta pontos de código; UTF-16 conta mais num emoji, o que
 *   erra para o lado seguro.
 * - Taxa de entrada ou de saída ausente, zero ou negativa **fecha**: a
 *   estimativa não existe e a chamada não é admitida ([Admissao.SemTaxas]).
 *   O web devolve `NaN` nesse caso; aqui é um estado com nome.
 * - A origem do custo segue a seção 7.1, não o `observedCostSource` do web:
 *   contagem de tokens devolvida pelo provedor conta como observado. O web só
 *   chama de "provider" o custo em dólar informado pelo provedor, e rotularia
 *   de estimado um custo calculado com tokens reais.
 */
public object Custo {

    public const val ESCALA_INTERNA: Int = 8
    public const val ESCALA_DE_EXIBICAO: Int = 2

    /**
     * Taxas de um provedor, em dólares: por milhão de tokens de entrada, por
     * milhão de saída e, na Perplexity, por mil requisições.
     */
    public data class Taxas(
        val entradaPorMilhao: BigDecimal?,
        val saidaPorMilhao: BigDecimal?,
        val requisicoesPorMil: BigDecimal? = null,
    )

    /** De onde veio o custo observado, para o jornal dizer a verdade. */
    public enum class Fonte { PROVEDOR, ESTIMATIVA }

    public data class Observado(val valor: BigDecimal, val fonte: Fonte)

    /** Resultado de [admitir]. */
    public sealed interface Admissao {
        public data object Permitida : Admissao

        /** Sem taxa de entrada ou de saída válida: não há estimativa possível. */
        public data object SemTaxas : Admissao

        /** A chamada não cabe; os três valores vão ao jornal. */
        public data class AcimaDoTeto(
            val acumulado: BigDecimal,
            val estimativa: BigDecimal,
            val teto: BigDecimal,
        ) : Admissao
    }

    /** `Math.ceil(texto.length / 4)`. */
    public fun tokensEstimados(texto: String): Long = (texto.length.toLong() + 3) / 4

    /**
     * `estimateCost`: custo da próxima chamada, supondo a saída inteira. `null`
     * quando falta taxa de entrada ou de saída.
     */
    public fun estimar(prompt: String, maxTokensDeSaida: Long, taxas: Taxas): BigDecimal? {
        val entrada = taxaValida(taxas.entradaPorMilhao) ?: return null
        val saida = taxaValida(taxas.saidaPorMilhao) ?: return null
        return calcular(tokensEstimados(prompt), maxTokensDeSaida, entrada, saida, taxas)
    }

    /**
     * `calculateObservedCost`. Custo em dólar informado pelo provedor vale
     * como está, se não for negativo. Senão, cada contagem de tokens que o
     * provedor não devolveu é estimada pelo tamanho do texto. `null` quando
     * falta taxa e o provedor não informou custo.
     */
    public fun observar(
        taxas: Taxas,
        promptEnviado: String,
        textoRecebido: String,
        tokensDeEntrada: Long?,
        tokensDeSaida: Long?,
        custoInformadoUsd: BigDecimal? = null,
    ): Observado? {
        if (custoInformadoUsd != null && custoInformadoUsd.signum() >= 0) {
            return Observado(paraEscalaInterna(custoInformadoUsd), Fonte.PROVEDOR)
        }
        val entrada = taxaValida(taxas.entradaPorMilhao) ?: return null
        val saida = taxaValida(taxas.saidaPorMilhao) ?: return null
        val valor = calcular(
            tokensDeEntrada ?: tokensEstimados(promptEnviado),
            tokensDeSaida ?: tokensEstimados(textoRecebido),
            entrada,
            saida,
            taxas,
        )
        val fonte = if (tokensDeEntrada != null && tokensDeSaida != null) {
            Fonte.PROVEDOR
        } else {
            Fonte.ESTIMATIVA
        }
        return Observado(valor, fonte)
    }

    /** A regra do teto da seção 7.1: `acumulado + estimativa <= teto`. */
    public fun admitir(acumulado: BigDecimal, estimativa: BigDecimal?, teto: BigDecimal): Admissao {
        if (estimativa == null) return Admissao.SemTaxas
        return if (acumulado.add(estimativa) <= teto) {
            Admissao.Permitida
        } else {
            Admissao.AcimaDoTeto(acumulado, estimativa, teto)
        }
    }

    /** Duas casas, a meio para cima. Nunca alimenta soma nem comparação. */
    public fun paraExibir(valor: BigDecimal): BigDecimal =
        valor.setScale(ESCALA_DE_EXIBICAO, RoundingMode.HALF_UP)

    private fun calcular(
        tokensDeEntrada: Long,
        tokensDeSaida: Long,
        entrada: BigDecimal,
        saida: BigDecimal,
        taxas: Taxas,
    ): BigDecimal {
        // Dividir por potência de dez é mover a vírgula: exato, sem escolher
        // arredondamento antes da hora.
        val porRequisicao = taxaValida(taxas.requisicoesPorMil)?.movePointLeft(3) ?: BigDecimal.ZERO
        val exato = BigDecimal.valueOf(tokensDeEntrada).multiply(entrada).movePointLeft(6)
            .add(BigDecimal.valueOf(tokensDeSaida).multiply(saida).movePointLeft(6))
            .add(porRequisicao)
        return paraEscalaInterna(exato)
    }

    private fun paraEscalaInterna(valor: BigDecimal): BigDecimal =
        valor.setScale(ESCALA_INTERNA, RoundingMode.CEILING)

    private fun taxaValida(taxa: BigDecimal?): BigDecimal? = taxa?.takeIf { it.signum() > 0 }
}
