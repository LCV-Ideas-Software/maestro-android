package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guardas da armadilha de espaço em branco.
 *
 * Estes casos não existem para provar que [EspacoUnicode] funciona: existem
 * para **falhar** se alguém o trocar pela API da plataforma.
 *
 * São três definições diferentes de "espaço", e é por isso que o módulo tem a
 * sua — medido neste projeto em 21/09/2026:
 *
 * | Definição                     | NBSP | NEL | U+001C |
 * | ----------------------------- | ---- | --- | ------ |
 * | Java `Character.isWhitespace` | não  | não | sim    |
 * | Kotlin `Char.isWhitespace`    | sim  | não | sim    |
 * | Unicode White_Space (= Rust)  | sim  | sim | não    |
 *
 * Quem porta lendo só a documentação do Java conclui que o `trim()` do Kotlin
 * é perigoso para NBSP, e erra — o Kotlin cobre NBSP. Quem lê só o Kotlin
 * conclui que `trim()` resolve, e erra no NEL e no U+001C. Os dois enganos
 * levam ao mesmo lugar: a trava comparando blocos com uma régua diferente da
 * canônica.
 *
 * Os pontos de código invisíveis aparecem aqui como número, via [pc], e nunca
 * colados no texto. Caractere invisível no código-fonte é irrevisável: ninguém
 * distingue um NBSP de um espaço comum numa revisão de pull request.
 */
class EspacoUnicodeTest {

    /** O texto de um ponto de código, escrito pelo número e não pelo glifo. */
    private fun pc(pontoDeCodigo: Int): String = String(Character.toChars(pontoDeCodigo))

    private val nel = 0x0085
    private val nbsp = 0x00A0
    private val espacoDeFigura = 0x2007
    private val nbspEstreito = 0x202F
    private val separadorDeArquivo = 0x001C
    private val rostoSorridente = 0x1F600

    /** Espaços Unicode que o `Character.isWhitespace` do Java não reconhece. */
    private val divergentesDoJava = listOf(
        nel to "NEL",
        nbsp to "no-break space",
        espacoDeFigura to "figure space",
        nbspEstreito to "narrow no-break space",
    )

    @Test
    fun `os divergentes sao espaco aqui e nao sao para o Java`() {
        for ((pontoDeCodigo, nome) in divergentesDoJava) {
            assertTrue(
                EspacoUnicode.ehEspaco(pontoDeCodigo),
                "$nome deveria ser espaço pelo Unicode White_Space",
            )
            assertFalse(
                Character.isWhitespace(pontoDeCodigo),
                "$nome deixou de divergir do Java; este teste precisa ser revisto, " +
                    "não removido — a premissa mudou",
            )
        }
    }

    @Test
    fun `aparar remove todos os espacos Unicode, divirja ou nao a plataforma`() {
        for ((pontoDeCodigo, nome) in divergentesDoJava) {
            val espaco = pc(pontoDeCodigo)
            assertEquals(
                "conteudo",
                EspacoUnicode.aparar("${espaco}conteudo$espaco"),
                "aparar falhou em $nome",
            )
        }
    }

    @Test
    fun `o espaco do Kotlin nao e o do Java nem o do Unicode`() {
        // O Kotlin cobre o NBSP que o Java não cobre...
        assertTrue(pc(nbsp).single().isWhitespace(), "o Kotlin deixou de tratar NBSP como espaço")
        assertFalse(
            Character.isWhitespace(nbsp),
            "o Java passou a tratar NBSP como espaço; a premissa mudou",
        )

        // ...mas não cobre o NEL, que é Unicode White_Space.
        assertFalse(pc(nel).single().isWhitespace(), "o Kotlin passou a tratar NEL como espaço")
        assertTrue(EspacoUnicode.ehEspaco(nel), "NEL é Unicode White_Space")

        // E sobra na direção oposta: o separador de arquivo é espaço para o
        // Kotlin e para o Java, e não é Unicode White_Space.
        assertTrue(
            pc(separadorDeArquivo).single().isWhitespace(),
            "o Kotlin deixou de tratar o separador de arquivo como espaço",
        )
        assertFalse(
            EspacoUnicode.ehEspaco(separadorDeArquivo),
            "o separador de arquivo não é Unicode White_Space",
        )
    }

    @Test
    fun `trim do Kotlin diverge de aparar nos dois sentidos`() {
        // Cada sentido é uma forma de a trava errar: deixar passar revisão que
        // devia recusar, ou recusar revisão legítima.
        val comNel = "${pc(nel)}conteudo${pc(nel)}"
        assertEquals("conteudo", EspacoUnicode.aparar(comNel))
        assertNotEquals(
            "conteudo",
            comNel.trim(),
            "o trim do Kotlin passou a remover NEL; a premissa mudou",
        )

        val separador = pc(separadorDeArquivo)
        val comSeparador = "${separador}conteudo$separador"
        assertEquals(comSeparador, EspacoUnicode.aparar(comSeparador))
        assertEquals("conteudo", comSeparador.trim())
    }

    @Test
    fun `dividirPorEspacos parte nos espacos que a plataforma ignoraria`() {
        for ((pontoDeCodigo, nome) in divergentesDoJava) {
            assertEquals(
                listOf("um", "dois"),
                EspacoUnicode.dividirPorEspacos("um${pc(pontoDeCodigo)}dois"),
                "dividirPorEspacos falhou em $nome",
            )
        }
    }

    @Test
    fun `dois blocos separados por espacos diferentes normalizam igual`() {
        // É esta igualdade que a trava usa para decidir se um bloco mudou. Com
        // a régua errada, o bloco com NBSP teria hash diferente e a trava
        // aprovaria uma revisão que deveria recusar — ou o contrário.
        val comEspacoComum = TravaDeConteudo.normalizarTextoDoBloco("palavra outra")
        val comNbsp = TravaDeConteudo.normalizarTextoDoBloco("palavra${pc(nbsp)}outra")
        assertEquals(comEspacoComum, comNbsp)
    }

    @Test
    fun `a contagem de caracteres conta pontos de codigo, nao unidades UTF-16`() {
        // O número vai para a coluna `chars` do manifesto que o agente lê.
        val comEmoji = "ab" + pc(rostoSorridente)
        assertEquals(3, EspacoUnicode.contarPontosDeCodigo(comEmoji))
        assertEquals(4, comEmoji.length, "se isto mudar, o JVM mudou de modelo de cadeia")
    }

    @Test
    fun `caixaBaixaAscii so rebaixa A-Z, como o canonico`() {
        // Nomes de campo e valores de `change_type` são comparados sem caixa,
        // como o `to_ascii_lowercase` do canônico: só A-Z rebaixa. Uma dobra
        // Unicode faria o `İ` virar `i` + ponto combinante e casar nome que o
        // canônico não casa. O `O` final é ASCII e rebaixa; os acentuados não.
        val acentuadas = pc(0x00C1) + pc(0x00C7) + pc(0x00C3) + "O"
        val original = "CHANGED_BLOCKS: $acentuadas " + pc(0x0130)
        val rebaixado = EspacoUnicode.caixaBaixaAscii(original)
        assertEquals(
            "changed_blocks: " + pc(0x00C1) + pc(0x00C7) + pc(0x00C3) + "o " + pc(0x0130),
            rebaixado,
        )
    }
}
