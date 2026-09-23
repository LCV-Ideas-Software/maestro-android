package dev.lcv.maestro.protocolo

/**
 * O portão de integridade bibliográfica: um texto candidato a entrega não pode
 * esconder evidência pendente nem lacuna de referência.
 *
 * Porte de `maestro-app/src-tauri/src/session_orchestration.rs`
 * (`validate_final_release_candidate`, `contains_final_release_blocker` e os
 * auxiliares de marcador, linhas 2598–2604 e 2812–2920 em `3c8babc`). Mora
 * aqui, e não com a auditoria do candidato final (MAEANDR-18), porque o
 * validador do turno ([TurnoSerial]) o chama sobre todo texto revisado.
 *
 * **Dígito é só ASCII.** O canônico usa `is_ascii_digit`; o `isDigit()` do
 * Kotlin é Unicode e aceitaria `٣` (U+0663), marcando como data incerta um
 * colchete que o canônico deixa passar.
 */
public object IntegridadeBibliografica {

    /** Mensagem canônica, palavra por palavra: ela volta ao agente. */
    internal const val MOTIVO =
        "final candidate failed bibliographic integrity gate: unresolved evidence marker or " +
            "bibliographic lacuna found"

    /** `validate_final_release_candidate`: o motivo da recusa, ou `null`. */
    public fun validarCandidato(texto: String): String? =
        if (temPendencia(texto)) MOTIVO else null

    /** `contains_final_release_blocker`. */
    internal fun temPendencia(texto: String): Boolean {
        val compacto = assinaturaCompacta(texto)
        if (compacto.contains("evidenciapendente") ||
            compacto.contains("edicaoconsultadanaoidentificada")
        ) {
            return true
        }

        var restante = texto
        while (true) {
            val abre = restante.indexOf('[')
            if (abre < 0) break
            val depoisDeAbrir = restante.substring(abre + 1)
            val fecha = depoisDeAbrir.indexOf(']')
            if (fecha < 0) break
            val entreColchetes = depoisDeAbrir.substring(0, fecha)
            val marcador = assinaturaCompacta(entreColchetes)
            if (ehLacunaBibliografica(entreColchetes, marcador) ||
                marcador.contains("evidenciapendente") ||
                marcador.contains("edicaoconsultadanaoidentificada")
            ) {
                return true
            }
            restante = depoisDeAbrir.substring(fecha + 1)
        }
        return false
    }

    /** `compact_ascii_signature`. */
    private fun assinaturaCompacta(valor: String): String {
        val construtor = StringBuilder()
        for (caractere in valor.lowercase()) {
            alfanumericoDobrado(caractere)?.let { construtor.append(it) }
        }
        return construtor.toString()
    }

    /** `is_bibliographic_lacuna_marker`. */
    private fun ehLacunaBibliografica(bruto: String, compacto: String): Boolean {
        if (compacto in MARCADORES_COMPACTOS ||
            compacto.contains("sinedata") ||
            compacto.contains("sineloco") ||
            compacto.contains("sinenomine")
        ) {
            return true
        }

        val tokens = tokensDobrados(bruto)
        if (tokens.zipWithNext().any { it in PARES_DE_LACUNA }) return true

        return temDataIncerta(bruto, tokens)
    }

    private val MARCADORES_COMPACTOS =
        setOf("sd", "nd", "sl", "sn", "slsn", "sineloco", "sinenomine", "sinedata")

    private val PARES_DE_LACUNA = setOf("s" to "d", "n" to "d", "s" to "l", "s" to "n")

    /** `contains_uncertain_date_marker`. */
    private fun temDataIncerta(bruto: String, tokens: List<String>): Boolean {
        if (bruto.none { it in '0'..'9' }) return false
        if (bruto.contains('?') || bruto.contains("--")) return true
        return tokens.windowed(4).any { janela ->
            janela[0] == "entre" &&
                janela[1].all { it in '0'..'9' } &&
                janela[2] == "e" &&
                janela[3].all { it in '0'..'9' }
        }
    }

    /** `ascii_folded_tokens`. */
    private fun tokensDobrados(valor: String): List<String> {
        val tokens = mutableListOf<String>()
        val atual = StringBuilder()
        for (caractere in valor.lowercase()) {
            val dobrado = alfanumericoDobrado(caractere)
            if (dobrado != null) {
                atual.append(dobrado)
            } else if (atual.isNotEmpty()) {
                tokens += atual.toString()
                atual.clear()
            }
        }
        if (atual.isNotEmpty()) tokens += atual.toString()
        return tokens
    }

    /** `ascii_folded_alnum`. */
    private fun alfanumericoDobrado(caractere: Char): Char? = when (caractere) {
        in 'a'..'z', in '0'..'9' -> caractere
        'á', 'à', 'ã', 'â', 'ä' -> 'a'
        'é', 'è', 'ê', 'ë' -> 'e'
        'í', 'ì', 'î', 'ï' -> 'i'
        'ó', 'ò', 'õ', 'ô', 'ö' -> 'o'
        'ú', 'ù', 'û', 'ü' -> 'u'
        'ç' -> 'c'
        else -> null
    }
}
