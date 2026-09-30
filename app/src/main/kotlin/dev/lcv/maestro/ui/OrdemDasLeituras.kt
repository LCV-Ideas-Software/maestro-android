/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

/**
 * A ordem das releituras de uma tela, usada só na linha principal: vale o resultado da mais nova que
 * terminou bem. Uma releitura anterior que termine depois de outra já aplicada não repõe a lista velha
 * (achado do Codex na #78); uma mais nova que falha não descarta a anterior que deu certo, e a tela não
 * fica sem lista.
 */
internal class OrdemDasLeituras {
    private var comecadas = 0
    private var aplicada = 0

    /** O número da releitura que começa. */
    fun comecar(): Int = ++comecadas

    /** Se o resultado da releitura [numero] deve ser aplicado; se sim, ela passa a ser a mais nova aplicada. */
    fun aplicar(numero: Int): Boolean {
        if (numero <= aplicada) return false
        aplicada = numero
        return true
    }
}
