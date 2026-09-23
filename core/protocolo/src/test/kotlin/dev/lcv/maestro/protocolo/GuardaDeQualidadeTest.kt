package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guarda de qualidade. Os dois primeiros casos são os do canônico
 * (`session_orchestration.rs` 3437–3464 em `3c8babc`).
 */
class GuardaDeQualidadeTest {

    @Test
    fun `mudanca so de espaco nao e substantiva`() {
        assertFalse(GuardaDeQualidade.mudancaSubstantiva("Linha 1\r\n\r\nLinha    2", " Linha 1\nLinha 2 "))
        assertTrue(GuardaDeQualidade.mudancaSubstantiva("Linha 1.", "Linha 1"))
    }

    @Test
    fun `revisor de nivel menor nao encolhe texto mais forte`() {
        val antes = "Paragrafo amplo e reflexivo. ".repeat(30)
        val depois = "Resumo curto."

        assertTrue(GuardaDeQualidade.bloqueiaRevisao("codex", "grok", antes, depois, true))
        assertFalse(GuardaDeQualidade.bloqueiaRevisao("grok", "codex", antes, depois, true))
    }

    @Test
    fun `NBSP e NEL sao espaco para a normalizacao`() {
        // Com a API da plataforma, NEL não seria espaço e a mudança pareceria
        // substantiva.
        assertFalse(GuardaDeQualidade.mudancaSubstantiva("Linha\u0085um dois", "Linha um dois"))
    }

    @Test
    fun `limites do guarda`() {
        val antes = "a".repeat(400)

        // 340 é exatamente 85%: não é menos que 85%.
        assertFalse(GuardaDeQualidade.bloqueiaRevisao("codex", "grok", antes, "b".repeat(340), true))
        assertTrue(GuardaDeQualidade.bloqueiaRevisao("codex", "grok", antes, "b".repeat(339), true))
        // Texto curto demais para o guarda valer.
        assertFalse(GuardaDeQualidade.bloqueiaRevisao("codex", "grok", "a".repeat(399), "b", true))
        // Sem autor conhecido, ou sem mudança substantiva, não bloqueia.
        assertFalse(GuardaDeQualidade.bloqueiaRevisao(null, "grok", antes, "b", true))
        assertFalse(GuardaDeQualidade.bloqueiaRevisao("codex", "grok", antes, "b", false))
        // Mesmo nível não bloqueia; nome desconhecido é o nível mais baixo.
        assertFalse(GuardaDeQualidade.bloqueiaRevisao("claude", "codex", antes, "b", true))
        assertTrue(GuardaDeQualidade.bloqueiaRevisao("GEMINI", "outro", antes, "b", true))
    }
}
