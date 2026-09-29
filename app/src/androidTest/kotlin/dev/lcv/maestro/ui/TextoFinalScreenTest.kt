package dev.lcv.maestro.ui

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.R
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.ui.textofinal.ClienteDaPagina
import dev.lcv.maestro.ui.textofinal.RenderizadorDoTextoFinal
import dev.lcv.maestro.ui.textofinal.TextoFinalViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A tela do texto final (especificação, seção 4.4; plano do `:app`, emenda A5;
 * critério de aceite da MAEANDR-21): só o texto liberado, num `WebView` sem
 * script, arquivo, rede nem navegação, e as três exportações. Os seletores de
 * documento são o [SeletorDeTeste], sobre a API oficial para isso.
 */
@RunWith(AndroidJUnit4::class)
class TextoFinalScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private val textoFinal = "# Artigo de teste\n\nCorpo com *ênfase* e um [link](https://exemplo.org).\n\n- um\n- dois\n"

    private fun abrirNoTextoFinal(id: String, seletor: SeletorDeTeste = SeletorDeTeste()) {
        regra.abrir(c, sessaoPedida = id, seletor = seletor)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.ABRIR_TEXTO_FINAL).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ABRIR_TEXTO_FINAL).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.TEXTO_FINAL_PAGINA).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pagina(): WebView {
        var achada: WebView? = null
        regra.runOnUiThread {
            fun procurar(vista: View) {
                if (vista is WebView) achada = vista
                if (vista is ViewGroup) (0 until vista.childCount).forEach { procurar(vista.getChildAt(it)) }
            }
            procurar(regra.activity.window.decorView)
        }
        assertNotNull("a tela do texto final tem um WebView", achada)
        return achada!!
    }

    @Test
    fun oTextoLiberadoAbreNumaPaginaTravadaComAsTresExportacoes() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = textoFinal, textoAtual = textoFinal)
        abrirNoTextoFinal(id)
        // O PDF é habilitado pelo `onPageFinished` da página (emenda A5). A página local carrega antes de
        // qualquer asserção chegar a ver o botão desligado; o que se prova é que ele acende depois da carga.
        regra.waitUntil(10_000) {
            runCatching { regra.onNodeWithTag(Marcas.EXPORTAR_PDF).assertIsEnabled() }.isSuccess
        }
        regra.onNodeWithTag(Marcas.EXPORTAR_MARKDOWN).assertIsEnabled()
        regra.onNodeWithTag(Marcas.EXPORTAR_TXT).assertIsEnabled()

        val pagina = pagina()
        var permitiuNavegar = true
        regra.runOnUiThread {
            val ajustes = pagina.settings
            assertFalse(ajustes.javaScriptEnabled)
            assertFalse(ajustes.allowFileAccess)
            assertFalse(ajustes.allowContentAccess)
            assertTrue(ajustes.blockNetworkLoads)
            assertTrue(ajustes.blockNetworkImage)
            assertEquals(WebSettings.LOAD_NO_CACHE, ajustes.cacheMode)
            assertFalse(ajustes.domStorageEnabled)
            val pedido = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse("https://exemplo.org")
                override fun isForMainFrame() = true
                override fun isRedirect() = false
                override fun hasGesture() = true
                override fun getMethod() = "GET"
                override fun getRequestHeaders(): Map<String, String> = emptyMap()
            }
            permitiuNavegar = !pagina.webViewClient.shouldOverrideUrlLoading(pagina, pedido)
        }
        assertFalse("um link do texto não pode navegar", permitiuNavegar)
    }

    @Test
    fun oClienteDaPaginaAvisaOFimRecusaNavegarETrocaAPaginaQueMorreu() {
        var terminou = 0
        var morreu = 0
        val cliente = ClienteDaPagina(aoTerminar = { terminou += 1 }, aoPerderOProcesso = { morreu += 1 })
        val morte = object : RenderProcessGoneDetail() {
            override fun didCrash() = true
            override fun rendererPriorityAtExit() = 0
        }
        regra.runOnUiThread {
            val pagina = WebView(regra.activity)
            cliente.onPageFinished(pagina, null)
            assertEquals(1, terminou)
            // `true`: a página morta é descartada pela tela, que cria outra, em vez de o processo cair junto.
            assertTrue(cliente.onRenderProcessGone(pagina, morte))
            assertEquals(1, morreu)
            pagina.destroy()
        }
    }

    @Test
    fun aTelaAbertaTiraAPaginaSeOTextoPerdeALiberacao() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = textoFinal, textoAtual = textoFinal)
        abrirNoTextoFinal(id)
        // A tela observa a linha: fora de `converged`, o texto deixa de ser liberado e sai com a página.
        c.banco.sessoes().mudarStatus(id, listOf(Estados.CONVERGIDA), Estados.AUDITORIA_FINAL, null, c.agora(), null)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.TEXTO_NAO_LIBERADO).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, regra.onAllNodesWithTag(Marcas.TEXTO_FINAL_PAGINA).fetchSemanticsNodes().size)
        regra.onNodeWithTag(Marcas.EXPORTAR_MARKDOWN).assertDoesNotExist()
    }

    @Test
    fun textoFinalNumaSessaoQueNaoConvergiuNaoELiberado() {
        // Só a convergência libera (seção 4.4): um texto final gravado numa sessão em outro status não abre.
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoFinal = textoFinal, textoAtual = textoFinal)
        regra.abrir(c, sessaoPedida = id)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.TEXTO_DA_SESSAO).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ABRIR_TEXTO_FINAL).assertDoesNotExist()
    }

    @Test
    fun semTextoLiberadoNaoHaBotaoNemPagina() {
        // A auditoria recusou o texto (HTML cru, por exemplo): a sessão pausou e não há texto final.
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoFinal = null, textoAtual = "<script>alert(1)</script>")
        regra.abrir(c, sessaoPedida = id)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.TEXTO_DA_SESSAO).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ABRIR_TEXTO_FINAL).assertDoesNotExist()
        assertEquals(0, regra.onAllNodesWithTag(Marcas.TEXTO_FINAL_PAGINA).fetchSemanticsNodes().size)
    }

    @Test
    fun oMarkdownSaiTalQualEOTxtSaiDoRenderizador() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = textoFinal, textoAtual = textoFinal, titulo = "Artigo: teste/1")
        val md = File(c.contexto.cacheDir, "export-${System.nanoTime()}.md")
        val txt = File(c.contexto.cacheDir, "export-${System.nanoTime()}.txt")
        val seletor = SeletorDeTeste(Uri.fromFile(md))
        abrirNoTextoFinal(id, seletor)

        regra.onNodeWithTag(Marcas.EXPORTAR_MARKDOWN).performClick()
        regra.esperarTexto("Markdown exportado.")
        assertArrayEquals(textoFinal.toByteArray(Charsets.UTF_8), md.readBytes())

        seletor.resposta = Uri.fromFile(txt)
        regra.onNodeWithTag(Marcas.EXPORTAR_TXT).performClick()
        regra.esperarTexto("TXT exportado.")
        assertEquals(RenderizadorDoTextoFinal.texto(textoFinal), txt.readText(Charsets.UTF_8))
        // O nome sugerido é o título, sem o que um nome de arquivo não aceita.
        assertEquals(listOf("Artigo- teste-1.md", "Artigo- teste-1.txt"), seletor.pedidos.toList())
        md.delete()
        txt.delete()
    }

    @Test
    fun seletorCanceladoOuDocumentoQueNaoAbreNaoDeixamArquivo() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = textoFinal, textoAtual = textoFinal)
        val seletor = SeletorDeTeste()
        abrirNoTextoFinal(id, seletor)

        regra.onNodeWithTag(Marcas.EXPORTAR_MARKDOWN).performClick()
        regra.esperarTexto("Exportação cancelada.")

        val impossivel = File(c.contexto.cacheDir, "nao-existe-${System.nanoTime()}/texto.md")
        seletor.resposta = Uri.fromFile(impossivel)
        regra.onNodeWithTag(Marcas.EXPORTAR_TXT).performClick()
        regra.esperarTexto("Não foi possível gravar o arquivo; nada foi salvo.")
        assertFalse(impossivel.exists())
    }

    /**
     * O documento que o seletor criou e a gravação não encheu é apagado. O endereço `file:` do teste
     * não é documento de provedor, então o que se prova é que a falha pede a remoção daquele documento.
     */
    @Test
    fun umaGravacaoQueFalhaApagaODocumentoQueOSeletorCriou() {
        val impossivel = Uri.fromFile(File(c.contexto.cacheDir, "nao-existe-${System.nanoTime()}/texto.md"))
        val apagados = mutableListOf<Uri>()
        assertFalse(TextoFinalViewModel.gravar(c.contexto.contentResolver, impossivel, "texto".toByteArray()) { _, uri -> apagados += uri })
        assertEquals(listOf(impossivel), apagados)
    }

    /**
     * Achado do Codex na #78: recriada a Activity com o seletor aberto, o resultado chega a um
     * ViewModel novo, que ainda não leu a sessão. Chamar a exportação logo depois de criá-lo é
     * exatamente esse instante.
     */
    @Test
    fun aExportacaoQueChegaAntesDaLeituraDaSessaoEsperaOTexto() {
        val id = c.sessao(Estados.CONVERGIDA, textoFinal = textoFinal, textoAtual = textoFinal)
        val destino = c.arquivo("texto.md", ByteArray(0))
        val vm = TextoFinalViewModel(c.dependencias, id)
        vm.exportar(TextoFinalViewModel.Formato.MARKDOWN, Uri.fromFile(destino), c.contexto.contentResolver)
        assertEquals(Mensagem.DeRecurso(R.string.exportado_markdown), runBlocking { withTimeout(10_000) { vm.avisos.first() } })
        assertArrayEquals(textoFinal.toByteArray(Charsets.UTF_8), destino.readBytes())
    }

    @Test
    fun semTextoLiberadoODocumentoQueOSeletorCriouEApagado() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        val destino = Uri.fromFile(c.arquivo("texto.md", ByteArray(0)))
        val apagados = mutableListOf<Uri>()
        val vm = TextoFinalViewModel(c.dependencias, id)
        vm.exportar(TextoFinalViewModel.Formato.MARKDOWN, destino, c.contexto.contentResolver) { _, uri -> apagados += uri }
        assertEquals(Mensagem.DeRecurso(R.string.exportacao_falhou), runBlocking { withTimeout(10_000) { vm.avisos.first() } })
        assertEquals(listOf(destino), apagados)
    }
}
