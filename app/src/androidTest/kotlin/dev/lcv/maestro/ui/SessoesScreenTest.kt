package dev.lcv.maestro.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.Sincronia
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.ExecucaoEntidade
import dev.lcv.maestro.sessao.FormatoDeInstante
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tela inicial sobre o Room real (especificação, seção 8): as validações do
 * web, o caminho permissão → autenticação → gravação → enfileiramento, a lista
 * observada, as chaves relidas na volta à tela e o aviso do orçamento.
 */
@RunWith(AndroidJUnit4::class)
class SessoesScreenTest {

    // A regra com a Activity à mão: um teste leva a tela ao segundo plano e a traz de volta.
    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    @Before
    fun semPermissaoDeNotificacoes() {
        // O caminho da razão só é provável com a permissão negada, que é o estado de uma instalação nova.
        assertNotEquals(
            PackageManager.PERMISSION_GRANTED,
            c.contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
    }


    private fun iniciar(pedido: String = "Um artigo sobre revisão por pares.") {
        regra.onNodeWithTag(Marcas.CAMPO_PEDIDO).performScrollTo().performTextInput(pedido)
        regra.onNodeWithTag(Marcas.INICIAR).performScrollTo().performClick()
    }

    @Test
    fun comMenosDeDoisAgentesProntosAMensagemDoWebENadaComeca() {
        c.configurar()
        c.chaves(Provedor.CLAUDE)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("1 / 6").fetchSemanticsNodes().isNotEmpty() }
        iniciar()
        regra.esperarTexto("Configure pelo menos dois agentes antes de iniciar.")
        assertEquals(0, c.autenticacoes.get())
        assertTrue(c.sessoes.listar().isEmpty())
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    @Test
    fun pedidoVazioRecusaAntesDeTudo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.onNodeWithTag(Marcas.INICIAR).performScrollTo().performClick()
        regra.esperarTexto("Descreva o trabalho editorial antes de iniciar.")
        assertEquals(0, c.autenticacoes.get())
    }

    @Test
    fun colegiadoDeUmSoRecusa() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("3 / 6").fetchSemanticsNodes().isNotEmpty() }
        // Tirar o redator inicial (Claude) do colegiado o devolve à frente, como no web.
        regra.onNodeWithTag(Marcas.colegiado(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.colegiado(Provedor.CODEX)).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.colegiado(Provedor.GEMINI)).performScrollTo().performClick()
        iniciar()
        regra.esperarTexto("Selecione pelo menos dois agentes prontos para o colegiado.")
        assertTrue(c.sessoes.listar().isEmpty())
    }

    @Test
    fun redatorForaDoColegiadoRecusa() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("3 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.colegiado(Provedor.GEMINI)).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.redator(Provedor.GEMINI)).performScrollTo().performClick()
        iniciar()
        regra.esperarTexto("O redator inicial deve participar do colegiado selecionado.")
        assertTrue(c.sessoes.listar().isEmpty())
    }

    @Test
    fun comDoisProntosPedeARazaoAutenticaGravaEnfileiraEAbreASessao() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        iniciar()
        regra.onNodeWithTag(Marcas.RAZAO_DAS_NOTIFICACOES).assertExists()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        assertEquals(1, c.autenticacoes.get())
        val linha = c.sessoes.listar().single()
        assertEquals(Estados.NA_FILA, linha.status)
        assertEquals(listOf(linha.id), c.agendador.enfileiradas.toList())
        // A tela da sessão abriu, e o aviso do web aparece nela.
        regra.esperarTexto("Sessão Maestro AI iniciada.")
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.METRICA_CUSTO).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun semAutenticacaoNadaEGravado() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        c.autentica = false
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.esperarTexto("Sem a sua autenticação, a sessão não usa as chaves guardadas; nada foi iniciado.")
        assertEquals(1, c.autenticacoes.get())
        assertTrue(c.sessoes.listar().isEmpty())
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    @Test
    fun aListaEObservadaEAsChavesSaoRelidasNaVoltaATela() {
        c.configurar()
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("0 / 6").fetchSemanticsNodes().isNotEmpty() }
        // Uma sessão gravada fora da tela aparece sem recarregar nada.
        val id = c.sessao(Estados.LIMITE_DE_CUSTO, titulo = "Gravada por fora")
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.sessao(id)).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Pausada por custo")
        // Uma chave guardada enquanto a pessoa estava em Configurações conta na volta.
        c.chaves(Provedor.GEMINI, Provedor.GROK)
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun umSegundoToqueComAAutenticacaoAbertaNaoPedeDeNovo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val portao = CompletableDeferred<Boolean>()
        c.portao = portao
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.waitUntil(5_000) { c.autenticacoes.get() == 1 }
        regra.onNodeWithTag(Marcas.INICIAR).performScrollTo().performClick()
        regra.waitForIdle()
        assertEquals(1, c.autenticacoes.get())
        portao.complete(true)
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        assertEquals(1, c.sessoes.listar().size)
        regra.onAllNodesWithText("Sem a sua autenticação", substring = true).assertCountEquals(0)
    }

    @Test
    fun asChavesSaoRelidasQuandoATelaVoltaAoPrimeiroPlano() {
        c.configurar()
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("0 / 6").fetchSemanticsNodes().isNotEmpty() }
        // A chave foi guardada com a tela parada (nos ajustes do sistema, por exemplo): o
        // ViewModel e a composição sobrevivem, e só o ON_RESUME faz a releitura (emenda A4).
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun iniciarEsperaAReconciliacaoQueEstaEmCurso() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        // A trava da reconciliação da abertura está tomada: a sessão não pode ser gravada
        // nem enfileirada até ela ser solta (emenda A11).
        runBlocking { Sincronia.reconciliacao.lock() }
        try {
            iniciar()
            regra.onNodeWithText("Seguir sem notificações").performClick()
            regra.waitUntil(5_000) { c.autenticacoes.get() == 1 }
            Thread.sleep(1_000)
            assertTrue(c.sessoes.listar().isEmpty())
            assertTrue(c.agendador.enfileiradas.isEmpty())
        } finally {
            Sincronia.reconciliacao.unlock()
        }
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        assertEquals(1, c.sessoes.listar().size)
    }

    private fun execucao(horas: Long, minutos: Long) {
        val agora = Instant.parse("2026-09-28T12:00:00Z")
        c.banco.execucoes().inserir(
            ExecucaoEntidade(
                sessaoId = c.sessao(Estados.ERRO),
                inicio = FormatoDeInstante.iso(agora.minus(Duration.ofHours(horas).plusMinutes(minutos))),
                fim = FormatoDeInstante.iso(agora),
            ),
        )
    }

    @Test
    fun oAvisoDoOrcamentoApareceComCincoHorasEMeiaGastas() {
        c.configurar()
        execucao(horas = 5, minutos = 30)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.AVISO_DE_ORCAMENTO).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun oAvisoDoOrcamentoNaoApareceComUmaHoraGasta() {
        c.configurar()
        execucao(horas = 1, minutos = 0)
        regra.abrir(c)
        // O teto gravado (US$ 5.00, não o US$ 0.00 de antes da carga) chega na mesma leitura que o orçamento.
        regra.waitUntil(5_000) { regra.onAllNodesWithText("US$ 5.00").fetchSemanticsNodes().isNotEmpty() }
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.AVISO_DE_ORCAMENTO).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.redator(Provedor.CLAUDE)).assertIsNotEnabled()
    }
}
