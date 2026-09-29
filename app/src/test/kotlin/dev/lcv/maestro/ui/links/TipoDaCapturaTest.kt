package dev.lcv.maestro.ui.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `captureMediaType` do desktop (`EvidenceScreen.tsx:127-133`), com o tipo desconhecido do Android contando como sem tipo. */
class TipoDaCapturaTest {

    @Test
    fun `o tipo informado decide, em minusculas, e so passa o da lista`() {
        assertEquals("application/pdf", LinksViewModel.tipoDaCaptura("salvo.txt", "Application/PDF"))
        assertEquals("text/html", LinksViewModel.tipoDaCaptura("sem-extensao", "text/html"))
        // Informado e fora da lista: recusado, mesmo com extensão aceita — como no desktop.
        assertNull(LinksViewModel.tipoDaCaptura("dados.json", "application/json"))
        assertNull(LinksViewModel.tipoDaCaptura("pagina.html", "text/x-markdown"))
    }

    @Test
    fun `sem tipo, ou com o tipo desconhecido, a extensao decide`() {
        assertEquals("text/markdown", LinksViewModel.tipoDaCaptura("Notas.MD", null))
        assertEquals("text/markdown", LinksViewModel.tipoDaCaptura("notas.markdown", "application/octet-stream"))
        assertEquals("text/html", LinksViewModel.tipoDaCaptura("pagina.htm", ""))
        assertEquals("image/jpeg", LinksViewModel.tipoDaCaptura("foto.jpeg", null))
        assertEquals("text/plain", LinksViewModel.tipoDaCaptura("texto.txt", null))
        assertNull(LinksViewModel.tipoDaCaptura("arquivo.docx", null))
        assertNull(LinksViewModel.tipoDaCaptura("sem-extensao", "application/octet-stream"))
    }
}
