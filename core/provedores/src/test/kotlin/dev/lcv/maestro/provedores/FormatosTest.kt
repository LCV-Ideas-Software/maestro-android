package dev.lcv.maestro.provedores

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/**
 * O contrato de cada provedor, contra um servidor falso: o corpo enviado
 * conforme a seção 5.1 e a decisão de 23/09/2026 sobre o raciocínio, e a
 * leitura da resposta. Nenhum teste fala com provedor real, e a chave de teste
 * não tem forma de chave real.
 */
class FormatosTest {

    private val servidor = MockWebServer()
    private val chave = "chave-de-teste"

    @BeforeTest fun subir() = servidor.start()

    @AfterTest fun descer() = servidor.close()

    private fun cliente() = ClienteDeProvedores(
        OkHttpClient(),
        { LeituraDaChave.Presente(chave) },
        { servidor.url("/${it.agente}").toString() },
        { },
        { Instant.EPOCH },
    )

    private fun responder(corpo: String) =
        servidor.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(corpo).build())

    private val pedido = Pedido(sistema = "Papel do agente.", prompt = "Texto do turno.")

    private fun chamarERegistrar(provedor: Provedor, resposta: String): Pair<Resultado, Enviado> {
        responder(resposta)
        val resultado = runBlocking { cliente().chamar(provedor, pedido) }
        val requisicao = servidor.takeRequest()
        val texto = requisicao.body!!.utf8()
        return resultado to Enviado(requisicao.url.encodedPath, requisicao.headers.toMap(), texto, Json.LEITOR.readTree(texto))
    }

    private data class Enviado(val caminho: String, val cabecalhos: Map<String, String>, val texto: String, val json: JsonNode)

    private fun okhttp3.Headers.toMap() = names().associateWith { get(it)!! }

    // -- Corpo enviado -----------------------------------------------------------

    @Test
    fun `store false vai nos quatro que o expoem e fica fora dos dois que nao o tem`() {
        val comStore = setOf(Provedor.CODEX, Provedor.GEMINI, Provedor.GROK, Provedor.PERPLEXITY)
        for (provedor in Provedor.entries) {
            val (_, enviado) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            if (provedor in comStore) {
                assertTrue(enviado.json.path("store").isBoolean && !enviado.json.path("store").booleanValue(), "$provedor")
            } else {
                assertFalse(enviado.json.has("store"), "$provedor não declara store e não pode recebê-lo")
            }
        }
    }

    @Test
    fun `a chave vai so no cabecalho do provedor`() {
        for (provedor in Provedor.entries) {
            val (_, enviado) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            assertFalse(enviado.texto.contains(chave), "$provedor pôs a chave no corpo")
            val cabecalho = when (provedor) {
                Provedor.CLAUDE -> enviado.cabecalhos["x-api-key"]
                Provedor.GEMINI -> enviado.cabecalhos["x-goog-api-key"]
                else -> enviado.cabecalhos["Authorization"]?.removePrefix("Bearer ")
            }
            assertEquals(chave, cabecalho, "$provedor")
            assertEquals("/${provedor.agente}", enviado.caminho)
        }
    }

    @Test
    fun `modelo e raciocinio no maximo de cada provedor`() {
        val esperado = mapOf(
            Provedor.CLAUDE to listOf("/output_config/effort" to "max", "/thinking/type" to "adaptive"),
            Provedor.CODEX to listOf("/reasoning/effort" to "max"),
            Provedor.GEMINI to listOf("/generation_config/thinking_level" to "high"),
            Provedor.DEEPSEEK to listOf("/thinking/type" to "enabled", "/thinking/reasoning_effort" to "max"),
            Provedor.GROK to listOf("/reasoning/effort" to "xhigh"),
            Provedor.PERPLEXITY to listOf("/preset" to "xhigh"),
        )
        for (provedor in Provedor.entries) {
            val (_, enviado) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            assertEquals(provedor.modelo, enviado.json.path("model").textValue(), "$provedor")
            for ((caminho, valor) in esperado.getValue(provedor)) {
                assertEquals(valor, enviado.json.at(caminho).textValue(), "$provedor $caminho")
            }
        }
    }

    @Test
    fun `campos que a secao 5 2 proibe nao sao enviados`() {
        val (_, claude) = chamarERegistrar(Provedor.CLAUDE, RESPOSTAS.getValue(Provedor.CLAUDE))
        assertTrue(claude.json.at("/thinking/budget_tokens").isMissingNode)
        val (_, gemini) = chamarERegistrar(Provedor.GEMINI, RESPOSTAS.getValue(Provedor.GEMINI))
        assertTrue(gemini.json.at("/generation_config/thinking_budget").isMissingNode)
        assertFalse(gemini.json.has("contents"), "a Interactions API usa input, não contents")
    }

    @Test
    fun `teto de saida de 64 mil no campo de cada provedor`() {
        val campo = mapOf(
            Provedor.CLAUDE to "/max_tokens",
            Provedor.CODEX to "/max_output_tokens",
            Provedor.GEMINI to "/generation_config/max_output_tokens",
            Provedor.DEEPSEEK to "/max_tokens",
            Provedor.GROK to "/max_output_tokens",
            Provedor.PERPLEXITY to "/max_output_tokens",
        )
        for (provedor in Provedor.entries) {
            val (_, enviado) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            assertEquals(64_000, enviado.json.at(campo.getValue(provedor)).intValue(), "$provedor")
        }
    }

    @Test
    fun `o endereco do Gemini e v1beta, nao v1beta2`() {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", Provedor.GEMINI.endereco)
    }

    // -- Leitura da resposta -----------------------------------------------------

    @Test
    fun `so o texto final e lido, nunca o raciocinio`() {
        for (provedor in Provedor.entries) {
            val (resultado, _) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            val concluida = resultado as? Resultado.Concluida ?: error("$provedor: $resultado")
            assertEquals("Resposta final.", concluida.texto, "$provedor")
        }
    }

    @Test
    fun `uso de tokens de cada provedor, com o raciocinio na saida`() {
        val esperado = mapOf(
            Provedor.CLAUDE to Uso(120, 50),
            Provedor.CODEX to Uso(100, 50),
            // total_output_tokens 30 + total_thought_tokens 20.
            Provedor.GEMINI to Uso(100, 50),
            Provedor.DEEPSEEK to Uso(100, 50),
            Provedor.GROK to Uso(100, 50),
            Provedor.PERPLEXITY to Uso(100, 50, BigDecimal("0.1")),
        )
        for (provedor in Provedor.entries) {
            val (resultado, _) = chamarERegistrar(provedor, RESPOSTAS.getValue(provedor))
            assertEquals(esperado.getValue(provedor), (resultado as Resultado.Concluida).uso, "$provedor")
        }
    }

    @Test
    fun `custo informado pela Perplexity chega exato e so em dolar`() {
        // Mais dígitos do que um double guarda: lido como double, viraria
        // 0.12345678901234568.
        val (emDolar, _) = chamarERegistrar(
            Provedor.PERPLEXITY,
            RESPOSTAS.getValue(Provedor.PERPLEXITY).replace("0.1", "0.1234567890123456789"),
        )
        assertEquals(BigDecimal("0.1234567890123456789"), (emDolar as Resultado.Concluida).uso.custoInformadoUsd)

        val (emReal, _) = chamarERegistrar(
            Provedor.PERPLEXITY,
            RESPOSTAS.getValue(Provedor.PERPLEXITY).replace("\"USD\"", "\"BRL\""),
        )
        assertEquals(null, (emReal as Resultado.Concluida).uso.custoInformadoUsd)
    }

    @Test
    fun `Gemini com so tokens de raciocinio nao perde o raciocinio`() {
        val (resultado, _) = chamarERegistrar(
            Provedor.GEMINI,
            """{"status":"incomplete","steps":[],"usage":{"total_input_tokens":10,"total_thought_tokens":64000}}""",
        )
        assertEquals(Uso(10, 64_000), (resultado as Resultado.Incompleta).uso)
    }

    @Test
    fun `resposta sem uso devolve uso vazio`() {
        val (resultado, _) = chamarERegistrar(
            Provedor.CODEX,
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Ok."}]}]}""",
        )
        assertEquals(Resultado.Concluida("Ok.", Uso(null, null)), resultado)
    }

    @Test
    fun `resposta truncada ou recusada e incompleta e carrega o uso`() {
        val truncadas = mapOf(
            Provedor.CLAUDE to """{"content":[{"type":"text","text":"Meio"}],"stop_reason":"max_tokens","usage":{"input_tokens":10,"output_tokens":64000}}""",
            Provedor.CODEX to """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[],"usage":{"input_tokens":10,"output_tokens":64000}}""",
            Provedor.GEMINI to """{"status":"incomplete","steps":[],"usage":{"total_input_tokens":10,"total_output_tokens":0,"total_thought_tokens":64000}}""",
            Provedor.DEEPSEEK to """{"choices":[{"finish_reason":"length","message":{"content":"Meio"}}],"usage":{"prompt_tokens":10,"completion_tokens":64000}}""",
            Provedor.GROK to """{"status":"incomplete","output":[],"usage":{"input_tokens":10,"output_tokens":64000}}""",
            Provedor.PERPLEXITY to """{"status":"failed","output":[],"usage":{"input_tokens":10,"output_tokens":64000}}""",
        )
        for ((provedor, corpo) in truncadas) {
            val (resultado, _) = chamarERegistrar(provedor, corpo)
            val incompleta = resultado as? Resultado.Incompleta ?: error("$provedor: $resultado")
            assertEquals(Uso(10, 64_000), incompleta.uso, "$provedor")
        }
    }

    @Test
    fun `motivo da resposta incompleta diz o que o provedor disse`() {
        val (resultado, _) = chamarERegistrar(
            Provedor.CODEX,
            """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}""",
        )
        assertEquals("status: incomplete, reason: max_output_tokens", (resultado as Resultado.Incompleta).motivo)
    }

    @Test
    fun `motivo vindo do provedor e saneado antes de ir ao jornal, nos seis`() {
        // Quebra de linha escapada forjaria linha no jornal; texto longo o
        // encheria. Vale para todo campo do provedor que chega ao motivo.
        val hostil = "x\\ny" + "a".repeat(400)
        val incompletas = mapOf(
            Provedor.CLAUDE to """{"content":[],"stop_reason":"$hostil"}""",
            Provedor.CODEX to """{"status":"incomplete","incomplete_details":{"reason":"$hostil"},"output":[]}""",
            Provedor.GEMINI to """{"status":"$hostil","steps":[]}""",
            Provedor.DEEPSEEK to """{"choices":[{"finish_reason":"$hostil","message":{"content":""}}]}""",
            Provedor.GROK to """{"status":"$hostil","output":[]}""",
            Provedor.PERPLEXITY to """{"status":"$hostil","output":[]}""",
        )
        val recusa = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"$hostil"}]}]}"""
        for ((provedor, corpo) in incompletas.entries.map { it.key to it.value } + (Provedor.CODEX to recusa)) {
            val (resultado, _) = chamarERegistrar(provedor, corpo)
            val motivo = (resultado as? Resultado.Incompleta ?: error("$provedor: $resultado")).motivo
            assertFalse(motivo.any { Character.isISOControl(it) }, "$provedor: $motivo")
            assertTrue(motivo.codePointCount(0, motivo.length) <= 180, "$provedor: ${motivo.length}")
        }
    }

    @Test
    fun `recusa na Responses API nao passa por resposta concluida`() {
        val (resultado, _) = chamarERegistrar(
            Provedor.CODEX,
            """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"Nao posso ajudar."}]}]}""",
        )
        assertContains((resultado as Resultado.Incompleta).motivo, "refusal")
    }

    @Test
    fun `objeto 2xx sem os campos do contrato e resposta invalida`() {
        val semEstrutura = mapOf(
            Provedor.CLAUDE to """{"stop_reason":"end_turn"}""",
            Provedor.CODEX to """{"status":"completed"}""",
            Provedor.GEMINI to """{"status":"completed"}""",
            Provedor.DEEPSEEK to """{"choices":[]}""",
            Provedor.GROK to """{"output":[]}""",
            Provedor.PERPLEXITY to """{"status":"completed"}""",
        )
        for ((provedor, corpo) in semEstrutura) {
            val (resultado, _) = chamarERegistrar(provedor, corpo)
            assertIs<Resultado.RespostaInvalida>(resultado, "$provedor")
        }
    }

    @Test
    fun `resposta concluida sem texto nao e resposta`() {
        val (resultado, _) = chamarERegistrar(Provedor.CODEX, """{"status":"completed","output":[]}""")
        assertIs<Resultado.Incompleta>(resultado)
    }

    @Test
    fun `recusa do Claude nao passa por resposta concluida`() {
        val (resultado, _) = chamarERegistrar(
            Provedor.CLAUDE,
            """{"content":[{"type":"text","text":"Nao posso."}],"stop_reason":"refusal"}""",
        )
        assertEquals("stop_reason: refusal", (resultado as Resultado.Incompleta).motivo)
    }

    private companion object {
        /** Respostas concluídas, cada uma com um bloco de raciocínio antes do texto. */
        val RESPOSTAS = mapOf(
            Provedor.CLAUDE to """
                {"content":[{"type":"thinking","thinking":"raciocinio secreto"},{"type":"text","text":"Resposta final."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":100,"output_tokens":50,"cache_read_input_tokens":20}}
            """.trimIndent(),
            Provedor.CODEX to RESPONSES,
            Provedor.GROK to RESPONSES,
            Provedor.GEMINI to """
                {"status":"completed","steps":[{"type":"thought","content":[{"type":"text","text":"raciocinio secreto"}]},
                 {"type":"model_output","content":[{"type":"text","text":"Resposta final."}]}],
                 "usage":{"total_input_tokens":100,"total_output_tokens":30,"total_thought_tokens":20}}
            """.trimIndent(),
            Provedor.DEEPSEEK to """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","reasoning_content":"raciocinio secreto",
                 "content":"Resposta final."}}],"usage":{"prompt_tokens":100,"completion_tokens":50}}
            """.trimIndent(),
            Provedor.PERPLEXITY to """
                {"status":"completed","output":[{"type":"search_results","results":[]},
                 {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Resposta final."}]}],
                 "usage":{"input_tokens":100,"output_tokens":50,"cost":{"currency":"USD","total_cost":0.1}}}
            """.trimIndent(),
        )

        private val RESPONSES: String
            get() = """
                {"status":"completed","output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"raciocinio secreto"}]},
                 {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Resposta final."}]}],
                 "usage":{"input_tokens":100,"output_tokens":50}}
            """.trimIndent()
    }
}
