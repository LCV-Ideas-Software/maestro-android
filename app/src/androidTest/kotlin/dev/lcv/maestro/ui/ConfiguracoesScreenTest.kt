package dev.lcv.maestro.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tela de configurações sobre o Room real e o cofre dublê: a chave guardada
 * depois da autenticação quando a janela venceu, o aparelho sem trava, o
 * terceiro estado do cofre, as regras do núcleo ao salvar, o nível da chave
 * do Keystore e o teste de chaves atrás de confirmação e autenticação.
 */
@RunWith(AndroidJUnit4::class)
class ConfiguracoesScreenTest {

    val regra = createComposeRule()

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
}
