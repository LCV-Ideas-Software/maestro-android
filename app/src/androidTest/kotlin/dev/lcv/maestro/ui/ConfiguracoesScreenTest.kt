package dev.lcv.maestro.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.ui.configuracoes.ConfiguracoesViewModel
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A tela de configurações sobre o Room real e o cofre dublê: a chave guardada
 * depois da autenticação quando a janela venceu, o aparelho sem trava, o
 * terceiro estado do cofre, as regras do núcleo ao salvar, o nível da chave
 * do Keystore e o teste de chaves atrás de confirmação e autenticação.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguracoesScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private fun abrirConfiguracoes() {
        regra.abrir(c)
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        // O botão só se habilita depois de o formulário receber o que está gravado.
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasTestTag(Marcas.SALVAR_CONFIGURACOES) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A pílula é uma superfície com o texto dentro: procura o texto exato sob a marca dela. */
    private fun esperarPilula(provedor: Provedor, texto: String) {
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasText(texto) and hasAnyAncestor(hasTestTag(Marcas.pilulaDaChave(provedor)))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun digitar(marca: String, texto: String) {
        regra.onNodeWithTag(marca).performScrollTo().performTextClearance()
        regra.onNodeWithTag(marca).performTextInput(texto)
    }

    @Test
    fun comAJanelaVencidaAutenticaEGuardaNaSegundaTentativa() {
        c.cofre.respostas += Guarda.ExigeAutenticacao
        abrirConfiguracoes()
        digitar(Marcas.chave(Provedor.CLAUDE), "sk-teste-1")
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.esperarTexto("Chave guardada neste aparelho.")
        assertEquals(1, c.autenticacoes.get())
        assertEquals(listOf(Provedor.CLAUDE to "sk-teste-1", Provedor.CLAUDE to "sk-teste-1"), c.cofre.guardadas.toList())
        esperarPilula(Provedor.CLAUDE, "configurada")
        // A chave digitada some do campo depois de guardada: nunca é exibida de volta.
        regra.onNodeWithTag(Marcas.chave(Provedor.CLAUDE)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test
    fun semAutenticacaoAChaveNaoEGuardada() {
        c.cofre.respostas += Guarda.ExigeAutenticacao
        c.autentica = false
        abrirConfiguracoes()
        digitar(Marcas.chave(Provedor.CODEX), "sk-teste-2")
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CODEX)).performScrollTo().performClick()
        regra.esperarTexto("Sem a sua autenticação, as chaves guardadas não são usadas; nada foi feito.")
        assertEquals(1, c.cofre.guardadas.size)
    }

    @Test
    fun semTravaDeTelaOCofreRecusaEATelaOferecemOsAjustes() {
        c.cofre.respostas += Guarda.SemTravaDeTela
        c.cofre.trava = false
        abrirConfiguracoes()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.SEM_TRAVA_AVISO).fetchSemanticsNodes().isNotEmpty() }
        digitar(Marcas.chave(Provedor.GEMINI), "chave-gemini")
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.GEMINI)).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.SEM_TRAVA).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Política de Dados do Usuário do Google Play (seção 9): o link da política de privacidade dentro do aplicativo abre a página no navegador do sistema. */
    @Test
    fun aPoliticaDePrivacidadeAbreNoNavegadorDoSistema() {
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.ABRIR_PRIVACIDADE).performScrollTo().performClick()
        regra.waitUntil(5_000) { c.navegador.abertas.isNotEmpty() }
        assertEquals(listOf(ConfiguracoesViewModel.URL_DA_POLITICA), c.navegador.abertas)
        assertEquals("https://www.lcv.dev/privacy/", ConfiguracoesViewModel.URL_DA_POLITICA)
    }

    /** Sem navegador, o aviso diz o motivo, como na passagem da tela de links. */
    @Test
    fun aPoliticaSemNavegadorAvisaOMotivo() {
        c.navegador.falha = "failed to open system default browser: No Activity found to handle Intent"
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.ABRIR_PRIVACIDADE).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível abrir o navegador para a política de privacidade: failed to open system default browser", substring = true)
    }

    /** A legenda da tela de chaves, palavra por palavra: o resumo da seção 6.1 da política de privacidade 1.5. */
    private val legendaAprovada =
        "O título, o identificador e o pedido da sessão, o texto de partida e as versões seguintes, o protocolo editorial, " +
            "os relatórios das revisões anteriores e um resumo do manifesto de citações (quantas citações e fontes ele tem) " +
            "são enviados, por TLS, aos provedores dos agentes que você ativar, e a nenhum outro provedor de inteligência artificial; " +
            "nenhum servidor da LCV Ideas & Software os recebe, vê ou guarda. " +
            "Quando a auditoria reprova o texto em revisão, vai também aos mesmos provedores o resultado dela, que pode incluir " +
            "citações e referências, links conferidos, propostas de correção e as decisões e justificativas que você registrou. " +
            "O arquivo anexado não é enviado como tal, e os anexos que não são manifesto de citações não saem do aparelho. " +
            "Na conferência dos links, os endereços citados no texto vão aos próprios sites, e o nome de cada site, ao serviço de nomes do Google; " +
            "se você pedir propostas de correção para um link, a sua consulta ou o trecho do texto em torno dele vai ao Crossref e ao OpenAlex. " +
            "O aplicativo pede à OpenAI, ao Google e à xAI que não guardem a conversa para consulta posterior; " +
            "na API da Perplexity usada pelo aplicativo, o mesmo pedido só esconde a resposta da consulta posterior e não desliga a guarda; " +
            "à Anthropic e à DeepSeek esse pedido não vai, porque as APIs delas usadas pelo aplicativo não têm essa opção; " +
            "e cada provedor ainda aplica a própria política de retenção."

    /**
     * Seção 6.3: a tela que pede a chave diz, antes da primeira sessão, o que vai aos provedores e o que dos anexos
     * fica no aparelho, com a frase de retenção da 6.4 — inteira, visível e na árvore de acessibilidade.
     */
    @Test
    fun aTelaDasChavesDizParaOndeVaiOTexto() {
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.TEXTO_PARA_PROVEDORES).performScrollTo().assertIsDisplayed().assertTextEquals(legendaAprovada)
    }

    @Test
    fun oCofreQueNaoRespondeTemOTerceiroEstado() {
        c.cofre.presentes[Provedor.GROK] = null
        abrirConfiguracoes()
        // Antes da leitura do cofre todas as pílulas dizem "não verificável": espera a leitura chegar.
        esperarPilula(Provedor.CLAUDE, "não configurada")
        esperarPilula(Provedor.GROK, "não verificável")
    }

    @Test
    fun salvarComTrezentosEUmMinutosTemAMensagemDoNucleoENaoGrava() {
        abrirConfiguracoes()
        digitar(Marcas.CAMPO_TETO, "5")
        digitar(Marcas.CAMPO_LIMITE, "301")
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        regra.esperarTexto("Limite de tempo opcional deve ficar entre 1 e 300 minutos.")
        assertNull(c.banco.configuracoes().carregar())
    }

    @Test
    fun oLimiteZeroERecusadoComoNoCliente() {
        abrirConfiguracoes()
        digitar(Marcas.CAMPO_TETO, "5")
        digitar(Marcas.CAMPO_LIMITE, "0")
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        regra.esperarTexto(RepositorioDeConfiguracoes.MENSAGEM_LIMITE_DE_MINUTOS)
        assertNull(c.banco.configuracoes().carregar())
    }

    @Test
    fun oTetoVazioTemAMensagemDoWeb() {
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.CAMPO_TETO).performScrollTo().performTextClearance()
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        regra.esperarTexto(RepositorioDeConfiguracoes.MENSAGEM_TETO_POSITIVO)
        assertNull(c.banco.configuracoes().carregar())
    }

    @Test
    fun oBancoCheioAoSalvarEAvisadoSemDerrubarOAplicativo() {
        // Decisão 25 do operador (29/09/2026): o banco cheio numa ação da tela é a falha da ação.
        abrirConfiguracoes()
        val antes = c.configuracoes.carregar().tetoDeCustoUsd
        digitar(Marcas.CAMPO_TETO, "12.5")
        c.bancoCheio.cheio = true
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        assertEquals(0, antes.compareTo(c.configuracoes.carregar().tetoDeCustoUsd))
    }

    @Test
    fun salvarValoresValidosGravaERecarregaOFormulario() {
        abrirConfiguracoes()
        digitar(Marcas.CAMPO_TETO, "12.5")
        digitar(Marcas.CAMPO_LIMITE, "120")
        digitar(Marcas.entrada(Provedor.DEEPSEEK), "2")
        digitar(Marcas.CAMPO_EMAIL, " pessoa@exemplo.org ")
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        regra.esperarTexto("Configurações salvas.")
        val salvas = c.configuracoes.carregar()
        assertEquals(0, BigDecimal("12.5").compareTo(salvas.tetoDeCustoUsd))
        assertEquals(120, salvas.tetoDeMinutos)
        assertEquals(0, BigDecimal("2").compareTo(salvas.taxas.getValue(Provedor.DEEPSEEK).entradaPorMilhao))
        assertEquals("pessoa@exemplo.org", salvas.emailDeContato)
        regra.onNodeWithTag(Marcas.CAMPO_EMAIL).assertTextEquals("pessoa@exemplo.org")
    }

    @Test
    fun oNivelDaChaveDoKeystoreEODoAparelho() {
        c.cofre.nivel = NivelDoCofre.STRONGBOX
        abrirConfiguracoes()
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasTestTag(Marcas.NIVEL_DO_COFRE) and hasText("Chave do Keystore em chip dedicado (StrongBox)."))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun testarChavesConfirmaAutenticaESoChamaQuemTemChave() {
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.TESTAR_CHAVES).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.CONFIRMAR_TESTE).performClick()
        regra.esperarTexto("4 agentes exigem atenção.")
        assertEquals(1, c.autenticacoes.get())
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX), c.testadas.toList())
        Provedor.entries.forEach { regra.onNodeWithTag(Marcas.resultadoDoTeste(it)).assertExists() }
    }

    // Revisão antes do push da rodada 10 na #78 (decisão 25 do operador).

    @Test
    fun oDiscoQueFalhaAoLerAsTarifasDoTesteEAvisado() {
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        abrirConfiguracoes()
        c.bancoCheio.leituraQuebrada = "configuracoes"
        regra.onNodeWithTag(Marcas.TESTAR_CHAVES).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.CONFIRMAR_TESTE).performClick()
        regra.esperarTexto("Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        c.bancoCheio.leituraQuebrada = null
        assertEquals(emptyList<Provedor>(), c.testadas.toList())
    }

    @Test
    fun oCofreQueNaoGravaDizOMotivo() {
        c.cofre.respostas += Guarda.Falhou("disco que não grava")
        abrirConfiguracoes()
        digitar(Marcas.chave(Provedor.CLAUDE), "sk-teste-1")
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.esperarTexto("O cofre de chaves não gravou a chave; tente de novo. Motivo: disco que não grava")
    }

    @Test
    fun aRemocaoQueFalhaDizOMotivo() {
        c.chaves(Provedor.CLAUDE)
        c.cofre.falhaAoApagar = "disco que não grava"
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.removerChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível remover a chave agora; tente de novo. Motivo: disco que não grava")
        esperarPilula(Provedor.CLAUDE, "configurada")
    }

    @Test
    fun testarChavesSemAutenticacaoNaoChamaNinguem() {
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        c.autentica = false
        abrirConfiguracoes()
        regra.onNodeWithTag(Marcas.TESTAR_CHAVES).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.CONFIRMAR_TESTE).performClick()
        regra.esperarTexto("Sem a sua autenticação, as chaves guardadas não são usadas; nada foi feito.")
        assertEquals(emptyList<Provedor>(), c.testadas.toList())
    }

    // Decisão 25 do operador, estendida em 30/09/2026 (#80): a leitura das configurações ao abrir a tela. "Aviso e
    // segue": o aviso com o motivo, o motivo no lugar do formulário, e a volta da tela lê de novo até carregar.

    private val aviso = "Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
    private val noLugar = "Não foi possível ler os dados desta tela; ela tenta de novo quando você voltar a ela. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"

    /** A tela ao segundo plano e de volta: só o ON_RESUME relê. */
    private fun voltarATela() {
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun asConfiguracoesQueNaoSeLeemMostramOMotivoNoLugarDoFormularioESaoRelidasNaVolta() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        // A tela inicial lê primeiro; só a de Configurações encontra o disco quebrado.
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        regra.esperarTexto(aviso)
        regra.waitUntil(5_000) { regra.onAllNodes(hasTestTag(Marcas.LEITURA_FALHOU) and hasText(noLugar)).fetchSemanticsNodes().isNotEmpty() }
        // Custos, Contato e Protocolo não aparecem vazios como se fossem o gravado; as chaves seguem.
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.CAMPO_TETO).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.CAMPO_EMAIL).assertDoesNotExist()
        regra.onNodeWithTag(Marcas.chave(Provedor.CLAUDE)).assertExists()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasTestTag(Marcas.SALVAR_CONFIGURACOES) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
    }

    @Test
    fun aVoltaDaTelaNaoSobrescreveOQueFoiDigitado() {
        c.configurar()
        abrirConfiguracoes()
        digitar(Marcas.CAMPO_TETO, "7")
        voltarATela()
        // A releitura, se houvesse, corre em `Dispatchers.IO`, que o `waitForIdle` não espera.
        Thread.sleep(1_000)
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.CAMPO_TETO).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("7")))
    }

    // Rodada 3 da revisão da #81: as releituras desta tela, em ordem.

    @Test
    fun aFalhaDeUmaLeituraJaSuperadaPorOutraQueCarregouNaoAvisa() {
        c.configurar()
        c.chaves(Provedor.CLAUDE, Provedor.CODEX)
        regra.abrir(c)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("2 / 6").fetchSemanticsNodes().isNotEmpty() }
        // A leitura da abertura das Configurações fica presa; a da volta seguinte carrega o formulário.
        val trava = CountDownLatch(1)
        c.bancoCheio.prenderLeitura("FROM configuracoes", depoisDe = 0, trava)
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        regra.waitUntil(5_000) { c.bancoCheio.leituraPresa == null }
        voltarATela()
        regra.waitUntil(5_000) {
            regra.onAllNodes(hasTestTag(Marcas.SALVAR_CONFIGURACOES) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        // A antiga, solta, falha.
        val antes = c.bancoCheio.quebradas.get()
        c.bancoCheio.leituraQuebrada = "FROM configuracoes"
        trava.countDown()
        regra.waitUntil(5_000) { c.bancoCheio.quebradas.get() > antes }
        c.bancoCheio.leituraQuebrada = null
        val avisou = runCatching { regra.waitUntil(3_000) { regra.onAllNodesWithText(aviso).fetchSemanticsNodes().isNotEmpty() } }
        assertTrue("a leitura superada avisou", avisou.isFailure)
    }

    @Test
    fun aReleituraAntigaDoCofreNaoRepoeAChaveRemovida() {
        c.chaves(Provedor.CLAUDE)
        abrirConfiguracoes()
        esperarPilula(Provedor.CLAUDE, "configurada")
        // A releitura da volta tira a foto (com a chave) e fica presa; a de depois da remoção vem depois e termina antes.
        c.cofre.chavesPresas = CountDownLatch(1)
        val presa = c.cofre.chavesPresas!!
        voltarATela()
        regra.waitUntil(5_000) { c.cofre.chavesPresas == null }
        regra.onNodeWithTag(Marcas.removerChave(Provedor.CLAUDE)).performScrollTo().performClick()
        esperarPilula(Provedor.CLAUDE, "não configurada")
        presa.countDown()
        Thread.sleep(1_000)
        regra.waitForIdle()
        esperarPilula(Provedor.CLAUDE, "não configurada")
        regra.onAllNodes(hasText("configurada") and hasAnyAncestor(hasTestTag(Marcas.pilulaDaChave(Provedor.CLAUDE))))
            .assertCountEquals(0)
        // A releitura antiga ficou presa até o teste a soltar, e não até o prazo vencer.
        assertEquals(0, c.cofre.travasVencidas.get())
    }

    // MAEANDR-40: o que se digita enquanto a gravação corre fica no formulário.

    /** O campo da chave é de senha: a semântica dá os pontos no `EditableText` e o que foi digitado no `InputText`. */
    private fun chaveDigitada(texto: String) = SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(texto))

    /** Espera o aviso sair: o `Snackbar` cobre o pé da tela, e um toque no botão que está embaixo dele cairia no aviso. */
    private fun esperarAvisoSair(texto: String) {
        regra.waitUntil(15_000) { regra.onAllNodesWithText(texto).fetchSemanticsNodes().isEmpty() }
    }

    /**
     * Só sai do campo a chave que foi ao cofre: a digitada enquanto ele grava fica, quer a gravação falhe, quer dê
     * certo; sem nada digitado no meio, a chave guardada sai do campo, como antes.
     */
    @Test
    fun aChaveDigitadaDuranteAGravacaoFicaNoCampo() {
        c.cofre.respostas += Guarda.Falhou("disco que não grava")
        abrirConfiguracoes()
        digitar(Marcas.chave(Provedor.CLAUDE), "sk-teste-1")
        // A gravação que vai falhar fica presa no cofre, e nesse meio-tempo a pessoa digita outra chave.
        val primeira = CountDownLatch(1)
        c.cofre.gravacaoPresa = primeira
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.waitUntil(5_000) { c.cofre.gravacaoPresa == null }
        digitar(Marcas.chave(Provedor.CLAUDE), "sk-teste-2")
        primeira.countDown()
        regra.esperarTexto("O cofre de chaves não gravou a chave; tente de novo. Motivo: disco que não grava")
        regra.onNodeWithTag(Marcas.chave(Provedor.CLAUDE)).assert(chaveDigitada("sk-teste-2"))
        esperarAvisoSair("O cofre de chaves não gravou a chave; tente de novo. Motivo: disco que não grava")
        // A que dá certo, com a chave nova, também fica presa; a terceira, digitada durante ela, fica no campo.
        val segunda = CountDownLatch(1)
        c.cofre.gravacaoPresa = segunda
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.waitUntil(5_000) { c.cofre.gravacaoPresa == null }
        digitar(Marcas.chave(Provedor.CLAUDE), "sk-teste-3")
        segunda.countDown()
        regra.esperarTexto("Chave guardada neste aparelho.")
        regra.onNodeWithTag(Marcas.chave(Provedor.CLAUDE)).assert(chaveDigitada("sk-teste-3"))
        esperarAvisoSair("Chave guardada neste aparelho.")
        // Sem nada digitado durante a gravação, a chave guardada sai do campo.
        regra.onNodeWithTag(Marcas.salvarChave(Provedor.CLAUDE)).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodes(hasTestTag(Marcas.chave(Provedor.CLAUDE)) and chaveDigitada("")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(
            listOf(Provedor.CLAUDE to "sk-teste-1", Provedor.CLAUDE to "sk-teste-2", Provedor.CLAUDE to "sk-teste-3"),
            c.cofre.guardadas.toList(),
        )
        // As duas gravações ficaram presas até o teste as soltar, e não até o prazo vencer.
        assertEquals(0, c.cofre.travasVencidas.get())
    }

    /** O mesmo nas configurações: o campo mudado durante a gravação fica, e o que ninguém mudou recebe o gravado. */
    @Test
    fun oQueSeDigitaDuranteAGravacaoDasConfiguracoesFicaNoFormulario() {
        abrirConfiguracoes()
        digitar(Marcas.CAMPO_TETO, "12.5")
        digitar(Marcas.entrada(Provedor.DEEPSEEK), "1")
        digitar(Marcas.CAMPO_EMAIL, " pessoa@exemplo.org ")
        // A gravação fica presa no meio da transação, e nesse meio-tempo a pessoa muda o teto e uma tarifa.
        val trava = CountDownLatch(1)
        c.bancoCheio.tabelaTravada = "configuracoes"
        c.bancoCheio.trava = trava
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performClick()
        digitar(Marcas.CAMPO_TETO, "7")
        digitar(Marcas.saida(Provedor.DEEPSEEK), "5")
        // Ainda gravando: as mudanças vieram durante a gravação, e não depois dela.
        regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).assertIsNotEnabled()
        trava.countDown()
        regra.esperarTexto("Configurações salvas.")
        // Gravou-se o que foi enviado; o que mudou durante a gravação fica no formulário, para a próxima.
        val salvas = c.configuracoes.carregar()
        assertEquals(0, BigDecimal("12.5").compareTo(salvas.tetoDeCustoUsd))
        assertEquals(0, BigDecimal("3.96").compareTo(salvas.taxas.getValue(Provedor.DEEPSEEK).saidaPorMilhao))
        regra.onNodeWithTag(Marcas.CAMPO_TETO).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("7")))
        regra.onNodeWithTag(Marcas.saida(Provedor.DEEPSEEK)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("5")))
        // Os campos que ninguém mudou recebem o gravado: a tarifa abaixo do padrão sobe para ele, e o e-mail vem aparado.
        regra.onNodeWithTag(Marcas.entrada(Provedor.DEEPSEEK)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("1.32")))
        regra.onNodeWithTag(Marcas.CAMPO_EMAIL).assertTextEquals("pessoa@exemplo.org")
    }
}
