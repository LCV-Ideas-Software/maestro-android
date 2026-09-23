package dev.lcv.maestro.provedores

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

internal object Json {
    /**
     * `USE_BIG_DECIMAL_FOR_FLOATS`: o custo que a Perplexity informa em dólar
     * chega exato. Lido como `double`, 0,1 viraria 0,1000000000000000055…, e
     * o arredondamento para cima na escala 8 do `Custo` cobraria um centésimo
     * de milionésimo a mais.
     */
    val LEITOR: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun objeto(): ObjectNode = JsonNodeFactory.instance.objectNode()
}

/**
 * O contrato de um provedor: cabeçalhos, corpo e leitura da resposta. Cada
 * formato vem da documentação oficial do provedor, reconferida em 23/09/2026
 * (MAEANDR-19). O web e o desktop não servem de fonte para o corpo: nenhum
 * dos dois usa os transportes novos da seção 5.1.
 *
 * O raciocínio vai no máximo de cada provedor, por decisão do operador de
 * 23/09/2026. `store: false` vai explícito nos quatro que o expõem (OpenAI,
 * Gemini, xAI e Perplexity) e fica fora dos dois que não o têm (Anthropic e
 * DeepSeek): mandar campo que a API não conhece é pedir recusa, não
 * privacidade.
 *
 * Da resposta sai só o texto final: nunca bloco de raciocínio, que chegaria a
 * `<maestro_final_text>`.
 */
internal sealed interface Formato {
    fun cabecalhos(chave: String): Map<String, String>
    fun corpo(pedido: Pedido, modelo: String): ObjectNode
    fun ler(resposta: JsonNode): Resultado

    companion object {
        fun de(provedor: Provedor): Formato = when (provedor) {
            Provedor.CLAUDE -> Anthropic
            Provedor.CODEX -> Responses(esforco = "max")
            Provedor.GEMINI -> Gemini
            Provedor.DEEPSEEK -> DeepSeek
            Provedor.GROK -> Responses(esforco = "xhigh")
            Provedor.PERPLEXITY -> Perplexity
        }
    }

    /**
     * Anthropic Messages. Sem `store`: o `/v1/messages` não declara o campo.
     * Raciocínio adaptativo com `output_config.effort: "max"`; `budget_tokens`
     * é recusado com 400 nos modelos correntes (seção 5.2).
     */
    data object Anthropic : Formato {
        override fun cabecalhos(chave: String) = mapOf(
            "x-api-key" to chave,
            "anthropic-version" to "2023-06-01",
        )

        override fun corpo(pedido: Pedido, modelo: String): ObjectNode = Json.objeto().apply {
            put("model", modelo)
            put("max_tokens", pedido.maxTokensDeSaida)
            put("system", pedido.sistema)
            putArray("messages").addObject().apply {
                put("role", "user")
                put("content", pedido.prompt)
            }
            putObject("thinking").put("type", "adaptive")
            putObject("output_config").put("effort", "max")
        }

        /**
         * `output_tokens` já inclui o raciocínio ("the inclusive, authoritative
         * total used for billing"). A entrada soma as leituras e gravações de
         * cache, que a conta de custo, com taxa única de entrada, trata como
         * entrada comum.
         */
        override fun ler(resposta: JsonNode): Resultado {
            val uso = resposta.path("usage").let { u ->
                Uso(
                    tokensDeEntrada = soma(
                        u.inteiro("input_tokens"),
                        u.inteiro("cache_creation_input_tokens"),
                        u.inteiro("cache_read_input_tokens"),
                    ),
                    tokensDeSaida = u.inteiro("output_tokens"),
                )
            }
            val texto = resposta.path("content").filter { it.texto("type") == "text" }
                .joinToString("") { it.texto("text").orEmpty() }
            val parada = resposta.texto("stop_reason")
            return if (parada == "end_turn") {
                Resultado.Concluida(texto.trim(), uso)
            } else {
                Resultado.Incompleta("stop_reason: ${parada ?: "ausente"}", uso)
            }
        }
    }

    /** OpenAI e xAI, a mesma Responses API. */
    data class Responses(val esforco: String) : Formato {
        override fun cabecalhos(chave: String) = mapOf("Authorization" to "Bearer $chave")

        override fun corpo(pedido: Pedido, modelo: String): ObjectNode = Json.objeto().apply {
            put("model", modelo)
            put("instructions", pedido.sistema)
            putArray("input").addObject().apply {
                put("role", "user")
                putArray("content").addObject().apply {
                    put("type", "input_text")
                    put("text", pedido.prompt)
                }
            }
            put("max_output_tokens", pedido.maxTokensDeSaida)
            putObject("reasoning").put("effort", esforco)
            put("store", false)
        }

        override fun ler(resposta: JsonNode): Resultado {
            val uso = resposta.path("usage").let { u ->
                Uso(u.inteiro("input_tokens"), u.inteiro("output_tokens"))
            }
            val texto = resposta.path("output").filter { it.texto("type") == "message" }
                .flatMap { it.path("content") }
                .filter { it.texto("type") == "output_text" }
                .joinToString("") { it.texto("text").orEmpty() }
            val estado = resposta.texto("status")
            return if (estado == "completed") {
                Resultado.Concluida(texto.trim(), uso)
            } else {
                val razao = resposta.path("incomplete_details").texto("reason")
                Resultado.Incompleta(
                    "status: ${estado ?: "ausente"}" + (razao?.let { ", reason: $it" } ?: ""),
                    uso,
                )
            }
        }
    }

