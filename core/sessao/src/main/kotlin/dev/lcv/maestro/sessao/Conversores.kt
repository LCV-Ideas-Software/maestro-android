package dev.lcv.maestro.sessao

import androidx.room.TypeConverter
import java.math.BigDecimal

/**
 * Dinheiro no banco é texto, nunca `REAL`: o web guarda `observed_cost_usd`
 * e `max_cost_usd` como `REAL` (`ensureSchema`, `sessions.ts:466-467`), e a
 * seção 7 da especificação manda `BigDecimal` de ponta a ponta. A comparação
 * que decide o teto é feita em Kotlin, sobre o valor lido, e nunca em SQL
 * sobre o texto.
 */
public class Conversores {

    @TypeConverter
    public fun deDecimal(valor: BigDecimal?): String? = valor?.toPlainString()

    @TypeConverter
    public fun paraDecimal(texto: String?): BigDecimal? = texto?.let(::BigDecimal)
}
