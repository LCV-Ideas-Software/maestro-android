package dev.lcv.maestro.protocolo

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * O que o Rust do canônico faz com texto de um jeito que a JVM e o Android não
 * fazem sozinhos, reunido num lugar só para que cada porte use a mesma régua.
 *
 * **Expressão regular.** No crate `regex` do Rust, `\s`, `\d`, `\w`, `\b` e
 * `(?i)` são Unicode. No Java da JVM onde os testes rodam, `\s`, `\d` e `\w`
 * são ASCII, e `\b` mudou de definição no JDK 19. No Android, o
 * `java.util.regex` é a ICU4C: as classes já são Unicode, e a flag
 * `UNICODE_CHARACTER_CLASS` — o `(?U)` que resolveria na JVM — **lança
 * `IllegalArgumentException`** (nota *"Android-changed"* no `Pattern.java` do
 * libcore do AOSP, lida em 24/09/2026). Um padrão que passasse nos testes com
 * `(?U)` derrubaria o aplicativo no aparelho.
 *
 * Por isso os padrões portados neste módulo **não usam** `\s`, `\d`, `\w`, `\b`
 * nem `(?U)`: usam as classes explícitas abaixo, que as duas implementações
 * leem igual.
 *
 * **Flags vão por parâmetro, nunca embutidas no padrão.** O `(?i)` do Rust vira
 * `RegexOption.IGNORE_CASE`: o `Regex` do Kotlin na JVM acrescenta sozinho o
 * `UNICODE_CASE`, e no Android a caixa já é Unicode. O `(?m)` vira
 * `MULTILINE` sempre junto de `UNIX_LINES`, porque no Rust só `\n` termina
 * linha, e no Java sem `UNIX_LINES` também `\r`, `\u0085`, `\u2028` e `\u2029`
 * terminam. Flag embutida (`(?iu)`, `(?d)`) é lida pela ICU no aparelho, e não
 * há garantia de que ela aceite todas; a flag por parâmetro é traduzida pelo
 * próprio `Pattern` do Android. `}` literal é sempre escapado, como a ICU exige.
 */
internal object TextoRust {

    /**
     * Unicode White_Space, o `\s` do Rust, como conteúdo de classe de regex.
     * A mesma lista de [EspacoUnicode.ehEspaco]; um teste confere as duas
     * ponto de código a ponto de código, para que não se afastem.
     */
    const val ESPACO_CLASSE: String =
        "\\x{0009}-\\x{000D}\\x{0020}\\x{0085}\\x{00A0}\\x{1680}\\x{2000}-\\x{200A}" +
            "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}"

    /** `\s` do Rust. */
    const val ESPACO: String = "[$ESPACO_CLASSE]"

    /**
     * `\w` do Rust em modo Unicode, como o crate `regex` o documenta:
     * `\p{Alphabetic}` + `\p{M}` + `\d` (`\p{Nd}`) + `\p{Pc}` + `\p{Join_Control}`.
     * O prefixo `Is` das propriedades binárias existe no Android desde o 10, e
     * o `minSdk` é 34.
     */
    const val PALAVRA_CLASSE: String =
        "\\p{IsAlphabetic}\\p{M}\\p{Nd}\\p{Pc}\\p{IsJoin_Control}"

    /** `\d` do Rust, que é `\p{Nd}` e não só `0-9`. */
    const val DIGITO: String = "\\p{Nd}"

    /** `\b` do Rust: de um lado caractere de palavra, do outro não. */
    const val LIMITE: String =
        "(?:(?<=[$PALAVRA_CLASSE])(?![$PALAVRA_CLASSE])|(?<![$PALAVRA_CLASSE])(?=[$PALAVRA_CLASSE]))"

    /**
     * `str::lines` do Rust: parte em `\n`, tira um `\r` só quando ele vem
     * colado ao `\n`, e não produz linha vazia depois do último `\n`. O
     * `lines()` do Kotlin quebra também em `\r` sozinho, o que o Rust não faz.
     */
    fun linhas(texto: String): List<String> {
        val linhas = mutableListOf<String>()
        var inicio = 0
        while (inicio < texto.length) {
            val quebra = texto.indexOf('\n', inicio)
            if (quebra < 0) {
                linhas += texto.substring(inicio)
                break
            }
            var fim = quebra
            if (fim > inicio && texto[fim - 1] == '\r') fim--
            linhas += texto.substring(inicio, fim)
            inicio = quebra + 1
        }
        return linhas
    }

    /**
     * Quantos bytes UTF-8 ocupa `texto` até o índice UTF-16 [indice]. O Rust
     * mede posição em bytes, e posição que entra em hash ou identificador tem
     * de ser medida igual dos dois lados.
     */
    fun bytesAte(texto: String, indice: Int): Int {
        var bytes = 0
        var posicao = 0
        while (posicao < indice) {
            val pontoDeCodigo = texto.codePointAt(posicao)
            bytes += when {
                pontoDeCodigo < 0x80 -> 1
                pontoDeCodigo < 0x800 -> 2
                pontoDeCodigo < 0x10000 -> 3
                else -> 4
            }
            posicao += Character.charCount(pontoDeCodigo)
        }
        return bytes
    }

    /**
     * Índice UTF-16 da posição [pontosDeCodigo] pontos de código antes de
     * [indice], ou 0 se não houver tantos: o `char_indices().rev().nth(n)`.
     */
    fun recuarPontosDeCodigo(texto: String, indice: Int, pontosDeCodigo: Int): Int {
        var posicao = indice
        var contados = 0
        while (posicao > 0 && contados < pontosDeCodigo) {
            posicao -= Character.charCount(texto.codePointBefore(posicao))
            contados++
        }
        return if (contados < pontosDeCodigo) 0 else posicao
    }

    /**
     * Índice UTF-16 depois de [pontosDeCodigo] pontos de código a partir de
     * [indice], ou o fim do texto.
     */
    fun avancarPontosDeCodigo(texto: String, indice: Int, pontosDeCodigo: Int): Int {
        var posicao = indice
        var contados = 0
        while (posicao < texto.length && contados < pontosDeCodigo) {
            posicao += Character.charCount(texto.codePointAt(posicao))
            contados++
        }
        return posicao
    }

    /** SHA-256 em hexadecimal minúsculo, como o `format!("{byte:02x}")`. */
    fun sha256(valor: ByteArray): String {
        val resumo = MessageDigest.getInstance("SHA-256").digest(valor)
        val construtor = StringBuilder(resumo.size * 2)
        for (byte in resumo) {
            val sem = byte.toInt() and 0xFF
            construtor.append(HEX[sem ushr 4]).append(HEX[sem and 0x0F])
        }
        return construtor.toString()
    }

    fun sha256(valor: String): String = sha256(valor.toByteArray(Charsets.UTF_8))

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * `Utc::now().to_rfc3339()` do `chrono`: fração com 0, 3, 6 ou 9 casas,
     * a menor que representa o instante sem perda, e deslocamento `+00:00`.
     */
    fun rfc3339(instante: Instant): String {
        val segundos = FORMATO_SEGUNDOS.format(instante)
        val nanos = instante.nano
        val fracao = when {
            nanos == 0 -> ""
            nanos % 1_000_000 == 0 -> "." + (nanos / 1_000_000).toString().padStart(3, '0')
            nanos % 1_000 == 0 -> "." + (nanos / 1_000).toString().padStart(6, '0')
            else -> "." + nanos.toString().padStart(9, '0')
        }
        return "$segundos$fracao+00:00"
    }

    private val FORMATO_SEGUNDOS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
}
