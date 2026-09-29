package dev.lcv.maestro.ui

import dev.lcv.maestro.ui.Diff.Linha
import dev.lcv.maestro.ui.Diff.Tipo
import kotlin.test.Test
import kotlin.test.assertEquals

/** `simpleDiff` do web: posicional, sem linhas vazias, no máximo 220 linhas. */
class DiffTest {

    @Test
    fun `igual fica, diferente vira removida e acrescentada, vazia some`() {
        val linhas = Diff.simples("a\n\nb\nc", "a\n\nB\n")
        assertEquals(
            listOf(Linha(Tipo.IGUAL, "a"), Linha(Tipo.REMOVIDA, "b"), Linha(Tipo.ACRESCENTADA, "B"), Linha(Tipo.REMOVIDA, "c")),
            linhas,
        )
    }

    @Test
    fun `sem artefato anterior tudo e acrescentado e crlf quebra como lf`() {
        assertEquals(listOf(Linha(Tipo.ACRESCENTADA, "x"), Linha(Tipo.ACRESCENTADA, "y")), Diff.simples("", "x\r\ny"))
    }

    @Test
    fun `espaco do javascript conta como linha vazia`() {
        // U+FEFF e U+00A0 são espaço para o `trim` do JavaScript.
        assertEquals(emptyList(), Diff.simples("﻿", "  "))
    }

    @Test
    fun `no maximo 220 linhas`() {
        val atual = (1..300).joinToString("\n") { "linha $it" }
        val linhas = Diff.simples("", atual)
        assertEquals(Diff.MAXIMO, linhas.size)
        assertEquals("linha 220", linhas.last().texto)
    }

    @Test
    fun `o texto da aba leva os prefixos do web e fica vazio sem linhas`() {
        val texto = Diff.texto(listOf(Linha(Tipo.IGUAL, "a"), Linha(Tipo.REMOVIDA, "b"), Linha(Tipo.ACRESCENTADA, "c")))
        assertEquals("  a\n- b\n+ c", texto)
        assertEquals("", Diff.texto(emptyList()))
    }
}
