/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.textofinal

import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.VazioDeResultado

/**
 * O texto final formatado e as três exportações (especificação, seção 4.4;
 * plano do `:app`, emenda A5). O `WebView` mostra só a página que o
 * [RenderizadorDoTextoFinal] gerou no aparelho, sem script, sem arquivo, sem
 * rede e sem navegação; o PDF é o print framework do sistema sobre ele, e só
 * fica disponível depois de a página terminar de carregar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextoFinalScreen(vm: TextoFinalViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val recursos = LocalResources.current
    val resolver = LocalContext.current.contentResolver
    val atividade = LocalActivity.current
    LaunchedEffect(vm) { vm.avisos.collect { avisos.mostrar(it.em(recursos)) } }

    val salvarMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        vm.exportar(TextoFinalViewModel.Formato.MARKDOWN, it, resolver)
    }
    val salvarTexto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        vm.exportar(TextoFinalViewModel.Formato.TEXTO, it, resolver)
    }
    // A página em uso e se ela já terminou de carregar: o PDF só existe depois disso.
    var pagina by remember { mutableStateOf<WebView?>(null) }
    var carregada by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        val texto = estado.textoFinal
        when {
            !estado.carregada -> Unit
            texto == null -> VazioDeResultado(stringResource(R.string.texto_nao_liberado), Modifier.testTag(Marcas.TEXTO_NAO_LIBERADO))
            else -> {
                Cabecalho(R.drawable.simbolo_check_circle, stringResource(R.string.texto_final), estado.titulo)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoFantasma(
                        stringResource(R.string.exportar_markdown),
                        aoClicar = { salvarMarkdown.launch(vm.nomeDoArquivo(TextoFinalViewModel.Formato.MARKDOWN)) },
                        icone = R.drawable.simbolo_save,
                        habilitado = !estado.exportando,
                        modifier = Modifier.testTag(Marcas.EXPORTAR_MARKDOWN),
                    )
                    BotaoFantasma(
                        stringResource(R.string.exportar_txt),
                        aoClicar = { salvarTexto.launch(vm.nomeDoArquivo(TextoFinalViewModel.Formato.TEXTO)) },
                        icone = R.drawable.simbolo_save,
                        habilitado = !estado.exportando,
                        modifier = Modifier.testTag(Marcas.EXPORTAR_TXT),
                    )
                    BotaoPrimario(
                        stringResource(R.string.exportar_pdf),
                        aoClicar = {
                            val atual = pagina
                            val impressao = atividade?.getSystemService(PrintManager::class.java)
                            if (atual != null && impressao != null) {
                                val nome = TextoFinalViewModel.nomeBase(estado.titulo)
                                impressao.print(nome, atual.createPrintDocumentAdapter(nome), PrintAttributes.Builder().build())
                            }
                        },
                        icone = R.drawable.simbolo_description,
                        habilitado = carregada && pagina != null,
                        modifier = Modifier.testTag(Marcas.EXPORTAR_PDF),
                    )
                }
                Cartao(modifier = Modifier.weight(1f)) {
                    PaginaDoTexto(
                        html = remember(texto) { RenderizadorDoTextoFinal.html(texto) },
                        aoCarregar = { carregada = true },
                        aoTrocar = { nova ->
                            pagina = nova
                            carregada = false
                        },
                    )
                }
            }
        }
    }
}

/**
 * O `WebView` travado. Se o processo de renderização morrer, a página é
 * descartada e outra nasce no lugar ([geracao]). A página só é destruída
 * quando sai da composição; a impressão acontece com a tela aberta, porque o
 * diálogo do sistema fica por cima dela, então o adaptador de impressão não
 * perde o `WebView` no meio do trabalho.
 */
@Composable
private fun PaginaDoTexto(html: String, aoCarregar: () -> Unit, aoTrocar: (WebView?) -> Unit) {
    var geracao by remember { mutableIntStateOf(0) }
    key(geracao, html) {
        AndroidView(
            factory = { contexto ->
                WebView(contexto).apply {
                    travar(this)
                    webViewClient = ClienteDaPagina(
                        aoTerminar = aoCarregar,
                        aoPerderOProcesso = {
                            aoTrocar(null)
                            geracao += 1
                        },
                    )
                    aoTrocar(this)
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier
                .fillMaxSize()
                .testTag(Marcas.TEXTO_FINAL_PAGINA),
        )
    }
}

/**
 * Tudo o que a página não pode fazer (emenda A5): script, arquivo, conteúdo,
 * rede (inclusive a imagem que um modelo tivesse posto no Markdown), cache,
 * armazenamento, geolocalização e janela nova.
 */
internal fun travar(pagina: WebView) {
    pagina.settings.apply {
        javaScriptEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        blockNetworkLoads = true
        blockNetworkImage = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        domStorageEnabled = false
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
    }
}

/** Nenhuma navegação sai da página: um link do texto é inerte. */
internal class ClienteDaPagina(
    private val aoTerminar: () -> Unit,
    private val aoPerderOProcesso: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

    override fun onPageFinished(view: WebView, url: String?) = aoTerminar()

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        aoPerderOProcesso()
        return true
    }
}
