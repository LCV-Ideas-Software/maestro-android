package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `buildArtifactMarkdown`: o markdown derivado, byte a byte o do web, e os tetos da linha. */
class MarkdownDoArtefatoTest {

    @Test
    fun `markdown gerado e byte a byte o do web`() {
        val entrada = Fixtures.entrada(
            sessaoId = "android-1", ciclo = 2, turno = 5, agente = Provedor.CODEX, papel = "revision", status = "not_ready",
            titulo = "Título", texto = "Corpo.\n\nSegundo parágrafo.", relatorio = "{\"a\":1}",
            custoUsd = BigDecimal("0.1234565"), anteriorId = "artifact-x", modelo = "gpt-6-astra",
        )
        val esperado = listOf(
            "# Maestro AI Artifact - Título", "", "- Session: android-1", "- Cycle: 2", "- Turn: 5", "- Agent: Codex",
            "- Role: revision", "- Status: not_ready", "- Model: gpt-6-astra", "- Cost USD: 0.123457",
            "- Previous artifact: artifact-x", "- Invalid links: 0", "", "## Revision Report", "", "{\"a\":1}", "",
            "## Link Audit", "", "```json", "[]", "```", "", "## Current Text", "", "Corpo.\n\nSegundo parágrafo.", "",
        ).joinToString("\n")
        assertEquals(esperado, MarkdownDoArtefato.montar(entrada))
    }

    @Test
    fun `campos vazios caem em unknown none e chaves vazias`() {
        val markdown = MarkdownDoArtefato.montar(Fixtures.entrada(relatorio = "", modelo = "", anteriorId = ""))
        assertTrue("- Model: unknown\n" in markdown)
        assertTrue("- Previous artifact: none\n" in markdown)
        assertTrue("- Cost USD: 0.000000\n" in markdown)
        assertTrue("## Revision Report\n\n{}\n" in markdown)
    }

    @Test
    fun `auditoria entra como as linhas do motor e conta so os tons de erro e bloqueio`() {
        val linhas = listOf(Fixtures.linha("a", tom = "ok"), Fixtures.linha("b", tom = "error"), Fixtures.linha("c", tom = "blocked"), Fixtures.linha("d", tom = "warning"))
        val markdown = MarkdownDoArtefato.montar(Fixtures.entrada(auditoria = linhas))
        assertTrue("- Invalid links: 2\n" in markdown)
        assertTrue("\"link_id\": \"a\"" in markdown)
        assertEquals(2, MarkdownDoArtefato.contarInvalidos(linhas))
    }

    @Test
    fun `markdown acima do teto e recusado, nunca cortado`() {
        val normal = MarkdownDoArtefato.montar(Fixtures.entrada(texto = "Texto."))
        assertEquals(normal.dropLast(1), MarkdownDoArtefato.conferir(normal))
        val grande = assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferir("😀".repeat(MarkdownDoArtefato.MAX_PONTOS_DE_CODIGO + 1)) }
        assertEquals("Artifact markdown exceeds 500000 code points.", grande.message)
        assertEquals(MarkdownDoArtefato.MAX_PONTOS_DE_CODIGO * 2, MarkdownDoArtefato.conferir("😀".repeat(MarkdownDoArtefato.MAX_PONTOS_DE_CODIGO)).length)
    }

    @Test
    fun `a linha guarda o texto aceito canonico e o markdown e renderizado dela`() {
        val entrada = Fixtures.entrada(texto = "﻿Linha 1\r\nLinha 2\n", relatorio = "{\"a\":1}", custoUsd = BigDecimal("0.5"), modelo = "m", anteriorId = "artifact-x")
        val artefato = Fixtures.artefato("a", entrada)
        assertEquals("Linha 1\nLinha 2", artefato.textoAceito)
        val markdown = MarkdownDoArtefato.doArtefato(artefato)
        assertEquals(MarkdownDoArtefato.montar(entrada.copy(texto = "Linha 1\nLinha 2")).dropLast(1), markdown)
        assertTrue(markdown.endsWith("## Current Text\n\nLinha 1\nLinha 2"))
    }

    @Test
    fun `o texto aceito acima de meio mebibyte e recusado`() {
        assertEquals(MarkdownDoArtefato.MAX_BYTES_DA_LINHA / 2, MarkdownDoArtefato.MAX_BYTES_DO_TEXTO)
        MarkdownDoArtefato.conferirTexto("a".repeat(MarkdownDoArtefato.MAX_BYTES_DO_TEXTO))
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferirTexto("a".repeat(MarkdownDoArtefato.MAX_BYTES_DO_TEXTO + 1)) }
        assertEquals("Accepted text exceeds 524288 bytes.", erro.message)
        assertEquals("Accepted text is empty.", assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferirTexto("") }.message)
        // Bytes, não pontos de código: 200 000 emojis são 800 KB.
        assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferirTexto("😀".repeat(200_000)) }
    }

    @Test
    fun `a linha acima de um mebibyte e recusada`() {
        MarkdownDoArtefato.conferirLinha("a".repeat(MarkdownDoArtefato.MAX_BYTES_DA_LINHA - 4), "{}", "[]")
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferirLinha("a".repeat(MarkdownDoArtefato.MAX_BYTES_DA_LINHA - 3), "{}", "[]") }
        assertEquals("Artifact row exceeds 1048576 bytes.", erro.message)
        // Bytes, não pontos de código: 300 000 emojis são 1,2 MB.
        assertFailsWith<IntegridadeDeLinks.Falha> { MarkdownDoArtefato.conferirLinha("😀".repeat(300_000), "{}", "[]") }
    }
}
