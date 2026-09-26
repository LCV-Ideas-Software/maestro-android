package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `buildArtifactMarkdown` e `artifactMatchesCurrentText`, testados em par. */
class MarkdownDoArtefatoTest {

    @Test
    fun `markdown gerado e byte a byte o do web`() {
        val entrada = Fixtures.entrada(
            sessaoId = "android-1", ciclo = 2, turno = 5, agente = Provedor.CODEX, papel = "revision", status = "not_ready",
            titulo = "Título", conteudoMd = "Corpo.\n\nSegundo parágrafo.", relatorio = "{\"a\":1}",
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
    fun `texto atual volta inteiro mesmo quando o artigo e o relatorio contem o titulo Current Text`() {
        val texto = "Introdução.\n\n## Current Text\n\nA seção do artigo com esse nome.\n\nFim."
        // O relatório vem antes do bloco de auditoria; quem procurar o primeiro
        // `## Current Text` do arquivo pega o do relatório, não o gerado.
        val relatorio = "{\n  \"note\": \"veja\n## Current Text\n\nnão é este\"\n}"
        val artefato = Fixtures.artefato("a", Fixtures.entrada(conteudoMd = texto, relatorio = relatorio))
        assertEquals(texto, MarkdownDoArtefato.textoAtual(artefato.conteudoMd))
        assertTrue(MarkdownDoArtefato.casaCom(artefato, texto))
    }

    @Test
    fun `falha fechado quando o texto da sessao e so a subsecao final do artefato`() {
        val artefato = Fixtures.artefato("a", Fixtures.entrada(conteudoMd = "Parte A.\n\n## Current Text\n\nParte B."))
        assertFalse(MarkdownDoArtefato.casaCom(artefato, "Parte B."))
    }

    @Test
    fun `texto vazio nunca casa, nem com artefato vazio`() {
        val vazio = Fixtures.artefato("a", Fixtures.entrada(conteudoMd = ""))
        assertEquals("", MarkdownDoArtefato.textoAtual(vazio.conteudoMd))
        assertFalse(MarkdownDoArtefato.casaCom(vazio, ""))
        assertFalse(MarkdownDoArtefato.casaCom(vazio, " \n "))
    }

    @Test
    fun `sem bloco de auditoria usa o delimitador legado`() {
        val legado = "# Maestro AI Artifact - x\n\n## Current Text\n\nTexto legado.\n"
        assertEquals("Texto legado.", MarkdownDoArtefato.textoAtual(legado))
        assertNull(MarkdownDoArtefato.textoAtual("# nada aqui\n"))
    }

    @Test
    fun `crlf e aparado como no javascript`() {
        val artefato = Fixtures.artefato("a", Fixtures.entrada(conteudoMd = "Linha 1\nLinha 2"))
        assertTrue(MarkdownDoArtefato.casaCom(artefato, "﻿Linha 1\r\nLinha 2 "))
        assertFalse(MarkdownDoArtefato.casaCom(artefato, "   "))
    }
}
