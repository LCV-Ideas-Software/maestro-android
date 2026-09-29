package dev.lcv.maestro.ui

import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.DecisaoDeRevisao
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.StatusDaRevisao
import dev.lcv.maestro.sessao.Estados
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `statusLabel`, `agentLabel`, `isRunning`, `isResumable` e a pílula da aba Links, contra o web. */
class RotulosTest {

    @Test
    fun `os dezoito rotulos do web e o de autenticacao do android`() {
        val esperados = mapOf(
            "queued" to "Na fila",
            "running" to "Em execução",
            "converged" to "Concluída",
            "paused_cost_limit" to "Pausada por custo",
            "paused_time_limit" to "Pausada por tempo",
            "blocked_max_cycles" to "Sem unanimidade",
            "paused_cycle_limit" to "Limite de turnos atingido",
            "paused_round_incomplete" to "Rodada incompleta",
            "paused_self_review" to "Auto-revisão bloqueada",
            "paused_final_audit" to "Pausada na auditoria final",
            "paused_reviewer_outage" to "Pausada por falha de revisor",
            "paused_draft_unavailable" to "Pausada sem rascunho inicial",
            "paused_resume_state_invalid" to "Pausada por integridade da retomada",
            "blocked_revision_contract" to "Bloqueada por contrato",
            "blocked_link_audit" to "Bloqueada por link inválido",
            "blocked_cancelled" to "Cancelada",
            "error" to "Erro",
            "pausada_aguardando_autenticacao" to "Pausada aguardando autenticação",
        )
        assertEquals(esperados, Rotulos.STATUS)
        esperados.forEach { (status, rotulo) -> assertEquals(rotulo, Rotulos.status(status)) }
    }

    @Test
    fun `todo estado que o nucleo grava tem rotulo e o desconhecido volta como veio`() {
        (Estados.RETOMAVEIS + Estados.ATIVOS + Estados.CONVERGIDA).forEach { assertTrue(it in Rotulos.STATUS, it) }
        assertEquals("paused_futuro", Rotulos.status("paused_futuro"))
    }

    @Test
    fun `agentLabel devolve o nome o proprio valor ou Maestro AI`() {
        assertEquals("DeepSeek", Rotulos.agente("deepseek"))
        assertEquals("anthropic", Rotulos.agente("anthropic"))
        assertEquals("Maestro AI", Rotulos.agente(null))
        assertEquals("Maestro AI", Rotulos.agente(""))
    }

    @Test
    fun `em execucao so na fila ou rodando`() {
        assertTrue(Rotulos.emExecucao("queued"))
        assertTrue(Rotulos.emExecucao("running"))
        assertFalse(Rotulos.emExecucao("paused_cost_limit"))
        assertFalse(Rotulos.emExecucao(null))
    }

    @Test
    fun `retomavel inclui a pausa de autenticacao e exclui o texto final e a convergencia`() {
        assertTrue(Rotulos.retomavel(Estados.AGUARDANDO_AUTENTICACAO, null))
        assertTrue(Rotulos.retomavel("error", null))
        assertFalse(Rotulos.retomavel("paused_cost_limit", "texto final"))
        assertFalse(Rotulos.retomavel("converged", null))
        assertFalse(Rotulos.retomavel("running", null))
    }

    private fun link() = LinhaDeLink(
        versaoDoEsquema = "1",
        linkId = "link-1",
        artefatoDeOrigem = "artifact-1",
        impressaoDaOrigem = "f",
        textoDaAncora = null,
        textoAoRedor = "",
        urlOriginal = "https://exemplo.org/a",
        urlNormalizada = "https://exemplo.org/a",
        mudancasDaNormalizacao = emptyList(),
        urlFinal = null,
        cadeiaDeRedirecionamento = emptyList(),
        statusHttp = 200,
        tipoDeConteudo = null,
        sha256 = null,
        verificadoEm = "2026-09-28T10:00:00.000Z",
        sustentaAfirmacao = null,
        classificacao = ClassificacaoDoLink.VERIFICADO_MAS_FRACO,
        classificacaoMecanica = ClassificacaoDoLink.VERIFICADO_MAS_FRACO,
        candidatosDeCorrecao = emptyList(),
        statusDaRevisao = StatusDaRevisao.PENDENTE,
        decisaoDeRevisao = null,
        revisadoPor = null,
        notaDaRevisao = null,
        revisadoEm = null,
        evidenciaWebId = null,
        url = "https://exemplo.org/a",
        status = "",
        invalidade = "",
        tom = "warn",
    )

    @Test
    fun `o suporte a afirmacao segue o claimSupportLabel do desktop`() {
        assertEquals("Ainda não julgado editorialmente", Rotulos.suporte(link()))
        assertEquals(
            "Suporte à afirmação aceito explicitamente",
            Rotulos.suporte(link().copy(sustentaAfirmacao = true, decisaoDeRevisao = DecisaoDeRevisao.ACEITAR)),
        )
        assertEquals("Sinal armazenado sem aceite editorial concluído", Rotulos.suporte(link().copy(sustentaAfirmacao = true)))
        assertEquals(
            "Não sustenta a afirmação",
            Rotulos.suporte(link().copy(sustentaAfirmacao = false, decisaoDeRevisao = DecisaoDeRevisao.ACEITAR)),
        )
    }
}
