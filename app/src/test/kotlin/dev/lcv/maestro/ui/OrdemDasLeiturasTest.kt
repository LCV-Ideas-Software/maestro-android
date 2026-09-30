package dev.lcv.maestro.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A ordem das releituras das telas de anexos e de links (achado do Codex na #78). */
class OrdemDasLeiturasTest {

    @Test
    fun `uma releitura anterior que termina depois de uma mais nova ja aplicada e descartada`() {
        val ordem = OrdemDasLeituras()
        val antiga = ordem.comecar()
        val nova = ordem.comecar()
        assertTrue(ordem.aplicar(nova))
        assertFalse(ordem.aplicar(antiga))
    }

    @Test
    fun `uma releitura mais nova que falha nao descarta a anterior que deu certo`() {
        val ordem = OrdemDasLeituras()
        val antiga = ordem.comecar()
        ordem.comecar() // a mais nova falha: nunca chega a aplicar
        assertTrue(ordem.aplicar(antiga))
    }

    @Test
    fun `releituras em sequencia sao todas aplicadas e nenhuma duas vezes`() {
        val ordem = OrdemDasLeituras()
        val primeira = ordem.comecar()
        assertTrue(ordem.aplicar(primeira))
        assertFalse(ordem.aplicar(primeira))
        assertTrue(ordem.aplicar(ordem.comecar()))
    }
}
