package dev.lcv.maestro.sessao

import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * Os dois leitores que o web distingue de propósito (`parseJson` tolerante,
 * `sessions.ts:676-683`; `parseSessionEventsStrict` e
 * `parsePersistedCircularState` estritos, `:685-699` e `:3100-3116`):
 * taxas, modelos e agentes ativos caem no valor padrão quando não parseiam;
 * jornal e estado circular falham fechado.
 */
internal object Json {
    /** Chave repetida e lixo depois do valor são erro; decimais chegam exatos. */
    val ESTRITO: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    /** `JSON.parse` como o web o usa nos campos tolerantes. */
    val TOLERANTE: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    /** `parseJson(value, fallback)`: nó lido, ou `null` quando vazio ou inválido. */
    fun tolerante(texto: String?): JsonNode? {
        if (texto.isNullOrEmpty()) return null
        return try {
            TOLERANTE.readTree(texto)
        } catch (erro: com.fasterxml.jackson.core.JacksonException) {
            null
        }
    }
}
