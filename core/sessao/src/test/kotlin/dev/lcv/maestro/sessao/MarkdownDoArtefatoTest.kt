package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `buildArtifactMarkdown`: o markdown derivado, byte a byte o do web. */
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
    fun `a linha do artefato guarda o texto aceito canonico ao lado do markdown derivado`() {
        val artefato = Fixtures.artefato("a", Fixtures.entrada(texto = "﻿Linha 1\r\nLinha 2\n"))
        assertEquals("Linha 1\nLinha 2", artefato.textoAceito)
        assertTrue(artefato.conteudoMd.endsWith("## Current Text\n\nLinha 1\nLinha 2"))
    }
}
