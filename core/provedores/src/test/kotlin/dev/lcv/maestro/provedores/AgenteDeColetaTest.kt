package dev.lcv.maestro.provedores

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A identidade do crawler, literal, e o que não cabe num cabeçalho. */
class AgenteDeColetaTest {

    @Test
    fun `o agente e o do canonico, com a plataforma e o repositorio deste aplicativo`() {
        val agente = AgenteDeColeta(" 1.2.3 ", null)
        assertEquals("1.2.3", agente.versao)
        assertEquals("MaestroEditorialAI/1.2.3 (Android; +https://github.com/LCV-Ideas-Software/maestro-android)", agente.userAgent)
        assertEquals(agente.userAgent, agente.userAgentPolido)
        assertEquals("maestroeditorialai", agente.nomeDoRobo)
        assertNull(agente.emailDeContato)
        assertNull(AgenteDeColeta("1.2.3", "   ").emailDeContato)
    }

    @Test
    fun `o e-mail do usuario so entra na variante polida`() {
        val polido = AgenteDeColeta("1.2.3", " leitor@example.com ")
        assertEquals("leitor@example.com", polido.emailDeContato)
        assertEquals("MaestroEditorialAI/1.2.3 (Android; +https://github.com/LCV-Ideas-Software/maestro-android)", polido.userAgent)
        assertEquals(
            "MaestroEditorialAI/1.2.3 (Android; +https://github.com/LCV-Ideas-Software/maestro-android; mailto:leitor@example.com)",
            polido.userAgentPolido,
        )
    }

    @Test
    fun `versao e e-mail que nao cabem num cabecalho sao recusados sem serem citados`() {
        for (versao in listOf("", "1.2 3", "1.2\n.3", "1é")) {
            val erro = assertFailsWith<IllegalArgumentException> { AgenteDeColeta(versao, null) }
            assertEquals("versao do aplicativo invalida", erro.message)
        }
        for (email in listOf("sem-arroba", "a b@example.com", "leitorç@example.com", "x@y\rz")) {
            val erro = assertFailsWith<IllegalArgumentException> { AgenteDeColeta("1.2.3", email) }
            assertEquals("e-mail de contato invalido", erro.message)
        }
    }
}
