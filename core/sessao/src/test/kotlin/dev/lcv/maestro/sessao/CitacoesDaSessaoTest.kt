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
    fun `manifesto de outro protocolo recusa a sessao antes do rascunho e diz o hash ativo`() {
        // Achado do Codex na #78: a auditoria só descobria isso na primeira revisão, com o rascunho já pago.
        val outro = FormatoDoRegistro.sha256("outro protocolo")
        val manifesto = """{"schema_version":"citation_manifest.v1","protocol_hash":"$outro","citations":[],"sources":[]}"""
        val recusadas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", manifesto)), protocolo) as Citacoes.Recusadas

        assertEquals("O manifesto nao esta vinculado ao hash do protocolo ativo. Hash do protocolo ativo: $hash", recusadas.motivo)
    }

    @Test
    fun `manifesto sem hash recusa a sessao com as duas regras da auditoria`() {
        val manifesto = """{"schema_version":"citation_manifest.v1","protocol_hash":"","citations":[],"sources":[]}"""
        val recusadas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", manifesto)), protocolo) as Citacoes.Recusadas

        assertEquals(
            "O manifesto nao esta vinculado ao hash do protocolo ativo. O manifesto nao registra o hash do protocolo editorial " +
                "ativo. Hash do protocolo ativo: $hash",
            recusadas.motivo,
        )
    }

    @Test
    fun `manifesto acima da capacidade recusa a sessao sem falar de hash`() {
        val citacao = """{"schema_version":"citation.v1","claim_id":"claim-%d","citation_type":"direct_quote","author_display":"Silva, Maria",""" +
            """"author_key":"SILVA","year":"2026","locator":"p. 12","source_id":"source-001","source_access":"full_document_opened",""" +
            """"verification_status":"verified","risk_if_wrong":"medium","original_text":"Trecho %d."}"""
        val citacoes = (1..501).joinToString(",") { citacao.format(it, it) }
        val manifesto = """{"schema_version":"citation_manifest.v1","protocol_hash":"$hash","citations":[$citacoes],"sources":[]}"""
        val recusadas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", manifesto)), protocolo) as Citacoes.Recusadas

        assertEquals("O manifesto excede o limite seguro de citacoes ou fontes", recusadas.motivo)
    }

    @Test
    fun `manifesto ilegivel recusa a sessao antes de qualquer chamada`() {
        val recusadas = CitacoesDaSessao.de(listOf(anexo("citation-manifest.json", "{ isto nao e json")), protocolo)

        assertTrue(recusadas is Citacoes.Recusadas)
    }
}
