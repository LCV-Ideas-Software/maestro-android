package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Os casos do web (`sessions.test.ts:1588-1640`, "stable-approval convergence
 * and reviewer selection"), mais os quatro motivos do redesenho, que o web não
 * testa e o jornal grava.
 */
class EscalonamentoTest {

    private val escala = listOf("codex", "deepseek", "claude")

    @Test
    fun `convergencia exige todo agente que nao e o autor no conjunto estavel`() {
        assertFalse(Escalonamento.temTodasAsAprovacoesIndependentes(escala, "claude", emptySet()))
        assertFalse(Escalonamento.temTodasAsAprovacoesIndependentes(escala, "claude", setOf("codex")))
        assertTrue(Escalonamento.temTodasAsAprovacoesIndependentes(escala, "claude", setOf("codex", "deepseek")))
        // A presença do próprio autor é irrelevante.
        assertTrue(Escalonamento.temTodasAsAprovacoesIndependentes(escala, "claude", setOf("codex", "deepseek", "claude")))
        // Sem autor ainda (rascunho não produzido), nunca convergiu.
        assertFalse(Escalonamento.temTodasAsAprovacoesIndependentes(escala, null, setOf("codex", "deepseek", "claude")))
    }

    @Test
    fun `fechamento so abre depois do circuito completo dos pares`() {
        assertFalse(Escalonamento.fechamentoTemRevisoesPrevias(escala, "claude", emptySet(), null))
        assertFalse(Escalonamento.fechamentoTemRevisoesPrevias(escala, "claude", setOf("codex"), null))
        assertTrue(Escalonamento.fechamentoTemRevisoesPrevias(escala, "claude", setOf("codex", "deepseek"), null))
        // Uma escala só com o líder não tem revisores exigidos: nunca está pronta.
        assertFalse(Escalonamento.fechamentoTemRevisoesPrevias(listOf("claude"), "claude", emptySet(), null))
    }

    @Test
    fun `escolha prefere a vaga nominal elegivel e redesenha nas outras`() {
        // Nominal elegível: não é o autor, não aprovou, não é o líder travado.
        assertEquals(0, Escalonamento.escolherRevisor(escala, 0, "claude", "claude", emptySet(), emptySet(), 0))
        // Nominal é o autor atual: redesenho entre os pendentes (só o deepseek:
        // o claude é o líder e o fechamento não está pronto).
        assertEquals(1, Escalonamento.escolherRevisor(escala, 0, "codex", "claude", emptySet(), emptySet(), 0))
        assertEquals(1, Escalonamento.escolherRevisor(escala, 0, "codex", "claude", emptySet(), emptySet(), 7))
        // Nominal já aprovou de forma estável: o redesenho o pula.
        assertEquals(1, Escalonamento.escolherRevisor(escala, 0, "claude", "claude", emptySet(), setOf("codex"), 0))
        // O líder vira escalável quando todos os outros são agentes válidos da rodada.
        assertEquals(2, Escalonamento.escolherRevisor(escala, 2, "codex", "claude", setOf("codex", "deepseek"), setOf("deepseek"), 0))
        // Ninguém pendente: null (quem chama trata a versão como convergida).
        assertNull(Escalonamento.escolherRevisor(escala, 0, "claude", "claude", emptySet(), setOf("codex", "deepseek"), 0))
        // O redesenho usa semente % pendentes sobre a lista de índices pendentes.
        assertEquals(1, Escalonamento.escolherRevisor(escala, 2, "claude", "claude", emptySet(), emptySet(), 1))
        // Escala vazia: null, sem dividir por zero.
        assertNull(Escalonamento.escolherRevisor(emptyList<String>(), 0, "claude", "claude", emptySet(), emptySet(), 0))
    }

    @Test
    fun `semente grande e negativa nunca sai da lista de pendentes`() {
        // Só o codex e o deepseek estão pendentes (o líder espera o circuito).
        for (semente in listOf(Long.MAX_VALUE, Long.MIN_VALUE, -1L, 4_294_967_295L)) {
            val escolhido = Escalonamento.escolherRevisor(escala, 2, "claude", "claude", emptySet(), emptySet(), semente)
            assertTrue(escolhido == 0 || escolhido == 1, "semente $semente escolheu $escolhido")
        }
    }

    @Test
    fun `motivo do redesenho segue a ordem do web`() {
        assertEquals(
            Escalonamento.NOMINAL_E_O_AUTOR,
            Escalonamento.motivoDoRedesenho(escala, 0, "codex", "claude", emptySet(), emptySet()),
        )
        assertEquals(
            Escalonamento.FECHAMENTO_ESPERA_O_CIRCUITO,
            Escalonamento.motivoDoRedesenho(escala, 2, "codex", "claude", emptySet(), emptySet()),
        )
        assertEquals(
            Escalonamento.NOMINAL_JA_APROVOU,
            Escalonamento.motivoDoRedesenho(escala, 0, "claude", "claude", emptySet(), setOf("codex")),
        )
        // O líder com o circuito completo e sem aprovação estável é "inelegível" (o web não distingue mais).
        assertEquals(
            Escalonamento.NOMINAL_INELEGIVEL,
            Escalonamento.motivoDoRedesenho(escala, 2, "codex", "claude", setOf("codex", "deepseek"), emptySet()),
        )
    }
}
