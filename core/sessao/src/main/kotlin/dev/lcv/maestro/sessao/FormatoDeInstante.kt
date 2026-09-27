package dev.lcv.maestro.sessao

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `nowIso()` do web (`new Date().toISOString()`, `sessions.ts:331`): sempre
 * com três casas de fração e `Z`. As colunas de data do banco levam este
 * formato; a leitura aceita qualquer instante ISO-8601.
 */
public object FormatoDeInstante {
    private val ISO_JS: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    public fun iso(instante: Instant): String = ISO_JS.format(instante)

    /** `Date.parse` sem o `NaN`: `null` quando o texto não é um instante. */
    public fun ler(texto: String?): Instant? {
        if (texto.isNullOrEmpty()) return null
        return try {
            Instant.parse(texto)
        } catch (erro: java.time.format.DateTimeParseException) {
            null
        }
    }
}
