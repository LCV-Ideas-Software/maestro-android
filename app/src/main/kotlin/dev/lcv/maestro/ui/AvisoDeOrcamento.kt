/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import java.time.Duration

/**
 * Quando o formulário de nova sessão avisa sobre o orçamento de seis horas
 * por 24 horas do `dataSync` (especificação, seção 4.1, item 3): quando o que
 * resta na conta das execuções deste aplicativo é menor que o limite de tempo
 * da sessão, ou menor que [SEM_LIMITE] quando a sessão não tem limite. É aviso,
 * não recusa, e a conta é a do próprio aplicativo, não a cota que a
 * plataforma guarda (revisão cruzada de 28/09/2026, emendas A12 e C4).
 */
object AvisoDeOrcamento {

    /** O limiar de uma sessão sem limite de tempo. */
    val SEM_LIMITE: Duration = Duration.ofMinutes(60)

    fun mostrar(restante: Duration, tetoDeMinutos: Int?): Boolean =
        restante < (tetoDeMinutos?.let { Duration.ofMinutes(it.toLong()) } ?: SEM_LIMITE)
}
