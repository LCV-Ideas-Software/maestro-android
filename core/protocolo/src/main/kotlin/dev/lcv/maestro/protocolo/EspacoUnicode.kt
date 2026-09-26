package dev.lcv.maestro.protocolo

/**
 * Espaço em branco no sentido do Unicode White_Space, que é o do
 * `char::is_whitespace` do Rust — a definição usada pela implementação
 * canônica da trava de conteúdo.
 *
 * **Este arquivo existe porque o JVM classifica espaço de outro jeito.** Medido
 * no JDK 17.0.20.1+1 em 21/09/2026:
 *
 * | Code point            | `Character.isWhitespace` | Unicode White_Space |
 * | --------------------- | ------------------------ | ------------------- |
 * | `U+0085` NEL          | `false`                  | **sim**             |
 * | `U+00A0` NBSP         | `false`                  | **sim**             |
 * | `U+2007` figure space | `false`                  | **sim**             |
 * | `U+202F` narrow NBSP  | `false`                  | **sim**             |
 * | `U+FEFF` BOM          | `false`                  | não                 |
 *
 * E `\s` de expressão regular no Java é ASCII puro por padrão, sem
 * `UNICODE_CHARACTER_CLASS`, o que é ainda mais distante.
 *
 * A consequência de usar a API da plataforma seria silenciosa e séria: a trava
 * decide se uma revisão mexeu só nos blocos que declarou ter mexido, comparando
 * texto normalizado por espaço. Uma classe de espaço diferente normaliza
 * diferente; dois blocos que o canônico vê como iguais seriam vistos como
 * distintos, e o portão **aprovaria uma revisão que deveria recusar**, sem erro
 * nenhum na tela.
 *
 * Por isso, neste módulo, `Char.isWhitespace()`, `String.trim()`,
 * `String.isBlank()` e `\s` de regex estão proibidos. Use o que está aqui.
 */
public object EspacoUnicode {

    /**
     * Unicode White_Space, a mesma lista que o `char::is_whitespace` do Rust
     * reconhece. Escrita por extenso em vez de derivada da plataforma, porque
     * derivar da plataforma é exatamente o erro que este arquivo evita.
     */
    public fun ehEspaco(pontoDeCodigo: Int): Boolean = when (pontoDeCodigo) {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, // tab, LF, VT, FF, CR
        0x0020, // espaço
        0x0085, // NEL
        0x00A0, // no-break space
        0x1680, // ogham space mark
        0x2028, // line separator
        0x2029, // paragraph separator
        0x202F, // narrow no-break space
        0x205F, // medium mathematical space
        0x3000, // ideographic space
        -> true

        else -> pontoDeCodigo in 0x2000..0x200A // en quad .. hair space
    }

    /** Equivalente do `str::trim` do Rust. */
    public fun aparar(texto: String): String {
        var inicio = 0
        var fim = texto.length
        while (inicio < fim) {
            val pontoDeCodigo = texto.codePointAt(inicio)
            if (!ehEspaco(pontoDeCodigo)) break
            inicio += Character.charCount(pontoDeCodigo)
        }
        while (fim > inicio) {
            val pontoDeCodigo = texto.codePointBefore(fim)
            if (!ehEspaco(pontoDeCodigo)) break
            fim -= Character.charCount(pontoDeCodigo)
        }
        return texto.substring(inicio, fim)
    }

    /** Equivalente do `str::trim_start` do Rust. */
    public fun apararInicio(texto: String): String {
        var inicio = 0
        while (inicio < texto.length) {
            val pontoDeCodigo = texto.codePointAt(inicio)
            if (!ehEspaco(pontoDeCodigo)) break
            inicio += Character.charCount(pontoDeCodigo)
        }
        return texto.substring(inicio)
    }

    /** Equivalente do `str::trim_end` do Rust. */
    public fun apararFim(texto: String): String {
        var fim = texto.length
        while (fim > 0) {
            val pontoDeCodigo = texto.codePointBefore(fim)
            if (!ehEspaco(pontoDeCodigo)) break
            fim -= Character.charCount(pontoDeCodigo)
        }
        return texto.substring(0, fim)
    }

    /** Verdadeiro quando o texto só tem espaço em branco, ou nada. */
    public fun soEspaco(texto: String): Boolean = aparar(texto).isEmpty()

    /**
     * Equivalente do `str::split_whitespace` do Rust: parte em corridas de
     * espaço e descarta os pedaços vazios das pontas.
     */
    public fun dividirPorEspacos(texto: String): List<String> {
        val pedacos = mutableListOf<String>()
        var indice = 0
        var inicioDoPedaco = -1
        while (indice < texto.length) {
            val pontoDeCodigo = texto.codePointAt(indice)
            val largura = Character.charCount(pontoDeCodigo)
            if (ehEspaco(pontoDeCodigo)) {
                if (inicioDoPedaco >= 0) {
                    pedacos += texto.substring(inicioDoPedaco, indice)
                    inicioDoPedaco = -1
                }
            } else if (inicioDoPedaco < 0) {
                inicioDoPedaco = indice
            }
            indice += largura
        }
        if (inicioDoPedaco >= 0) pedacos += texto.substring(inicioDoPedaco)
        return pedacos
    }

    /**
     * Caixa baixa só de A–Z, como o `to_ascii_lowercase` do Rust — usada para
     * comparar nomes de campo e valores de `change_type` do jeito do canônico.
     * Dobra Unicode casaria nomes que o canônico não casa.
     */
    public fun caixaBaixaAscii(texto: String): String {
        val construtor = StringBuilder(texto.length)
        for (caractere in texto) {
            construtor.append(if (caractere in 'A'..'Z') caractere + 32 else caractere)
        }
        return construtor.toString()
    }

    /**
     * Quantidade de pontos de código, como o `chars().count()` do Rust — e não
     * `String.length`, que conta unidades UTF-16 e contaria 2 num emoji.
     */
    public fun contarPontosDeCodigo(texto: String): Int =
        texto.codePointCount(0, texto.length)

    /** Primeiros [limite] pontos de código, como `chars().take(n)` do Rust. */
    public fun primeirosPontosDeCodigo(texto: String, limite: Int): String {
        if (limite <= 0) return ""
        var indice = 0
        var contados = 0
        while (indice < texto.length && contados < limite) {
            indice += Character.charCount(texto.codePointAt(indice))
            contados++
        }
        return texto.substring(0, indice)
    }
}
