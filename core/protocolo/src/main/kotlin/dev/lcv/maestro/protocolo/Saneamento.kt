package dev.lcv.maestro.protocolo

/**
 * Porte de `maestro-app/src-tauri/src/sanitize.rs` (`sanitize_text`,
 * `sanitize_short` e `redact_secrets`, em `68528f9`): todo texto que sai de um
 * portão para a tela ou para o agente passa por aqui.
 *
 * O `:core:provedores` tem a própria cópia de `sanitize_text` (`Erros.kt`),
 * porque os dois módulos não dependem um do outro. As duas vêm do mesmo
 * canônico.
 */
internal object Saneamento {

    /**
     * `sanitize_text`: oculta segredos, troca caractere de controle (categoria
     * Cc, o `char::is_control` do Rust) por espaço e corta em [limite] pontos
     * de código.
     */
    fun texto(valor: String, limite: Int): String {
        val semSegredo = ocultarSegredos(valor)
        val construtor = StringBuilder()
        var indice = 0
        var contados = 0
        while (indice < semSegredo.length && contados < limite) {
            val pontoDeCodigo = semSegredo.codePointAt(indice)
            if (Character.getType(pontoDeCodigo) == Character.CONTROL.toInt()) {
                construtor.append(' ')
            } else {
                construtor.appendCodePoint(pontoDeCodigo)
            }
            indice += Character.charCount(pontoDeCodigo)
            contados++
        }
        return construtor.toString()
    }

    /**
     * `sanitize_short`: `sanitize_text` e depois só alfanumérico ASCII e
     * `_ - . :`, para identificadores e rótulos.
     */
    fun curto(valor: String, limite: Int): String {
        val construtor = StringBuilder()
        for (caractere in texto(valor, limite)) {
            if (caractere in 'a'..'z' || caractere in 'A'..'Z' || caractere in '0'..'9' ||
                caractere == '_' || caractere == '-' || caractere == '.' || caractere == ':'
            ) {
                construtor.append(caractere)
            }
        }
        return construtor.toString()
    }

    /** `redact_secrets`. */
    fun ocultarSegredos(valor: String): String = SEGREDO.replace(valor, "<redacted>")

    /**
     * A mesma expressão de `secret_value_regex`. Só classes ASCII e
     * `[^\r\n]`, que a JVM e a ICU leem igual; o `(?m)` do canônico não tem
     * efeito aqui, porque o padrão não usa `^` nem `$`.
     */
    private val SEGREDO = Regex(
        "(sk-ant-[A-Za-z0-9_-]{8,}|sk_live_[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|" +
            "pplx-[A-Za-z0-9_-]{8,}|cfut_[A-Za-z0-9_-]{8,}|cfat_[A-Za-z0-9_-]{8,}|" +
            "cfk_[A-Za-z0-9_-]{8,}|xox[baprs]-[A-Za-z0-9-]{8,}|gh[pousr]_[A-Za-z0-9_]{8,}|" +
            "AIza[0-9A-Za-z_-]{8,}|re_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|" +
            "-----BEGIN[^\\r\\n]*(?:\\r?\\n[^\\r\\n]*){0,80})",
    )
}
