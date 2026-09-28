package dev.lcv.maestro.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import dev.lcv.maestro.Sincronia
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tela da sessão sobre o Room real: o que ela mostra (métricas, custo ao
 * vivo, eventos, autos e as cinco abas) e o que ela faz (cancelar na ordem
 * certa; retomar com autenticação antes e, numa pausa por custo, com teto
 * novo acima do teto e do gasto).
 */
@RunWith(AndroidJUnit4::class)
class SessaoScreenTest {

    val regra = createComposeRule()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private fun naAba(texto: String): SemanticsNodeInteraction =
        regra.onNode(hasText(texto, substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA)))

    private fun esperarTag(marca: String) {
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(marca).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun mostraMetricasOsUltimosOitoEventosOsAutosEAsCincoAbas() {
        val id = c.sessao(Estados.RODANDO, teto = "5", custo = "1.2345")
        (1..10).forEach { c.evento(id, "running", "Evento $it", Provedor.CODEX, custo = "0.0001") }
        val primeiro = c.artefato(id, 1, Provedor.CLAUDE, "Primeira versão do texto.")
        c.artefato(id, 2, Provedor.CODEX, "Segunda versão do texto.", anterior = primeiro)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.METRICA_CUSTO)

        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Em execução")
        regra.onNodeWithTag(Marcas.METRICA_COM_O_TRABALHO).assertTextEquals("Codex")
        regra.onNodeWithTag(Marcas.METRICA_CUSTO).assertTextEquals("US$ 1.23 / teto US$ 5.00")
        regra.onNodeWithTag(Marcas.CANCELAR).assertExists()
        regra.onNodeWithTag(Marcas.RETOMAR).assertDoesNotExist()

        // `events.slice(-8)`: dos dez, ficam do terceiro ao décimo; os dois primeiros só ao expandir.
        regra.onNodeWithText("Evento 10", substring = true).assertExists()
        regra.onNodeWithText("Evento 3 ", substring = true).assertExists()
        regra.onNodeWithText("Evento 2 ", substring = true).assertDoesNotExist()
        regra.onNodeWithText("Evento 1 ", substring = true).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.MOSTRAR_EVENTOS).performScrollTo().performClick()
        regra.onNodeWithText("Evento 1 ", substring = true).assertExists()
        regra.onNodeWithText("Evento 2 ", substring = true).assertExists()

        // O último artefato vem escolhido, e cada aba mostra a sua projeção.
        naAba("Segunda versão do texto.").assertExists()
        regra.onNodeWithTag(Marcas.aba("diff")).performScrollTo().performClick()
        naAba("- Primeira versão do texto.").assertExists()
        naAba("+ Segunda versão do texto.").assertExists()
        regra.onNodeWithTag(Marcas.aba("relatorio")).performScrollTo().performClick()
        naAba("{\"decision\":\"READY\"}").assertExists()
        regra.onNodeWithTag(Marcas.aba("links")).performScrollTo().performClick()
        naAba("Nenhum link encontrado neste artefato.").assertExists()
        regra.onNodeWithTag(Marcas.aba("metadados")).performScrollTo().performClick()
        naAba("\"turn\": 2").assertExists()
        naAba("\"cost_usd\": 0.0123").assertExists()

        // Escolher o primeiro artefato troca a aba para a projeção dele.
        regra.onNodeWithTag(Marcas.artefato(primeiro)).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodes(hasText("\"turn\": 1", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun oCustoAcumuladoAcompanhaOBancoSemNovoEvento() {
        val id = c.sessao(Estados.RODANDO, teto = "5", custo = "1")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.METRICA_CUSTO)
        regra.onNodeWithTag(Marcas.METRICA_CUSTO).assertTextEquals("US$ 1.00 / teto US$ 5.00")
        c.sessoes.somarCusto(id, BigDecimal("0.5"))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("US$ 1.50 / teto US$ 5.00").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun cancelarGravaOCancelamentoAntesDeCancelarOTrabalho() {
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.CANCELAR)
        regra.onNodeWithTag(Marcas.CANCELAR).performClick()
        regra.esperarTexto("Sessão cancelada.")
        assertEquals(Estados.CANCELADA, c.sessoes.carregar(id)?.status)
        assertEquals(listOf(id to Estados.CANCELADA), c.agendador.canceladas.toList())
    }

    private fun confirmarRetomada(teto: String? = null) {
        if (teto != null) {
            regra.onNodeWithTag(Marcas.NOVO_TETO).performTextClearance()
            regra.onNodeWithTag(Marcas.NOVO_TETO).performTextInput(teto)
        }
        regra.onNodeWithTag(Marcas.CONFIRMAR_RETOMADA).performClick()
    }

    @Test
    fun retomarPorCustoExigeTetoAcimaDoTetoEDoGastoEAutenticaAntes() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = "5", custo = "5")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)

        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        regra.onNodeWithTag(Marcas.NOVO_TETO).assertTextEquals("6")
        confirmarRetomada("4")
        regra.esperarTexto(RepositorioDeSessoes.MENSAGEM_TETO_NAO_SOBE)
        assertEquals(1, c.autenticacoes.get())
        assertEquals(Estados.LIMITE_DE_CUSTO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.enfileiradas.isEmpty())

        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        confirmarRetomada("7")
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        assertEquals(2, c.autenticacoes.get())
        val linha = c.sessoes.carregar(id)!!
        assertEquals(Estados.NA_FILA, linha.status)
        assertEquals(Dinheiro.paraE8(BigDecimal("7")), linha.tetoDeCustoE8)
        assertEquals(listOf(id), c.agendador.enfileiradas.toList())
        regra.esperarTexto("Sessão retomada.")
    }

    private fun tetoSugerido(teto: String, custo: String): String {
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = teto, custo = custo)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        return regra.onNodeWithTag(Marcas.NOVO_TETO).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }

    @Test
    fun oTetoSugeridoPassaDoGastoQuandoOGastoEOMaior() {
        assertEquals("8", tetoSugerido(teto = "5", custo = "7.3"))
    }

    @Test
    fun oTetoSugeridoPassaDoTetoQuandoOTetoEOMaior() {
        assertEquals("10", tetoSugerido(teto = "9", custo = "5"))
    }

    @Test
    fun retomarEsperaAReconciliacaoQueEstaEmCurso() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.CONFIRMAR_RETOMADA)
        // Emenda A11: com a trava da reconciliação tomada, nada é pedido nem enfileirado.
        runBlocking { Sincronia.reconciliacao.lock() }
        try {
            confirmarRetomada()
            regra.waitUntil(5_000) { c.autenticacoes.get() == 1 }
            Thread.sleep(1_000)
            assertEquals(Estados.ERRO, c.sessoes.carregar(id)?.status)
            assertTrue(c.agendador.enfileiradas.isEmpty())
        } finally {
            Sincronia.reconciliacao.unlock()
        }
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        assertEquals(Estados.NA_FILA, c.sessoes.carregar(id)?.status)
    }

    @Test
    fun desmarcarOLiderODevolveAoColegiado() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.", agentes = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI))
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.CONFIRMAR_RETOMADA)
        regra.onNodeWithTag(Marcas.painel(Provedor.CLAUDE)).performClick()
        regra.onNodeWithTag(Marcas.painel(Provedor.CLAUDE)).assertIsOn()
        regra.onNodeWithTag(Marcas.painel(Provedor.CODEX)).performClick()
        regra.onNodeWithTag(Marcas.painel(Provedor.CODEX)).assertIsOff()
        confirmarRetomada()
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
    }

    @Test
    fun semAutenticacaoAPausaDeAutenticacaoNaoERetomada() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        c.autentica = false
        val id = c.sessao(Estados.AGUARDANDO_AUTENTICACAO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Pausada aguardando autenticação")
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.CONFIRMAR_RETOMADA)
        regra.onNodeWithTag(Marcas.NOVO_TETO).assertDoesNotExist()
        confirmarRetomada()
        regra.esperarTexto("Sem a sua autenticação, a sessão não usa as chaves guardadas; nada foi retomado.")
        assertEquals(1, c.autenticacoes.get())
        assertEquals(Estados.AGUARDANDO_AUTENTICACAO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    @Test
    fun retomarComUmSoAgenteProntoTemAMensagemDoWebESemAutenticacao() {
        c.configurar()
        c.chaves(Provedor.CLAUDE)
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        // Um erro comum não tem o aviso da chamada paga sem resultado (decisão 16).
        regra.onNodeWithTag(Marcas.ERRO_OPERACIONAL).assertExists()
        regra.onNodeWithTag(Marcas.CHAMADA_INDETERMINADA).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.CONFIRMAR_RETOMADA)
        confirmarRetomada()
        regra.esperarTexto("Selecione pelo menos dois agentes prontos para retomar o colegiado.")
        assertEquals(0, c.autenticacoes.get())
    }

    @Test
    fun oErroMostraAParadaDoSistemaEOAvisoDaChamadaIndeterminada() {
        c.agendador.parada = WorkInfo.STOP_REASON_TIMEOUT
        val id = c.sessao(Estados.ERRO, erro = RepositorioDeSessoes.mensagemDeChamadaIndeterminada(Provedor.CODEX))
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.ERRO_OPERACIONAL)
        regra.onNodeWithTag(Marcas.PARADA_PELO_SISTEMA).assertTextEquals("Última parada registrada pelo sistema: tempo limite do servico dataSync")
        regra.onNodeWithTag(Marcas.CHAMADA_INDETERMINADA).assertExists()
    }

    @Test
    fun aSessaoConvergidaMostraOTextoFinalSemAcoes() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = "O texto final liberado.", textoAtual = "O rascunho anterior.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.TEXTO_DA_SESSAO)
        regra.onNodeWithTag(Marcas.TEXTO_DA_SESSAO).assertTextEquals("O texto final liberado.")
        regra.onNodeWithText("Texto final").assertExists()
        regra.onNodeWithTag(Marcas.RETOMAR).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.CANCELAR).assertDoesNotExist()
    }

    @Test
    fun sessaoInexistenteDizQueNaoFoiEncontrada() {
        regra.abrir(c, sessaoPedida = "android-inexistente")
        regra.esperarTexto("Sessão não encontrada.")
    }
}
