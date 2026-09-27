package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.EspacoUnicode
import dev.lcv.maestro.provedores.Provedor
import java.util.Locale

/**
 * Os seis agentes como o web os nomeia (`PROVIDER_KEYS`, `AGENT_LABELS`,
 * `sanitizeAgent`, `sanitizeAgents`; `sessions.ts:236-243, 296, 354-378`).
 * A ordem canônica é a da enumeração [Provedor], que é a de `PROVIDER_KEYS`.
 */
public object Agentes {

    /** `AGENT_LABELS`. */
    public val Provedor.rotulo: String
        get() = when (this) {
            Provedor.CLAUDE -> "Claude"
            Provedor.CODEX -> "Codex"
            Provedor.GEMINI -> "Gemini"
            Provedor.DEEPSEEK -> "DeepSeek"
            Provedor.GROK -> "Grok"
            Provedor.PERPLEXITY -> "Perplexity"
        }

    /** `isProviderKey`: a chave exata, sem apelido. */
    public fun porChave(valor: String?): Provedor? = Provedor.entries.firstOrNull { it.agente == valor }

    /**
     * `sanitizeAgent`: a chave, um apelido conhecido, ou [padrao]. O texto é
     * aparado e cortado em 80 pontos de código como `sanitizeText`, e comparado
     * em caixa baixa.
     */
    public fun sanear(valor: String?, padrao: Provedor): Provedor {
        val normalizado = Texto.sanear(valor, 80).lowercase(Locale.ROOT)
        porChave(normalizado)?.let { return it }
        return when (normalizado) {
            "anthropic" -> Provedor.CLAUDE
            "openai", "chatgpt" -> Provedor.CODEX
            "google", "agy", "antigravity" -> Provedor.GEMINI
            "xai", "grok-api" -> Provedor.GROK
            "sonar", "perplexity-api" -> Provedor.PERPLEXITY
            "deepseek-api" -> Provedor.DEEPSEEK
            else -> padrao
        }
    }

    /**
     * `sanitizeAgents`: cada valor saneado, sem repetição, com o [inicial] na
     * frente se faltar, no máximo seis. `null` é "todos", como no web.
     */
    public fun sanearLista(valores: List<String?>?, inicial: Provedor): List<Provedor> {
        val brutos = valores ?: Provedor.entries.map { it.agente }
        val selecionados = mutableListOf<Provedor>()
        for (valor in brutos) {
            val agente = sanear(valor, inicial)
            if (agente !in selecionados) selecionados += agente
        }
        if (inicial !in selecionados) selecionados.add(0, inicial)
        return selecionados.take(Provedor.entries.size)
    }
}

/** `sanitizeText` do web (`sessions.ts:337-343`): sem NUL, aparado, cortado. */
public object Texto {
    /**
     * O corte é em pontos de código, e não em unidades UTF-16 como o `slice`
     * do JavaScript (desvio declarado: um emoji nunca é partido ao meio). O
     * `trim` é o do JavaScript, o mesmo que o web aplica aqui.
     */
    public fun sanear(valor: String?, maximo: Int = 4000): String {
        val semNulo = (valor ?: "").replace("\u0000", "")
        return EspacoUnicode.primeirosPontosDeCodigo(TrimJs.aparar(semNulo), maximo)
    }
}

/**
 * `String.prototype.trim` do JavaScript, escrito por extenso: WhiteSpace
 * (U+0009, U+000B, U+000C, U+0020, U+00A0, U+FEFF e a categoria Zs) e
 * LineTerminator (U+000A, U+000D, U+2028, U+2029). Difere do `str::trim` do
 * Rust, que o `:core:protocolo` usa: este inclui U+FEFF e exclui U+0085. O
 * web apara com ele a custódia circular e o hash do texto; o porte faz o mesmo
 * nas duas pontas (revisão cruzada de 25/09/2026, emenda A9).
 */
public object TrimJs {
    public fun ehEspacoJs(pontoDeCodigo: Int): Boolean = when (pontoDeCodigo) {
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0xFEFF, 0x2028, 0x2029 -> true
        else -> Character.getType(pontoDeCodigo) == Character.SPACE_SEPARATOR.toInt()
    }

    public fun aparar(texto: String): String {
        var inicio = 0
        var fim = texto.length
        while (inicio < fim) {
            val ponto = texto.codePointAt(inicio)
            if (!ehEspacoJs(ponto)) break
            inicio += Character.charCount(ponto)
        }
        while (fim > inicio) {
            val ponto = texto.codePointBefore(fim)
            if (!ehEspacoJs(ponto)) break
            fim -= Character.charCount(ponto)
        }
        return texto.substring(inicio, fim)
    }
}
