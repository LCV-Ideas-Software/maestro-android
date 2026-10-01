package dev.lcv.maestro.ui

import android.database.sqlite.SQLiteDiskIOException
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import dev.lcv.maestro.Sincronia
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.CancelamentoDaSessao
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import java.math.BigDecimal
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A tela da sessão sobre o Room real: o que ela mostra (métricas, custo ao
 * vivo, eventos, autos e as cinco abas) e o que ela faz (cancelar na ordem
 * certa; retomar com autenticação antes e, numa pausa por custo, com teto
 * novo acima do teto e do gasto).
 */
@RunWith(AndroidJUnit4::class)
class SessaoScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

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
        // A auditoria de links é da sessão, no portão: a aba leva à tela dela em vez de uma lista sempre vazia.
        naAba("No aparelho, os links são auditados por sessão").assertExists()
        regra.onNodeWithTag(Marcas.ABRIR_LINKS_DOS_AUTOS).assertExists()
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

    @Test
    fun oBancoCheioAoCancelarEAvisadoSemDerrubarOAplicativo() {
        // Decisão 25 do operador (29/09/2026): o banco cheio numa ação da tela é a falha da ação.
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.CANCELAR)
        c.bancoCheio.cheio = true
        regra.onNodeWithTag(Marcas.CANCELAR).performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.canceladas.isEmpty())
    }

    @Test
    fun oBancoCheioAoRetomarEAvisadoSemDerrubarOAplicativo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = "5", custo = "5")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        c.bancoCheio.cheio = true
        confirmarRetomada("7")
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        val linha = c.sessoes.carregar(id)!!
        assertEquals(Estados.LIMITE_DE_CUSTO, linha.status)
        assertEquals(Dinheiro.paraE8(BigDecimal("5")), linha.tetoDeCustoE8)
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    // Revisão antes do push da rodada 10 na #78 (decisão 25 do operador).

    @Test
    fun oDiscoQueFalhaAoAbrirARetomadaEAvisado() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = "5", custo = "5")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        c.bancoCheio.leituraQuebrada = "configuracoes"
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        regra.esperarTexto("Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        c.bancoCheio.leituraQuebrada = null
        regra.onNodeWithTag(Marcas.CONFIRMAR_RETOMADA).assertDoesNotExist()
    }

    @Test
    fun aReleituraQueFalhaDepoisDeCancelarNaoDeixaOCancelamentoPelaMetade() {
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.CANCELAR)
        // A primeira leitura depois da escrita é a do fechamento da execução, na mesma transação; a que falha é a releitura.
        c.bancoCheio.releiturasAntes = 1
        c.bancoCheio.releituraQuebrada = "sessoes"
        regra.onNodeWithTag(Marcas.CANCELAR).performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        // Nada ficou gravado e o trabalho segue: o aviso diz a verdade.
        assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.canceladas.isEmpty())
    }

    @Test
    fun aReleituraQueFalhaDepoisDeRetomarNaoDeixaASessaoNaFilaSemTrabalho() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = "5", custo = "5")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        c.bancoCheio.releituraQuebrada = "sessoes"
        confirmarRetomada("7")
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        val linha = c.sessoes.carregar(id)!!
        assertEquals(Estados.LIMITE_DE_CUSTO, linha.status)
        assertEquals(Dinheiro.paraE8(BigDecimal("5")), linha.tetoDeCustoE8)
        assertTrue(c.agendador.enfileiradas.isEmpty())
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

    @Test
    fun retomadaRecusadaNaoDeixaOTetoElevado() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, teto = "5", custo = "5")
        val jornal = c.banco.eventos().daSessao(id).map { it.mensagem }
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.RETOMAR)
        regra.onNodeWithTag(Marcas.RETOMAR).performClick()
        esperarTag(Marcas.NOVO_TETO)
        // A chave do Codex some entre o diálogo e a confirmação: a retomada é recusada e o teto
        // novo vai junto com ela, na mesma transação (achado do Codex na #72).
        c.cofre.presentes[Provedor.CODEX] = false
        confirmarRetomada("7")
        regra.esperarTexto("Agentes indisponiveis para retomada: Codex.")
        val linha = c.sessoes.carregar(id)!!
        assertEquals(Estados.LIMITE_DE_CUSTO, linha.status)
        assertEquals(Dinheiro.paraE8(BigDecimal("5")), linha.tetoDeCustoE8)
        assertEquals(jornal, c.banco.eventos().daSessao(id).map { it.mensagem })
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    @Test
    fun umArtefatoNovoApareceNosAutosSemMudancaNaSessao() {
        val id = c.sessao(Estados.RODANDO)
        val primeiro = c.artefato(id, 1, Provedor.CLAUDE, "Primeira versão do texto.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.artefato(primeiro))
        // Só a tabela de artefatos muda: os autos a observam, e não o progresso da sessão,
        // que por isso não relê os corpos a cada custo ou evento (achado do Codex na #72).
        val segundo = c.artefato(id, 2, Provedor.CODEX, "Segunda versão do texto.", anterior = primeiro)
        esperarTag(Marcas.artefato(segundo))
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasText("Segunda versão do texto.", substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA)))
                .fetchSemanticsNodes().isNotEmpty()
        }
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
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
    }

    // Decisão 25 do operador, estendida em 30/09/2026 (#80): as leituras de abrir e voltar à tela e a observação.
    // "Aviso e segue": o aviso com o motivo, a tela fica com o que mostrava (ou o motivo no lugar, se nunca leu), e a
    // volta da tela ao primeiro plano lê de novo.

    private val aviso = "Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
    private val noLugar = "Não foi possível ler os dados desta tela; ela tenta de novo quando você voltar a ela. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"

    /** A tela ao segundo plano e de volta: STARTED mantém a coleta viva, e só o ON_RESUME relê. */
    private fun voltarATela() {
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun esperarNaMarca(marca: String, texto: String) {
        regra.waitUntil(5_000) { regra.onAllNodes(hasTestTag(marca) and hasText(texto)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Espera o aviso passageiro sair da tela: a fila do Snackbar mostra um de cada vez. */
    private fun esperarAvisoSair(texto: String) {
        regra.waitUntil(15_000) { regra.onAllNodesWithText(texto, substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aSessaoQueNaoSeLeAoAbrirMostraOMotivoNoLugarESoVoltaNaVoltaDaTela() {
        val id = c.sessao(Estados.RODANDO)
        c.bancoCheio.leituraQuebrada = "FROM sessoes WHERE id"
        regra.abrir(c, sessaoPedida = id)
        esperarNaMarca(Marcas.LEITURA_FALHOU, noLugar)
        regra.esperarTexto(aviso)
        regra.onNodeWithText("Sessão não encontrada.").assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        // Sem a volta da tela, a sessão não é relida: nada de laço.
        Thread.sleep(1_000)
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertDoesNotExist()
        voltarATela()
        esperarTag(Marcas.METRICA_SESSAO)
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
    }

    @Test
    fun aSessaoQueCaiDepoisDeLidaFicaComoEstavaEVoltaNaVoltaDaTela() {
        val id = c.sessao(Estados.RODANDO)
        c.evento(id, "running", "Evento de antes", Provedor.CODEX)
        regra.abrir(c, sessaoPedida = id)
        regra.esperarTexto("Evento de antes", substring = true)
        c.bancoCheio.leituraQuebrada = "FROM eventos WHERE sessaoId"
        c.evento(id, "running", "Evento de depois", Provedor.CODEX)
        regra.esperarTexto(aviso)
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Em execução")
        regra.onNodeWithText("Evento de depois", substring = true).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.esperarTexto("Evento de depois", substring = true)
    }

    @Test
    fun osAutosQueNaoSeLeemAoAbrirMostramOMotivoNoLugarEORestoDaSessaoSegue() {
        val id = c.sessao(Estados.RODANDO)
        c.artefato(id, 1, Provedor.CLAUDE, "Primeira versão do texto.")
        c.bancoCheio.leituraQuebrada = "bytesDoConteudo"
        regra.abrir(c, sessaoPedida = id)
        esperarNaMarca(Marcas.FALHA_DOS_AUTOS, noLugar)
        regra.esperarTexto(aviso)
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Em execução")
        regra.onNodeWithText("Os artefatos aparecerão aqui", substring = true).assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasText("Primeira versão do texto.", substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        regra.onNodeWithTag(Marcas.FALHA_DOS_AUTOS).assertDoesNotExist()
    }

    @Test
    fun oArtefatoQueNaoSeLeMostraOMotivoNoLugarEOToqueAvisaDeNovo() {
        val id = c.sessao(Estados.RODANDO)
        val primeiro = c.artefato(id, 1, Provedor.CLAUDE, "Primeira versão do texto.")
        c.artefato(id, 2, Provedor.CODEX, "Segunda versão do texto.", anterior = primeiro)
        c.bancoCheio.leituraQuebrada = "AND id = ?"
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.artefato(primeiro))
        esperarNaMarca(Marcas.FALHA_DOS_AUTOS, noLugar)
        regra.esperarTexto(aviso)
        esperarAvisoSair(aviso)
        // Um toque é um pedido novo: o aviso sai de novo na mesma volta da tela.
        regra.onNodeWithTag(Marcas.artefato(primeiro)).performScrollTo().performClick()
        regra.esperarTexto(aviso)
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasText("Primeira versão do texto.", substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        regra.onNodeWithTag(Marcas.FALHA_DOS_AUTOS).assertDoesNotExist()
    }

    @Test
    fun oArtefatoJaMostradoFicaQuandoOutroNaoSeLe() {
        val id = c.sessao(Estados.RODANDO)
        val primeiro = c.artefato(id, 1, Provedor.CLAUDE, "Primeira versão do texto.")
        c.artefato(id, 2, Provedor.CODEX, "Segunda versão do texto.", anterior = primeiro)
        regra.abrir(c, sessaoPedida = id)
        regra.waitUntil(5_000) { regra.onAllNodes(hasText("Segunda versão do texto.", substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA))).fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.leituraQuebrada = "AND id = ?"
        regra.onNodeWithTag(Marcas.artefato(primeiro)).performScrollTo().performClick()
        regra.esperarTexto(aviso)
        naAba("Segunda versão do texto.").assertExists()
        regra.onNodeWithTag(Marcas.FALHA_DOS_AUTOS).assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.waitUntil(5_000) { regra.onAllNodes(hasText("Primeira versão do texto.", substring = true) and hasAnyAncestor(hasTestTag(Marcas.CONTEUDO_DA_ABA))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aParadaQueNaoSeLeNoWorkManagerFicaComORotuloEAvisaComACausa() {
        c.agendador.parada = WorkInfo.STOP_REASON_TIMEOUT
        val id = c.sessao(Estados.ERRO, erro = "A execução parou.", teto = "5", custo = "1")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.PARADA_PELO_SISTEMA)
        // O `get()` do futuro do WorkManager embrulha o erro do banco dele: o aviso dá a causa.
        c.agendador.falhaAoConsultar = ExecutionException(SQLiteDiskIOException(BancoCheio.MENSAGEM_DE_DISCO))
        voltarATela()
        regra.esperarTexto(aviso)
        // A parada que não se lê não congela o resto da sessão: o custo segue ao vivo.
        c.sessoes.somarCusto(id, BigDecimal("0.5"))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("US$ 1.50 / teto US$ 5.00").fetchSemanticsNodes().isNotEmpty() }
        c.agendador.falhaAoConsultar = null
        regra.onNodeWithTag(Marcas.PARADA_PELO_SISTEMA).assertTextEquals("Última parada registrada pelo sistema: tempo limite do servico dataSync")
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertExists()
    }

    @Test
    fun osAgentesProntosQueNaoSeLeemNaVoltaSaoAvisados() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.CANCELAR)
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        voltarATela()
        regra.esperarTexto(aviso)
        c.bancoCheio.leituraQuebrada = null
        regra.onNodeWithTag(Marcas.CANCELAR).assertExists()
    }

    @Test
    fun umDiscoQueDerrubaVariasLeiturasDaUmAvisoPorVoltaDaTela() {
        c.configurar()
        c.agendador.parada = WorkInfo.STOP_REASON_TIMEOUT
        val id = c.sessao(Estados.ERRO, erro = "A execução parou.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.PARADA_PELO_SISTEMA)
        // A volta relê os prontos (configurações) e a parada (WorkManager): as duas falham, e sai um aviso só.
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        c.agendador.falhaAoConsultar = ExecutionException(SQLiteDiskIOException(BancoCheio.MENSAGEM_DE_DISCO))
        voltarATela()
        regra.esperarTexto(aviso)
        esperarAvisoSair(aviso)
        val segundo = runCatching { regra.waitUntil(2_000) { regra.onAllNodesWithText(aviso).fetchSemanticsNodes().isNotEmpty() } }
        assertTrue("a mesma volta deu um segundo aviso", segundo.isFailure)
        // Na volta seguinte, as leituras falham de novo, e sai um aviso novo.
        voltarATela()
        regra.esperarTexto(aviso)
        c.bancoCheio.leituraQuebrada = null
        c.agendador.falhaAoConsultar = null
    }

    @Test
    fun oCancelamentoPelaNotificacaoQueNaoGravaAvisaEDeixaOTrabalhoSeguir() {
        val id = c.sessao(Estados.RODANDO)
        val motivos = mutableListOf<String>()
        c.bancoCheio.cheio = true
        CancelamentoDaSessao.cancelar(id, c.sessoes, c.agendador) { motivos += it }
        c.bancoCheio.cheio = false
        assertEquals(listOf(BancoCheio.MENSAGEM), motivos)
        assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.canceladas.isEmpty())
        // O controle: gravado o cancelamento, o trabalho é cancelado, e não há aviso.
        CancelamentoDaSessao.cancelar(id, c.sessoes, c.agendador) { motivos += it }
        assertEquals(listOf(BancoCheio.MENSAGEM), motivos)
        assertEquals(listOf(id to Estados.CANCELADA), c.agendador.canceladas.toList())
    }

    @Test
    fun aParadaDeUmaExecucaoAnteriorNaoVoltaQuandoADoNovoErroNaoSeLe() {
        c.agendador.parada = WorkInfo.STOP_REASON_TIMEOUT
        val id = c.sessao(Estados.ERRO, erro = "A execução parou.")
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.PARADA_PELO_SISTEMA)
        // Retomada, a sessão sai do erro; depois cai de novo, e a parada no WorkManager não se lê.
        c.banco.sessoes().mudarStatus(id, listOf(Estados.ERRO), Estados.RODANDO, null, c.agora(), null)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.PARADA_PELO_SISTEMA).fetchSemanticsNodes().isEmpty() }
        c.agendador.falhaAoConsultar = ExecutionException(SQLiteDiskIOException(BancoCheio.MENSAGEM_DE_DISCO))
        c.banco.sessoes().mudarStatus(id, listOf(Estados.RODANDO), Estados.ERRO, "Outra parada.", c.agora(), null)
        regra.esperarTexto(aviso)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("Outra parada.").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.PARADA_PELO_SISTEMA).assertDoesNotExist()
        c.agendador.falhaAoConsultar = null
    }
}
