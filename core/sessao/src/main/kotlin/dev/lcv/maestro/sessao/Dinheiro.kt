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

    /** O maior valor que a coluna representa: `Long.MAX_VALUE` em 10⁻⁸ USD. Acima disso a entrada é recusada, não convertida. */
    public val MAXIMO: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE).movePointLeft(Custo.ESCALA_INTERNA)

    public fun cabe(valor: BigDecimal): Boolean = valor <= MAXIMO && valor >= MAXIMO.negate()

    /** Para a coluna: arredonda para cima, como a soma do protocolo, para nunca subestimar um gasto. Só para valores que [cabe] já admitiu. */
    public fun paraE8(valor: BigDecimal): Long = valor.multiply(FATOR).setScale(0, RoundingMode.CEILING).longValueExact()

    /**
     * Para a coluna, um valor **observado** de fora (custo devolvido por um
     * provedor): o que não cabe satura no extremo da coluna em vez de lançar,
     * porque esse caminho corre depois da chamada paga e um piso saturado
     * pausa a sessão pelo teto, enquanto uma exceção perderia o registro do
     * gasto. O que o operador digita continua sendo recusado por [cabe].
     */
    public fun paraE8Observado(valor: BigDecimal): Long = when {
        cabe(valor) -> paraE8(valor)
        valor.signum() > 0 -> Long.MAX_VALUE
        else -> Long.MIN_VALUE
    }

    /** Da coluna: exato, na escala interna. */
    public fun deE8(valor: Long): BigDecimal = BigDecimal.valueOf(valor).movePointLeft(Custo.ESCALA_INTERNA)
}
