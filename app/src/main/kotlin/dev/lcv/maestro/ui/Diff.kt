/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.sessao.TrimJs

/**
 * `simpleDiff` do web (`MaestroAiModule.tsx:288-305`), linha a linha e sem
 * alinhamento: compara a linha `i` de cada lado, guarda a igual quando não é
 * vazia e, quando difere, a removida e a acrescentada, cada uma só se não for
 * vazia; no máximo 220 linhas. É o que a aba **Diff** mostra — uma comparação
 * posicional, não um diff mínimo, e o porte não a "melhora".
 */
object Diff {

    enum class Tipo { IGUAL, ACRESCENTADA, REMOVIDA }

    data class Linha(val tipo: Tipo, val texto: String)

    const val MAXIMO: Int = 220

    private val QUEBRA = Regex("\r?\n")

    fun simples(anterior: String, atual: String): List<Linha> {
        val antes = anterior.split(QUEBRA)
        val depois = atual.split(QUEBRA)
        val linhas = ArrayList<Linha>()
        for (indice in 0 until maxOf(antes.size, depois.size)) {
            val esquerda = antes.getOrElse(indice) { "" }
            val direita = depois.getOrElse(indice) { "" }
            if (esquerda == direita) {
                if (naoVazia(direita)) linhas += Linha(Tipo.IGUAL, direita)
                continue
            }
            if (naoVazia(esquerda)) linhas += Linha(Tipo.REMOVIDA, esquerda)
            if (naoVazia(direita)) linhas += Linha(Tipo.ACRESCENTADA, direita)
        }
        return linhas.take(MAXIMO)
    }

    /** O texto da aba: `+ `, `- ` ou dois espaços antes de cada linha; vazio quando não há linha. */
    fun texto(linhas: List<Linha>): String = linhas.joinToString("\n") { linha ->
        when (linha.tipo) {
            Tipo.ACRESCENTADA -> "+ "
            Tipo.REMOVIDA -> "- "
            Tipo.IGUAL -> "  "
        } + linha.texto
    }

    /** `String.prototype.trim` do web decide o que é linha vazia. */
    private fun naoVazia(linha: String): Boolean = TrimJs.aparar(linha).isNotEmpty()
}
