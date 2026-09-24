package dev.lcv.maestro.protocolo

import java.util.TreeMap

/**
 * Um valor JSON como o `serde_json::Value` do canônico, que é compilado **sem**
 * a feature `preserve_order` (`maestro-app/src-tauri/Cargo.toml`, `serde_json =
 * "1"`): todo objeto guarda as chaves num `BTreeMap`, e por isso sai com elas
 * em ordem — inclusive os `struct` convertidos por `json!`.
 *
 * Os contextos de falha da auditoria final são desse tipo, e o canônico os
 * imprime com `to_string_pretty` no prompt do turno seguinte (linha 1255 de
 * `session_orchestration.rs`). [bonito] reproduz esse formato.
 */
public sealed interface ValorJson {

    public data class Texto(val valor: String) : ValorJson

    public data class Numero(val valor: Long) : ValorJson

    public data class Logico(val valor: Boolean) : ValorJson

    public data object Nulo : ValorJson

    public data class Lista(val itens: List<ValorJson>) : ValorJson

    /** As chaves ficam na ordem do `BTreeMap` do Rust ([OrdemRust]). */
    public class Objeto(campos: Map<String, ValorJson>) : ValorJson {
        public val campos: Map<String, ValorJson> = TreeMap<String, ValorJson>(OrdemRust).apply { putAll(campos) }

        override fun equals(other: Any?): Boolean = other is Objeto && other.campos == campos

        override fun hashCode(): Int = campos.hashCode()

        override fun toString(): String = "Objeto($campos)"
    }

    public companion object {
        public fun texto(valor: String?): ValorJson = valor?.let(::Texto) ?: Nulo

        public fun numero(valor: Long?): ValorJson = valor?.let(::Numero) ?: Nulo

        public fun numero(valor: Int?): ValorJson = valor?.let { Numero(it.toLong()) } ?: Nulo

        public fun logico(valor: Boolean?): ValorJson = valor?.let(::Logico) ?: Nulo

        public fun objeto(vararg campos: Pair<String, ValorJson>): Objeto = Objeto(campos.toMap())

        public fun textos(valores: List<String>): Lista = Lista(valores.map(::Texto))
    }
}

/** `serde_json::to_string_pretty`: dois espaços por nível, `"chave": valor`. */
public fun ValorJson.bonito(): String = StringBuilder().also { escreverBonito(this, it, 0) }.toString()

private fun escreverBonito(valor: ValorJson, saida: StringBuilder, nivel: Int) {
    when (valor) {
        is ValorJson.Texto -> saida.append(textoJson(valor.valor))
        is ValorJson.Numero -> saida.append(valor.valor)
        is ValorJson.Logico -> saida.append(valor.valor)
        ValorJson.Nulo -> saida.append("null")
        is ValorJson.Lista -> {
            if (valor.itens.isEmpty()) {
                saida.append("[]")
                return
            }
            saida.append("[\n")
            valor.itens.forEachIndexed { indice, item ->
                if (indice > 0) saida.append(",\n")
                recuo(saida, nivel + 1)
                escreverBonito(item, saida, nivel + 1)
            }
            saida.append('\n')
            recuo(saida, nivel)
            saida.append(']')
        }
        is ValorJson.Objeto -> {
            if (valor.campos.isEmpty()) {
                saida.append("{}")
                return
            }
            saida.append("{\n")
            var primeiro = true
            for ((chave, campo) in valor.campos) {
                if (!primeiro) saida.append(",\n")
                primeiro = false
                recuo(saida, nivel + 1)
                saida.append(textoJson(chave)).append(": ")
                escreverBonito(campo, saida, nivel + 1)
            }
            saida.append('\n')
            recuo(saida, nivel)
            saida.append('}')
        }
    }
}

private fun recuo(saida: StringBuilder, nivel: Int) {
    repeat(nivel) { saida.append("  ") }
}

/** Um texto JSON com o escape do `serde_json` — o mesmo do [JsonRust]. */
private fun textoJson(valor: String): String {
    val json = JsonRust()
    json.valorTexto(valor)
    return json.toString()
}
