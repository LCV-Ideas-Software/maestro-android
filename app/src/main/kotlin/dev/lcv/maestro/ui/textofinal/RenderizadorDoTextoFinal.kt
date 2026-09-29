/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.textofinal

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.renderer.text.LineBreakRendering
import org.commonmark.renderer.text.TextContentRenderer

/**
 * O texto final liberado, para a tela e para o TXT (especificação, seção 4.4).
 * O parser é o da auditoria (`AuditoriaAbnt`: a CommonMark inteira, sem
 * extensão), então o que a auditoria aprovou é exatamente o que a tela mostra.
 *
 * O HTML sai com `escapeHtml(true)` e `sanitizeUrls(true)`: HTML cru no texto
 * final é recusado pela auditoria (`raw_html_in_final_text`) e não chega aqui;
 * se chegasse, seria mostrado como texto, não como marcação. A página leva uma
 * política de conteúdo que não deixa carregar nada de fora nem rodar script,
 * por cima do `WebView` que já bloqueia os dois.
 */
object RenderizadorDoTextoFinal {

    private val LEITOR: Parser = Parser.builder().build()
    private val HTML: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build()
    private val TEXTO: TextContentRenderer = TextContentRenderer.builder().lineBreakRendering(LineBreakRendering.SEPARATE_BLOCKS).build()

    /** A página inteira que o `WebView` carrega: o corpo do Markdown e a folha de estilo do aplicativo. */
    fun html(markdown: String): String = INICIO + HTML.render(LEITOR.parse(markdown)) + FIM

    /** O TXT da exportação: títulos e ênfases sem as marcas, e as listas com o marcador que o renderizador oficial escreve (`- `). */
    fun texto(markdown: String): String = TEXTO.render(LEITOR.parse(markdown))

    // As cores e medidas são as do tema (`Tema.kt`): texto #202124, texto fraco #64748b, acento #1a73e8.
    private const val INICIO = "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>" +
        "html,body{margin:0;padding:0;background:#ffffff}" +
        "body{font-family:sans-serif;font-size:16px;line-height:1.6;color:#202124;overflow-wrap:break-word}" +
        "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.2em 0 .5em}h1{font-size:1.6em}h2{font-size:1.35em}h3{font-size:1.15em}" +
        "p,ul,ol,blockquote,pre{margin:0 0 1em}ul,ol{padding-left:1.4em}" +
        "blockquote{padding:0 1em;border-left:3px solid rgba(26,115,232,.4);color:#64748b}" +
        "code,pre{font-family:monospace;font-size:.9em}code{background:#f1f3f4;padding:.1em .3em;border-radius:4px}" +
        "pre{background:#f1f3f4;padding:12px;border-radius:8px;overflow-x:auto}pre code{padding:0;background:none}" +
        "a{color:#1a73e8}img{max-width:100%}hr{border:0;border-top:1px solid rgba(0,0,0,.12);margin:1.5em 0}" +
        "</style></head><body>"
    private const val FIM = "</body></html>"
}
