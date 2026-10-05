package dev.lcv.maestro.ui

import android.Manifest
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDiskIOException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.ExecucaoEntidade
import dev.lcv.maestro.sessao.FormatoDeInstante
import dev.lcv.maestro.sessao.PedidoDeConfiguracoes
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
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
    fun oManifestoDoFormularioEGravadoAntesDoEnfileiramento() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray())))
        regra.abrir(c, seletor = seletor)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertTextEquals("Manifesto lido: 1 citação e 1 fonte.")
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        val linha = c.sessoes.listar().single()
        assertEquals(listOf("citation-manifest.json"), c.anexos.daSessao(linha.id).map { it.nomeOriginal })
        // No instante do enfileiramento o anexo já estava gravado: o worker o encontra ao começar.
        assertEquals(listOf(1), c.agendador.anexosAoEnfileirar.toList())
    }

    @Test
    fun umManifestoQueASessaoNaoLeriaImpedeOInicio() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", "{}".toByteArray()))))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        iniciar()
        regra.esperarTexto("O manifesto de citações escolhido não pode ser usado; troque ou remova o arquivo.")
        assertEquals(0, c.autenticacoes.get())
        assertTrue(c.sessoes.listar().isEmpty())
        // Tirar o arquivo devolve o seletor.
        regra.onNodeWithTag(Marcas.TIRAR_MANIFESTO).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).assertExists()
    }

    /** Decisão 26 do operador (#82): o manifesto que o provedor nega é avisado com o motivo, e o formulário fica sem ele. */
    @Test
    fun oManifestoQueOProvedorNegaEAvisadoComOMotivo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(DOCUMENTO_NEGADO))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível ler o arquivo escolhido. Motivo: $ACESSO_NEGADO", substring = true)
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).assertExists()
    }

    @Test
    fun oDiscoQueFalhaAoLerAsConfiguracoesNaEscolhaDoManifestoEAvisado() {
        // Conferir o manifesto lê o protocolo das configurações, e essa leitura falha (decisão 25 do operador).
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.leituraQuebrada = "configuracoes"
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        c.bancoCheio.leituraQuebrada = null
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).assertExists()
    }

    @Test
    fun oManifestoEReconferidoContraOProtocoloQueASessaoRecebe() {
        // Achado do Codex na #78: o protocolo mudou nas configurações depois da escolha do manifesto.
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertTextEquals("Manifesto lido: 1 citação e 1 fonte.")
        val outro = "Protocolo editorial trocado depois da escolha do manifesto; ".repeat(3).trim()
        c.configuracoes.salvar(PedidoDeConfiguracoes(protocolo = outro))
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        val motivo = "O manifesto nao esta vinculado ao hash do protocolo ativo. Hash do protocolo ativo: ${FormatoDoRegistro.sha256(outro)}"
        regra.esperarTexto(motivo)
        // A recusa vem depois da autenticação, no caminho que grava: nada foi gravado nem enfileirado.
        assertEquals(1, c.autenticacoes.get())
        assertTrue(c.sessoes.listar().isEmpty())
        assertTrue(c.agendador.enfileiradas.isEmpty())
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertTextEquals(
            "Manifesto recusado: $motivo. Com ele, a sessão pausaria na auditoria final antes de qualquer chamada paga; remova ou troque o arquivo.",
        )
    }

    @Test
    fun umManifestoQueNaoPodeSerGravadoNaoDeixaSessaoNaFila() {
        // Achado do Codex na #78: a sessão ficava `queued` sem o manifesto, e a reconciliação a retomaria.
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        // Um arquivo onde a pasta dos anexos deveria estar: o manifesto não pode ser gravado.
        c.pastaDosAnexos.writeBytes(byteArrayOf(0))
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.esperarTexto("failed to write attachment: cannot create directory ${c.pastaDosAnexos.absolutePath}")
        assertTrue(c.sessoes.listar().isEmpty())
        assertTrue(c.agendador.enfileiradas.isEmpty())
    }

    @Test
    fun oManifestoEscolhidoDuranteOInicioFicaParaAProximaSessao() {
        // Achado do Codex na #78: o início que termina não pode apagar a escolha feita enquanto ele corria.
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c, seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        // A trava da reconciliação segura o início sem manifesto no meio do caminho.
        runBlocking { Sincronia.reconciliacao.lock() }
        try {
            iniciar()
            regra.onNodeWithText("Seguir sem notificações").performClick()
            regra.waitUntil(5_000) { c.autenticacoes.get() == 1 }
            regra.onNodeWithTag(Marcas.ESCOLHER_MANIFESTO).performScrollTo().performClick()
            regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            Sincronia.reconciliacao.unlock()
        }
        regra.waitUntil(5_000) { c.agendador.enfileiradas.isNotEmpty() }
        val linha = c.sessoes.listar().single()
        assertTrue(c.anexos.daSessao(linha.id).isEmpty())
        // A tela da sessão abriu; na volta ao formulário, o manifesto escolhido continua lá.
        regra.esperarTexto("Sessão Maestro AI iniciada.")
        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_DO_FORMULARIO).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.MANIFESTO_DO_FORMULARIO).assertTextEquals("Manifesto lido: 1 citação e 1 fonte.")
    }

    @Test
    fun oBancoCheioAoIniciarEAvisadoSemDerrubarOAplicativo() {
        // Decisão 25 do operador (29/09/2026): o banco cheio numa ação da tela é a falha da ação.
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.cheio = true
        iniciar()
        regra.onNodeWithText("Seguir sem notificações").performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        assertTrue(c.sessoes.listar().isEmpty())
        assertTrue(c.agendador.enfileiradas.isEmpty())
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

    // Decisão 25 do operador, estendida em 30/09/2026 (#80): as leituras de abrir e voltar à tela, a observação e a
    // reconciliação da abertura. "Aviso e segue": o aviso com o motivo, a tela fica com o que mostrava (ou o motivo no
    // lugar, se nunca leu), e a volta da tela ao primeiro plano lê de novo.

    private val aviso = "Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
    private val noLugar = "Não foi possível ler os dados desta tela; ela tenta de novo quando você voltar a ela. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"

    private fun avisoDaAbertura(motivo: String) =
        "Não foi possível concluir a conferência da abertura do aplicativo; ela se repete na próxima vez que o aplicativo voltar ao primeiro plano. Motivo: $motivo"

    /** A tela ao segundo plano e de volta: STARTED mantém a coleta viva, e só o ON_RESUME relê. */
    private fun voltarATela() {
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun esperarNaMarca(marca: String, texto: String) {
        regra.waitUntil(5_000) { regra.onAllNodes(hasTestTag(marca) and hasText(texto)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun esperarMarca(marca: String) {
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(marca).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Espera o aviso passageiro sair da tela: a fila do Snackbar mostra um de cada vez. */
    private fun esperarAvisoSair(texto: String) {
        regra.waitUntil(15_000) { regra.onAllNodesWithText(texto).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun asConfiguracoesQueNaoSeLeemAoAbrirSaoAvisadasTemOMotivoNoFormularioESaoRelidasNaVolta() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        regra.abrir(c)
        regra.esperarTexto(aviso)
        esperarNaMarca(Marcas.AJUSTES_ILEGIVEIS, noLugar)
        // O que não foi lido não aparece como o gravado: nem "0 / 6", nem "US$ 0.00", nem "configure".
        regra.onNodeWithTag(Marcas.METRICA_AGENTES_PRONTOS).assertTextEquals("Não lido")
        regra.onNodeWithTag(Marcas.METRICA_TETO).assertTextEquals("Não lido")
        regra.onNodeWithTag(Marcas.legendaDoAgente(Provedor.CLAUDE)).assertTextEquals("configuração não lida")
        // Sem as configurações lidas, a recusa do Iniciar diz o motivo, e não "configure pelo menos dois agentes".
        esperarAvisoSair(aviso)
        iniciar()
        regra.esperarTexto(aviso)
        assertTrue(c.sessoes.listar().isEmpty())
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.AJUSTES_ILEGIVEIS).assertDoesNotExist()
    }

    @Test
    fun asConfiguracoesQueNaoSeLeemNaVoltaFicamComoEstavam() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        voltarATela()
        regra.esperarTexto(aviso)
        c.bancoCheio.leituraQuebrada = null
        regra.onNodeWithText("2 / 6").assertExists()
        regra.onNodeWithTag(Marcas.AJUSTES_ILEGIVEIS).assertDoesNotExist()
    }

    @Test
    fun aListaQueNaoSeLeAoAbrirMostraOMotivoNoLugarESoVoltaNaVoltaDaTela() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.ERRO)
        c.bancoCheio.leituraQuebrada = "FROM sessoes ORDER BY atualizadaEm"
        regra.abrir(c)
        esperarNaMarca(Marcas.RECENTES_VAZIO, noLugar)
        regra.esperarTexto(aviso)
        regra.onNodeWithTag(Marcas.METRICA_SESSAO).assertTextEquals("Não lido")
        regra.onNodeWithTag(Marcas.METRICA_COM_O_TRABALHO).assertTextEquals("Não lido")
        // O resto da tela segue: os cartões leram as configurações.
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.leituraQuebrada = null
        // Sem a volta da tela, a lista não é relida: nada de laço.
        Thread.sleep(1_000)
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.sessao(id)).assertDoesNotExist()
        voltarATela()
        esperarMarca(Marcas.sessao(id))
        regra.onNodeWithTag(Marcas.RECENTES_VAZIO).assertDoesNotExist()
    }

    @Test
    fun aListaQueCaiDepoisDeLidaFicaComoEstavaEVoltaNaVoltaDaTela() {
        val primeira = c.sessao(Estados.ERRO, titulo = "Primeira")
        regra.abrir(c)
        esperarMarca(Marcas.sessao(primeira))
        c.bancoCheio.leituraQuebrada = "FROM sessoes ORDER BY atualizadaEm"
        val segunda = c.sessao(Estados.LIMITE_DE_CUSTO, titulo = "Segunda")
        regra.esperarTexto(aviso)
        regra.onNodeWithTag(Marcas.sessao(primeira)).assertExists()
        regra.onNodeWithTag(Marcas.sessao(segunda)).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.RECENTES_VAZIO).assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        esperarMarca(Marcas.sessao(segunda))
    }

    @Test
    fun osEventosQueNaoSeLeemAoAbrirNaoApagamAListaEVoltamNaVoltaDaTela() {
        val id = c.sessao(Estados.ERRO)
        c.evento(id, "running", "revisando", agente = Provedor.GEMINI)
        c.bancoCheio.leituraQuebrada = "FROM eventos WHERE sessaoId"
        regra.abrir(c)
        regra.esperarTexto(aviso)
        esperarMarca(Marcas.sessao(id))
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, "Maestro AI")
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.GEMINI.agente))
    }

    @Test
    fun osEventosQueNaoSeLeemDaNovaSessaoDoTopoNaoMostramOAgenteDaAnterior() {
        val anterior = c.sessao(Estados.ERRO)
        c.evento(anterior, "running", "revisando", agente = Provedor.GEMINI)
        regra.abrir(c)
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.GEMINI.agente))
        c.bancoCheio.leituraQuebrada = "FROM eventos WHERE sessaoId"
        // O relógio do cenário anda a cada leitura: a nova fica no topo.
        val nova = c.sessao(Estados.ERRO, titulo = "Mais nova")
        esperarMarca(Marcas.sessao(nova))
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, "Maestro AI")
        c.bancoCheio.leituraQuebrada = null
    }

    @Test
    fun osEventosJaMostradosFicamQuandoAReleituraCai() {
        val id = c.sessao(Estados.ERRO)
        c.evento(id, "running", "revisando", agente = Provedor.GEMINI)
        regra.abrir(c)
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.GEMINI.agente))
        c.bancoCheio.leituraQuebrada = "FROM eventos WHERE sessaoId"
        c.evento(id, "blocked", "parado")
        regra.esperarTexto(aviso)
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.METRICA_COM_O_TRABALHO).assertTextEquals(Rotulos.agente(Provedor.GEMINI.agente))
        c.bancoCheio.leituraQuebrada = null
    }

    @Test
    fun umDiscoQueDerrubaVariasLeiturasDaUmAvisoPorVoltaDaTela() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        c.sessao(Estados.ERRO)
        // "oes" casa com configuracoes, sessoes e execucoes: a abertura derruba a lista e os ajustes.
        c.bancoCheio.leituraQuebrada = "oes"
        regra.abrir(c)
        regra.esperarTexto(aviso)
        esperarAvisoSair(aviso)
        val segundo = runCatching { regra.waitUntil(2_000) { regra.onAllNodesWithText(aviso).fetchSemanticsNodes().isNotEmpty() } }
        assertTrue("a mesma volta deu um segundo aviso", segundo.isFailure)
        // Na volta da tela, as leituras falham de novo, e sai um aviso novo.
        voltarATela()
        regra.esperarTexto(aviso)
        c.bancoCheio.leituraQuebrada = null
    }

    @Test
    fun aReconciliacaoDaAberturaQueCaiNoArmazenamentoViraAvisoNaTelaInicialEETentadaDeNovo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        // Rodando, sem execução e sem trabalho vivo: a reconciliação a interrompe e reenfileira.
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.cheio = true
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        regra.esperarTexto(avisoDaAbertura(BancoCheio.MENSAGEM))
        c.bancoCheio.cheio = false
        assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
        assertTrue(c.agendador.enfileiradas.isEmpty())
        // A próxima entrada em primeiro plano tenta de novo.
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        assertNull(c.abertura.falhaDaReconciliacao.value)
        assertEquals(listOf(id), c.agendador.enfileiradas.toList())
        assertEquals(Estados.NA_FILA, c.sessoes.carregar(id)?.status)
    }

    @Test
    fun aConsultaAoWorkManagerQueFalhaNaReconciliacaoChegaDesembrulhadaNoAviso() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.agendador.falhaAoConsultar = ExecutionException(SQLiteDiskIOException(BancoCheio.MENSAGEM_DE_DISCO))
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        c.agendador.falhaAoConsultar = null
        regra.esperarTexto(avisoDaAbertura(BancoCheio.MENSAGEM_DE_DISCO))
        // A consulta vem antes de qualquer escrita.
        assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
    }

    @Test
    fun aReconciliacaoDaAberturaEsperaATravaEApagaAFalhaAnteriorAoRecomecar() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.RODANDO)
        c.bancoCheio.cheio = true
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        c.bancoCheio.cheio = false
        assertNotNull(c.abertura.falhaDaReconciliacao.value)
        runBlocking { Sincronia.reconciliacao.lock() }
        val tentativa = try {
            thread { runBlocking(Dispatchers.IO) { c.abertura.executar() } }.also {
                // A tentativa nova apaga a falha anterior antes da trava, e espera a trava para reconciliar.
                regra.waitUntil(5_000) { c.abertura.falhaDaReconciliacao.value == null }
                Thread.sleep(1_000)
                assertTrue(c.agendador.enfileiradas.isEmpty())
                assertEquals(Estados.RODANDO, c.sessoes.carregar(id)?.status)
            }
        } finally {
            Sincronia.reconciliacao.unlock()
        }
        tentativa.join(10_000)
        assertEquals(listOf(id), c.agendador.enfileiradas.toList())
    }

    @Test
    fun oAvisoDaAberturaSoApareceNaTelaInicialEnquantoValeEUmaVezSo() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        val id = c.sessao(Estados.RODANDO)
        regra.abrir(c, sessaoPedida = id)
        esperarMarca(Marcas.VOLTAR)
        val trecho = "Não foi possível concluir a conferência da abertura"
        // Falha com outra tela por cima: a tela inicial não está composta, e nada é avisado.
        c.bancoCheio.cheio = true
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        c.bancoCheio.cheio = false
        assertNotNull(c.abertura.falhaDaReconciliacao.value)
        regra.waitForIdle()
        Thread.sleep(1_000)
        regra.onAllNodesWithText(trecho, substring = true).assertCountEquals(0)
        // A tentativa seguinte dá certo antes de a pessoa voltar: a falha antiga não é avisada.
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_000)
        regra.onAllNodesWithText(trecho, substring = true).assertCountEquals(0)
        // A linha ficou na fila sem trabalho de verdade (o dublê não enfileira): a próxima reconciliação a toca de novo.
        c.bancoCheio.cheio = true
        runBlocking(Dispatchers.IO) { c.abertura.executar() }
        c.bancoCheio.cheio = false
        regra.esperarTexto(avisoDaAbertura(BancoCheio.MENSAGEM))
        esperarAvisoSair(avisoDaAbertura(BancoCheio.MENSAGEM))
        // Ir às configurações e voltar não repete o aviso já dado.
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        esperarMarca(Marcas.VOLTAR)
        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_000)
        regra.onAllNodesWithText(trecho, substring = true).assertCountEquals(0)
    }

    @Test
    fun oBancoDoWorkManagerQueNaoAbreEAvisadoNaTelaInicialUmaVez() {
        // O `initializationExceptionHandler` recebe a falha do SQLite dentro de uma `IllegalStateException`.
        c.abertura.falhouNoWorkManager(IllegalStateException("estado ruim", SQLiteCantOpenDatabaseException("não abre")))
        regra.abrir(c)
        val texto = "O agendador do Android, que executa as sessões em segundo plano, não conseguiu iniciar o próprio banco de dados. Motivo: não abre"
        regra.esperarTexto(texto)
        esperarAvisoSair(texto)
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        esperarMarca(Marcas.VOLTAR)
        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        Thread.sleep(1_000)
        regra.onAllNodesWithText(texto).assertCountEquals(0)
    }

    @Test
    fun aSessaoQueVoltaAoTopoSemConseguirLerOsEventosMostraOsDelaENaoOsDaOutra() {
        val primeira = c.sessao(Estados.ERRO, titulo = "Primeira")
        c.evento(primeira, "running", "revisando", agente = Provedor.GEMINI)
        regra.abrir(c)
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.GEMINI.agente))
        val segunda = c.sessao(Estados.ERRO, titulo = "Segunda")
        c.evento(segunda, "running", "redigindo", agente = Provedor.CLAUDE)
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.CLAUDE.agente))
        c.bancoCheio.leituraQuebrada = "FROM eventos WHERE sessaoId"
        // O custo toca a linha da primeira, que volta ao topo; os eventos dela não se leem, e ficam os lidos antes.
        c.sessoes.somarCusto(primeira, BigDecimal("0.1"))
        regra.esperarTexto(aviso)
        esperarNaMarca(Marcas.METRICA_COM_O_TRABALHO, Rotulos.agente(Provedor.GEMINI.agente))
        c.bancoCheio.leituraQuebrada = null
    }

    @Test
    fun aFalhaDeUmaReleituraDosAjustesJaSuperadaNaoAvisa() {
        // Achado do Codex na #81, na mesma regra: a releitura antiga que falha depois de uma mais nova aplicada não decide.
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        // A releitura de uma volta fica presa na leitura das configurações.
        val trava = CountDownLatch(1)
        c.bancoCheio.prenderLeitura("FROM configuracoes", depoisDe = 0, trava)
        voltarATela()
        regra.waitUntil(5_000) { c.bancoCheio.leituraPresa == null }
        // A da volta seguinte termina bem e é aplicada.
        voltarATela()
        Thread.sleep(1_000)
        regra.waitForIdle()
        // A antiga, solta, falha na leitura do orçamento.
        val antes = c.bancoCheio.quebradas.get()
        c.bancoCheio.leituraQuebrada = "FROM execucoes"
        trava.countDown()
        regra.waitUntil(5_000) { c.bancoCheio.quebradas.get() > antes }
        c.bancoCheio.leituraQuebrada = null
        val avisou = runCatching { regra.waitUntil(3_000) { regra.onAllNodesWithText(aviso).fetchSemanticsNodes().isNotEmpty() } }
        assertTrue("a releitura superada avisou", avisou.isFailure)
        regra.onNodeWithText("2 / 6").assertExists()
    }
}
