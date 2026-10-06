/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.textofinal

import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
    val contexto = LocalContext.current
    val resolver = contexto.contentResolver
    val atividade = LocalActivity.current
    LaunchedEffect(vm) { vm.avisos.collect { avisos.mostrar(it.em(recursos)) } }
    LifecycleResumeEffect(vm) {
        vm.recarregar()
        onPauseOrDispose { }
    }

    val salvarMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        vm.exportar(TextoFinalViewModel.Formato.MARKDOWN, it, resolver)
    }
    val salvarTexto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        vm.exportar(TextoFinalViewModel.Formato.TEXTO, it, resolver)
    }
    // O adaptador de impressão da página em uso, e se ela já terminou de carregar: o PDF só existe depois
    // disso. A tela guarda só a função, e não o `WebView`, que nasce e é travado dentro da própria fábrica.
    var imprimir by remember { mutableStateOf<((String) -> PrintDocumentAdapter)?>(null) }
    var carregada by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        val texto = estado.textoFinal
        val falhaDeLeitura = estado.falhaDeLeitura
        when {
            !estado.carregada -> Unit
            // A sessão que nunca foi lida não é "sem texto liberado": o motivo fica no lugar (decisão 25 estendida, #80).
            falhaDeLeitura != null -> VazioDeResultado(stringResource(R.string.tela_sem_leitura, falhaDeLeitura), Modifier.testTag(Marcas.LEITURA_FALHOU))
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
                            val adaptador = imprimir
                            val impressao = atividade?.getSystemService(PrintManager::class.java)
                            if (adaptador != null && impressao != null) {
                                val nome = TextoFinalViewModel.nomeBase(estado.titulo)
                                impressao.print(nome, adaptador(nome), PrintAttributes.Builder().build())
                            }
                        },
                        icone = R.drawable.simbolo_description,
                        habilitado = carregada && imprimir != null,
                        modifier = Modifier.testTag(Marcas.EXPORTAR_PDF),
                    )
                    // Política de Conteúdo Gerado por IA do Google Play (seção 9): reportar conteúdo ofensivo sem
                    // sair do aplicativo; o relato vai por e-mail, pelo aplicativo de e-mail do aparelho.
                    val assunto = stringResource(R.string.reportar_assunto, vm.idDaSessao)
                    val corpo = stringResource(R.string.reportar_corpo, vm.idDaSessao, estado.titulo)
                    BotaoFantasma(
                        stringResource(R.string.acao_reportar_conteudo),
                        aoClicar = { vm.reportarConteudo(contexto, assunto, corpo) },
                        icone = R.drawable.simbolo_warning,
                        modifier = Modifier.testTag(Marcas.REPORTAR_CONTEUDO),
                    )
                }
                Cartao(modifier = Modifier.weight(1f)) {
                    PaginaDoTexto(
                        html = remember(texto) { RenderizadorDoTextoFinal.html(texto) },
                        aoCarregar = { carregada = true },
                        aoTrocar = { nova ->
                            imprimir = nova
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
private fun PaginaDoTexto(html: String, aoCarregar: () -> Unit, aoTrocar: (((String) -> PrintDocumentAdapter)?) -> Unit) {
    var geracao by remember { mutableIntStateOf(0) }
    key(geracao, html) {
        AndroidView(
            factory = { contexto ->
                val pagina = WebView(contexto)
                travar(pagina)
                pagina.webViewClient = ClienteDaPagina(
                    aoTerminar = aoCarregar,
                    aoPerderOProcesso = {
                        aoTrocar(null)
                        geracao += 1
                    },
                )
                aoTrocar { nome -> pagina.createPrintDocumentAdapter(nome) }
                pagina.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                pagina
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
 * armazenamento, geolocalização e janela nova. Cada ajuste é escrito direto
 * sobre o `getSettings()` da página, sem bloco de escopo: é assim que a
 * análise do CodeQL segue a página até o ajuste (alertas 9 a 12 na #78).
 * As duas suspensões são dos dois ajustes de URL de arquivo, obsoletos.
 */
@Suppress("DEPRECATION")
internal fun travar(pagina: WebView) {
    val ajustes = pagina.settings
    ajustes.javaScriptEnabled = false
    ajustes.allowFileAccess = false
    ajustes.allowContentAccess = false
    ajustes.blockNetworkLoads = true
    ajustes.blockNetworkImage = true
    ajustes.cacheMode = WebSettings.LOAD_NO_CACHE
    ajustes.domStorageEnabled = false
    ajustes.setGeolocationEnabled(false)
    ajustes.setSupportMultipleWindows(false)
    ajustes.allowFileAccessFromFileURLs = false
    ajustes.allowUniversalAccessFromFileURLs = false
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