    /**
     * Gemini Interactions. O `gemini-3.1-pro-preview` aceita `low`, `medium`
     * e `high` em `thinking_level`, e não `minimal`; o máximo é `high`. Nunca
     * `thinking_budget` junto: os dois no mesmo pedido devolvem 400.
     */
    data object Gemini : Formato {
        override fun cabecalhos(chave: String) = mapOf("x-goog-api-key" to chave)

        override fun corpo(pedido: Pedido, modelo: String): ObjectNode = Json.objeto().apply {
            put("model", modelo)
            put("system_instruction", pedido.sistema)
            put("input", pedido.prompt)
            putObject("generation_config").apply {
                put("thinking_level", "high")
                put("max_output_tokens", pedido.maxTokensDeSaida)
            }
            put("store", false)
        }

        /**
         * `total_output_tokens` conta só a resposta; o raciocínio vem em
         * `total_thought_tokens` e é cobrado como saída. Os dois são somados.
         */
        override fun ler(resposta: JsonNode): Resultado {
            val uso = resposta.path("usage").let { u ->
                Uso(
                    tokensDeEntrada = u.inteiro("total_input_tokens"),
                    tokensDeSaida = soma(u.inteiro("total_output_tokens"), u.inteiro("total_thought_tokens")),
                )
            }
            val texto = resposta.path("steps").filter { it.texto("type") == "model_output" }
                .flatMap { it.path("content") }
                .filter { it.texto("type") == "text" }
                .joinToString("") { it.texto("text").orEmpty() }
            val estado = resposta.texto("status")
            return if (estado == "completed") {
                Resultado.Concluida(texto.trim(), uso)
            } else {
                Resultado.Incompleta("status: ${estado ?: "ausente"}", uso)
            }
        }
    }

    /**
     * DeepSeek Chat Completions. Sem `store`: a API não declara o campo.
     * `thinking.reasoning_effort` fica dentro de `thinking`, como a referência
     * da API documenta.
     */
    data object DeepSeek : Formato {
        override fun cabecalhos(chave: String) = mapOf("Authorization" to "Bearer $chave")

        override fun corpo(pedido: Pedido, modelo: String): ObjectNode = Json.objeto().apply {
            put("model", modelo)
            putArray("messages").apply {
                addObject().put("role", "system").put("content", pedido.sistema)
                addObject().put("role", "user").put("content", pedido.prompt)
            }
            put("max_tokens", pedido.maxTokensDeSaida)
            put("stream", false)
            putObject("thinking").apply {
                put("type", "enabled")
                put("reasoning_effort", "max")
            }
        }

        /**
         * O texto é `content`; o raciocínio vem em `reasoning_content`, que
         * nunca é lido. Um `<think>…</think>` no começo de `content` é tirado,
         * como o web faz.
         */
        override fun ler(resposta: JsonNode): Resultado {
            val uso = resposta.path("usage").let { u ->
                Uso(u.inteiro("prompt_tokens"), u.inteiro("completion_tokens"))
            }
            val escolha = resposta.path("choices").path(0)
            val texto = escolha.path("message").texto("content").orEmpty()
                .replace(PENSAMENTO_INICIAL, "")
            val fim = escolha.texto("finish_reason")
            return if (fim == "stop") {
                Resultado.Concluida(texto.trim(), uso)
            } else {
                Resultado.Incompleta("finish_reason: ${fim ?: "ausente"}", uso)
            }
        }

        private val PENSAMENTO_INICIAL = Regex("^\\s*<think>[\\s\\S]*?</think>\\s*", RegexOption.IGNORE_CASE)
    }

    /** Perplexity Agent API, com o preset `xhigh` e o modelo explícito. */
    data object Perplexity : Formato {
        override fun cabecalhos(chave: String) = mapOf("Authorization" to "Bearer $chave")

        override fun corpo(pedido: Pedido, modelo: String): ObjectNode = Json.objeto().apply {
            put("preset", "xhigh")
            put("model", modelo)
            put("instructions", pedido.sistema)
            put("input", pedido.prompt)
            put("max_output_tokens", pedido.maxTokensDeSaida)
            put("stream", false)
            put("store", false)
        }

        /** O custo informado só vale em dólar, como no web. */
        override fun ler(resposta: JsonNode): Resultado {
            val u = resposta.path("usage")
            val custo = u.path("cost").takeIf { it.texto("currency") == "USD" }
                ?.path("total_cost")?.takeIf { it.isNumber }?.decimalValue()
            val uso = Uso(u.inteiro("input_tokens"), u.inteiro("output_tokens"), custo)
            val texto = resposta.path("output")
                .filter { it.texto("type") == "message" && it.texto("role") == "assistant" }
                .flatMap { it.path("content") }
                .filter { it.texto("type") == "output_text" }
                .mapNotNull { it.texto("text") }
                .joinToString("\n")
            val estado = resposta.texto("status")
            return if (estado == "completed") {
                Resultado.Concluida(texto.trim(), uso)
            } else {
                Resultado.Incompleta("status: ${estado ?: "ausente"}", uso)
            }
        }
    }
}

private fun JsonNode.texto(campo: String): String? = get(campo)?.takeIf { it.isTextual }?.textValue()

private fun JsonNode.inteiro(campo: String): Long? =
    get(campo)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()

/** Soma o que veio; `null` só quando nenhuma parcela veio. */
private fun soma(vararg parcelas: Long?): Long? =
    parcelas.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
