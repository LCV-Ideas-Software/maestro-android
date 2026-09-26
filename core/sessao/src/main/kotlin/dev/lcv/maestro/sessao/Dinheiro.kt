package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Dinheiro nas colunas: inteiros em unidades de 10⁻⁸ USD, a escala interna
 * do protocolo (`Custo.ESCALA_INTERNA`). Um inteiro compara e soma em SQL
 * sem perder casa nenhuma, e o piso de custo vira um `MAX` atômico; texto
 * compararia `"10"` menor que `"9"`. A API continua em `BigDecimal`
 * (especificação, seção 7; decisão do operador de 26/09/2026).
 */
public object Dinheiro {
    private val FATOR: BigDecimal = BigDecimal.TEN.pow(Custo.ESCALA_INTERNA)

    /** Para a coluna: arredonda para cima, como a soma do protocolo, para nunca subestimar um gasto. */
    public fun paraE8(valor: BigDecimal): Long = valor.multiply(FATOR).setScale(0, RoundingMode.CEILING).longValueExact()

    /** Da coluna: exato, na escala interna. */
    public fun deE8(valor: Long): BigDecimal = BigDecimal.valueOf(valor).movePointLeft(Custo.ESCALA_INTERNA)
}
