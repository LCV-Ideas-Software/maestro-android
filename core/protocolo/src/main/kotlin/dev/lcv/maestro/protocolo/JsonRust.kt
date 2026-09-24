package dev.lcv.maestro.protocolo

/**
 * Escrita de JSON compacto com as regras do `serde_json::to_vec`: campos na
 * ordem em que o `struct` do canônico os declara, `None` como `null`, sem
 * espaço entre os tokens.
 *
 * Existe porque o canônico calcula hash sobre esses bytes — o `audit_id` da
 * auditoria ABNT e a comparação entre versões do manifesto — e o mesmo
 * manifesto tem de dar o mesmo hash aqui e no desktop. É só **escrita**; ler
 * JSON continua sendo trabalho do Jackson.
 *
 * O escape segue o `serde_json` (`ESCAPE` em `ser.rs`): `"` e `\` com barra;
 * `\b`, `\t`, `\n`, `\f` e `\r` na forma curta; os demais abaixo de `0x20`
 * como `\u00xx` em hexadecimal minúsculo. `/`, `0x7F` e todo o não ASCII vão
 * como estão.
 */
internal class JsonRust {

    private val saida = StringBuilder()

    /** Por nível aberto: se o próximo campo ou item é o primeiro dele. */
    private val primeiro = ArrayDeque<Boolean>()

    fun objeto(bloco: JsonRust.() -> Unit): JsonRust {
        saida.append('{')
        primeiro.addLast(true)
        bloco()
        primeiro.removeLast()
        saida.append('}')
        return this
    }

    fun lista(bloco: JsonRust.() -> Unit): JsonRust {
        saida.append('[')
        primeiro.addLast(true)
        bloco()
        primeiro.removeLast()
        saida.append(']')
        return this
    }

    /** Um campo de objeto: o nome e o valor que [valor] escreve. */
    fun campo(nome: String, valor: JsonRust.() -> Unit) {
        separar()
        escreverTexto(nome)
        saida.append(':')
        valor()
    }

    fun campo(nome: String, texto: String?) = campo(nome) { valorTexto(texto) }

    fun campo(nome: String, logico: Boolean) = campo(nome) { saida.append(logico) }

    /** Um item de lista. */
    fun item(valor: JsonRust.() -> Unit) {
        separar()
        valor()
    }

    fun valorTexto(texto: String?) {
        if (texto == null) saida.append("null") else escreverTexto(texto)
    }

    override fun toString(): String = saida.toString()

    fun bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

    /** Vírgula antes de todo campo ou item que não é o primeiro do nível. */
    private fun separar() {
        if (primeiro.last()) primeiro[primeiro.lastIndex] = false else saida.append(',')
    }

    private fun escreverTexto(texto: String) {
        saida.append('"')
        for (caractere in texto) {
            when (caractere) {
                '"' -> saida.append("\\\"")
                '\\' -> saida.append("\\\\")
                '\b' -> saida.append("\\b")
                '\t' -> saida.append("\\t")
                '\n' -> saida.append("\\n")
                '\u000C' -> saida.append("\\f")
                '\r' -> saida.append("\\r")
                else -> if (caractere < ' ') {
                    saida.append("\\u00")
                    saida.append(HEX[caractere.code ushr 4])
                    saida.append(HEX[caractere.code and 0x0F])
                } else {
                    saida.append(caractere)
                }
            }
        }
        saida.append('"')
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
    }
}
