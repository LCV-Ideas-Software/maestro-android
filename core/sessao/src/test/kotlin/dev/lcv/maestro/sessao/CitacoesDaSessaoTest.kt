package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.AuditoriaAbnt
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O contexto de citações da sessão e o bloco que vai ao prompt (`session_orchestration.rs:338-372`). */
class CitacoesDaSessaoTest {

    private val protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO
    private val hash = FormatoDoRegistro.sha256(protocolo)

    private fun anexo(nome: String, conteudo: String) = ManifestosDosAnexos.Anexo(nome, "application/json") { conteudo.toByteArray() }

    @Test
    fun `sem anexo o manifesto e o vazio preso ao hash do protocolo`() {
        val lidas = CitacoesDaSessao.de(emptyList(), protocolo) as Citacoes.Lidas

        assertEquals(hash, lidas.contexto.hashDoProtocolo)
        assertEquals(AuditoriaAbnt.manifestoVazio(hash), lidas.contexto.manifesto)
        assertNull(lidas.contexto.manifestoAnterior)
        assertContains(lidas.bloco, "vazio inicializado pelo Maestro porque nenhum manifesto foi anexado com 0 citacao(oes) e 0 fonte(s)")
    }

    @Test
    fun `o hash e o sha256 em 64 hexadecimais do texto do protocolo`() {
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(FormatoDoRegistro.sha256(protocolo + " ") != hash)
    }

    @Test
    fun `manifesto anexado entra como anexado pelo operador com as contagens`() {
        val manifesto = """{"schema_version":"citation_manifest.v1","protocol_hash":"$hash","citations":[],"sources":[]}"""
        val lidas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", manifesto)), protocolo) as Citacoes.Lidas

        assertEquals(hash, lidas.contexto.manifesto!!.hashDoProtocolo)
        assertContains(lidas.bloco, "anexado pelo operador com 0 citacao(oes) e 0 fonte(s)")
    }

    @Test
    fun `manifesto ilegivel recusa a sessao antes de qualquer chamada`() {
        val recusadas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", "{ isto nao e json")), protocolo)

        assertTrue(recusadas is Citacoes.Recusadas)
    }
}
