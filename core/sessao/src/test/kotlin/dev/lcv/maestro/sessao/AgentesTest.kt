package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `sanitizeAgent`, `sanitizeAgents`, `sanitizeText` e o `trim` do JavaScript. */
class AgentesTest {

    @Test
    fun `apelidos do web e o padrao para o resto`() {
        assertEquals(Provedor.CLAUDE, Agentes.sanear("anthropic", Provedor.GROK))
        assertEquals(Provedor.CODEX, Agentes.sanear(" ChatGPT ", Provedor.GROK))
        assertEquals(Provedor.CODEX, Agentes.sanear("openai", Provedor.GROK))
        assertEquals(Provedor.GEMINI, Agentes.sanear("antigravity", Provedor.GROK))
        assertEquals(Provedor.GEMINI, Agentes.sanear("agy", Provedor.GROK))
        assertEquals(Provedor.GEMINI, Agentes.sanear("google", Provedor.GROK))
        assertEquals(Provedor.GROK, Agentes.sanear("xai", Provedor.CLAUDE))
        assertEquals(Provedor.GROK, Agentes.sanear("grok-api", Provedor.CLAUDE))
        assertEquals(Provedor.PERPLEXITY, Agentes.sanear("sonar", Provedor.CLAUDE))
        assertEquals(Provedor.PERPLEXITY, Agentes.sanear("perplexity-api", Provedor.CLAUDE))
        assertEquals(Provedor.DEEPSEEK, Agentes.sanear("deepseek-api", Provedor.CLAUDE))
        assertEquals(Provedor.GROK, Agentes.sanear("bing", Provedor.GROK))
        assertEquals(Provedor.GROK, Agentes.sanear(null, Provedor.GROK))
        assertNull(Agentes.porChave("anthropic"))
        assertEquals(Provedor.CLAUDE, Agentes.porChave("claude"))
        assertEquals("DeepSeek", Provedor.DEEPSEEK.rotulo)
    }

    @Test
    fun `lista saneada tira repeticao, poe o inicial na frente e para em seis`() {
        assertEquals(
            listOf(Provedor.CODEX, Provedor.CLAUDE, Provedor.GEMINI),
            Agentes.sanearLista(listOf("codex", "openai", "anthropic", "google"), Provedor.GEMINI),
        )
        assertEquals(
            listOf(Provedor.GROK, Provedor.CODEX, Provedor.CLAUDE),
            Agentes.sanearLista(listOf("codex", "anthropic"), Provedor.GROK),
        )
        assertEquals(Provedor.entries.toList(), Agentes.sanearLista(null, Provedor.CLAUDE))
        assertEquals(Provedor.entries.toList(), Agentes.sanearLista(Provedor.entries.map { it.agente } + listOf("xai", "bing"), Provedor.CLAUDE))
    }

    @Test
    fun `sanear texto tira o nulo, apara como o javascript e corta em pontos de codigo`() {
        assertEquals("a b", Texto.sanear("﻿ a\u0000 b \n"))
        assertEquals("😀😀", Texto.sanear("😀😀😀", 2))
        assertEquals("", Texto.sanear(null))
    }

    @Test
    fun `sanear em bytes conta utf-8, nunca parte um ponto de codigo e apara como o sanear`() {
        assertEquals("a b", Texto.sanearEmBytes("﻿ a\u0000 b \n", 10))
        // a (1 byte) e é (2) somam 3; o 😀 (4) passaria de 5.
        assertEquals("aé", Texto.sanearEmBytes("aé😀", 5))
        assertEquals("aé😀", Texto.sanearEmBytes("aé😀", 7))
        assertEquals("", Texto.sanearEmBytes(null, 10))
    }

    @Test
    fun `o protocolo de 181508 caracteres cabe inteiro e o pior caso continua em 640000 bytes`() {
        val protocolo = "Revisão editorial: coesão, ênfase e coerência. ".repeat(4_000).take(181_507) + "."
        assertEquals(181_508, protocolo.length)
        assertEquals(protocolo, Texto.sanearEmBytes(protocolo, RepositorioDeConfiguracoes.MAX_BYTES_DO_PROTOCOLO))
        // O corte antigo, em 160 000 pontos de código, perdia o fim dele (MAEANDR-39).
        assertTrue(Texto.sanear(protocolo, 160_000).length < protocolo.length)
        // Só caracteres de 4 bytes: o mesmo pior caso de antes, 160 000 pontos de código.
        val piorCaso = Texto.sanearEmBytes("😀".repeat(170_000), RepositorioDeConfiguracoes.MAX_BYTES_DO_PROTOCOLO)
        assertEquals(160_000, piorCaso.codePointCount(0, piorCaso.length))
        assertEquals(640_000, piorCaso.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `trim do javascript inclui feff e nao inclui nel`() {
        assertEquals("x", TrimJs.aparar("﻿ x 　"))
        assertEquals("\u0085x\u0085", TrimJs.aparar(" \u0085x\u0085 "))
        assertEquals("", TrimJs.aparar("\t\n\u000B\u000C\r "))
    }
}
