package dev.lcv.maestro.provedores

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode

/**
 * Mensagens de erro de provedor, portadas do desktop: a classe por status HTTP
 * de `provider_http_error_status` (`provider_retry.rs`), a extração da
 * mensagem de `api_error_message` (`editorial_io.rs`) e o saneamento de
 * `sanitize_text` (`sanitize.rs`), em `maestro-app` `origin/main`.
 *
 * A mensagem vai para o jornal da sessão e para a tela. Por isso passa pelo
 * saneamento: segredo com forma conhecida vira `<redacted>`, e caractere de
 * controle vira espaço, para que uma quebra de linha no corpo do erro não
 * forje linhas no registro.
 */
internal object Erros {

    fun mensagemHttp(status: Int, corpo: String): String {
        val classe = when (status) {
            400 -> "BAD_REQUEST"
            401 -> "AUTH"
            403 -> "PERMISSION"
            404 -> "NOT_FOUND"
            408 -> "TIMEOUT"
            409 -> "CONFLICT"
            429 -> "RATE_LIMIT"
            in 500..599 -> "SERVER"
            else -> "OTHER"
        }
        return sanear("PROVIDER_ERROR_HTTP_${status}_$classe: ${mensagemDoProvedor(corpo)}", 240)
    }

    /** `api_error_message`. */
    fun mensagemDoProvedor(corpo: String): String {
        if (corpo.isBlank()) return "sem detalhe na resposta"
        val raiz: JsonNode? = try {
            Json.LEITOR.readTree(corpo)
        } catch (_: JacksonException) {
            null
        }
        if (raiz != null) {
            for (caminho in listOf("/error/message", "/error/status", "/error/code")) {
                val no = raiz.at(caminho)
                if (no.isTextual) return sanear(no.textValue(), 180)
            }
            for (campo in listOf("error", "message")) {
                val no = raiz.get(campo)
                if (no != null && no.isTextual) return sanear(no.textValue(), 180)
            }
        }
        return sanear(corpo, 180)
    }

    /**
     * `sanitize_text`: apaga segredos com forma conhecida, troca caractere de
     * controle por espaço e corta em [limite] pontos de código.
     */
    fun sanear(texto: String, limite: Int): String {
        val semSegredo = SEGREDO.replace(texto, "<redacted>")
        val construtor = StringBuilder()
        var contados = 0
        var indice = 0
        while (indice < semSegredo.length && contados < limite) {
            val pontoDeCodigo = semSegredo.codePointAt(indice)
            if (Character.isISOControl(pontoDeCodigo)) {
                construtor.append(' ')
            } else {
                construtor.appendCodePoint(pontoDeCodigo)
            }
            indice += Character.charCount(pontoDeCodigo)
            contados++
        }
        return construtor.toString()
    }

    /** A mesma expressão de `secret_value_regex` no desktop. */
    private val SEGREDO = Regex(
        "(sk-ant-[A-Za-z0-9_-]{8,}|sk_live_[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|" +
            "pplx-[A-Za-z0-9_-]{8,}|cfut_[A-Za-z0-9_-]{8,}|cfat_[A-Za-z0-9_-]{8,}|" +
            "cfk_[A-Za-z0-9_-]{8,}|xox[baprs]-[A-Za-z0-9-]{8,}|gh[pousr]_[A-Za-z0-9_]{8,}|" +
            "AIza[0-9A-Za-z_-]{8,}|re_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|" +
            "-----BEGIN[^\\r\\n]*(?:\\r?\\n[^\\r\\n]*){0,80})",
        RegexOption.MULTILINE,
    )
}
