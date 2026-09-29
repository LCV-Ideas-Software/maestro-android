package dev.lcv.maestro.ui

import dev.lcv.maestro.ui.textofinal.RenderizadorDoTextoFinal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O texto final para a tela e para o TXT (especificação, seção 4.4). */
class RenderizadorDoTextoFinalTest {

    @Test
    fun `titulos listas e enfases viram html dentro da pagina com a folha de estilo`() {
        val html = RenderizadorDoTextoFinal.html("# Título\n\nUm *texto* com **ênfase**.\n\n- um\n- dois\n")
        assertTrue(html.startsWith("<!doctype html><html lang=\"pt-BR\">"))
        assertTrue("<h1>Título</h1>" in html)
        assertTrue("<p>Um <em>texto</em> com <strong>ênfase</strong>.</p>" in html)
        assertTrue("<ul>\n<li>um</li>\n<li>dois</li>\n</ul>" in html)
        assertTrue("<style>" in html && html.endsWith("</body></html>"))
    }

    @Test
    fun `html cru no markdown sai escapado e nunca como marcacao`() {
        val html = RenderizadorDoTextoFinal.html("<script>alert(1)</script>\n\nTexto com <b>negrito</b> cru.\n")
        assertFalse("<script>" in html)
        assertFalse("<b>" in html)
        assertTrue("&lt;script&gt;alert(1)&lt;/script&gt;" in html)
        assertTrue("&lt;b&gt;negrito&lt;/b&gt;" in html)
    }

    @Test
    fun `url perigosa perde o destino e a politica de conteudo proibe o que vem de fora`() {
        val html = RenderizadorDoTextoFinal.html("[clique](javascript:alert(1)) e ![](https://exemplo.org/a.png)\n")
        assertFalse("javascript:" in html)
        assertTrue("default-src 'none'" in html)
    }

    @Test
    fun `o txt tira as marcas de titulo e enfase, mantem o marcador da lista e separa os blocos`() {
        assertEquals(
            "Título\n\nUm texto com ênfase.\n\n- um\n- dois",
            RenderizadorDoTextoFinal.texto("# Título\n\nUm *texto* com **ênfase**.\n\n- um\n- dois\n").trim(),
        )
    }
}
