/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.sessao.FormatoDeInstante
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `formatBytes` e `eventDate` do web (`MaestroAiModule.tsx:227-247`) e os valores em dólar das telas. */
object Formatos {

    private val KIB = BigDecimal(1024)
    private val MIB = BigDecimal(1024 * 1024)

    /**
     * `formatBytes`: `0 B` para zero ou menos, bytes inteiros abaixo de 1 KiB,
     * uma casa em KB, duas em MB. A divisão por potência de dois é exata, e o
     * arredondamento é o do `toFixed` sobre esse valor exato.
     */
    fun bytes(valor: Long): String = when {
        valor <= 0 -> "0 B"
        valor < 1024 -> "$valor B"
        valor < 1024 * 1024 -> "${BigDecimal(valor).divide(KIB).setScale(1, RoundingMode.HALF_UP).toPlainString()} KB"
        else -> "${BigDecimal(valor).divide(MIB).setScale(2, RoundingMode.HALF_UP).toPlainString()} MB"
    }

    /** `toLocaleString('pt-BR')`: `dd/MM/aaaa, HH:mm:ss` no fuso do aparelho. */
    private val DATA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu, HH:mm:ss")

    /** `eventDate`: a data no formato brasileiro, ou o próprio texto quando ele não é um instante. */
    fun dataDoEvento(valor: String, zona: ZoneId = ZoneId.systemDefault()): String =
        FormatoDeInstante.ler(valor)?.let { DATA.format(it.atZone(zona)) } ?: valor

    /** `valor.toFixed(casas)`: dólar com casas fixas e ponto decimal, como o web mostra. */
    fun usd(valor: BigDecimal, casas: Int): String = valor.setScale(casas, RoundingMode.HALF_UP).toPlainString()

    /** Uma duração em horas e minutos (`5 h 30 min`, `45 min`, `6 h`), arredondada para baixo no minuto. */
    fun duracao(valor: Duration): String {
        val minutos = valor.toMinutes().coerceAtLeast(0)
        val horas = minutos / 60
        val resto = minutos % 60
        return when {
            horas == 0L -> "$resto min"
            resto == 0L -> "$horas h"
            else -> "$horas h $resto min"
        }
    }
}
