package dev.lcv.maestro.protocolo

/**
 * O guarda contra empobrecimento: um revisor de nível editorial mais baixo não
 * pode encolher de forma substancial um texto escrito por um de nível mais
 * alto.
 *
 * Porte de `maestro-app/src-tauri/src/session_orchestration.rs` em `3c8babc`
 * (`normalized_editorial_text`, `is_substantive_editorial_change`,
 * `editorial_quality_tier` e `quality_guard_blocks_revision`, 3160–3202), que
 * o web porta byte a byte na mesma seção do contrato do turno.
 */
public object GuardaDeQualidade {

    /**
     * `normalized_editorial_text`: espaço nas pontas, quebra de linha e espaço
     * interno repetido são cosméticos; pontuação e caixa são substância.
     */
    internal fun normalizar(texto: String): String =
        EspacoUnicode.dividirPorEspacos(texto.replace("\r\n", "\n").replace('\r', '\n'))
            .joinToString(" ")

    /** `is_substantive_editorial_change`. */
    public fun mudancaSubstantiva(antes: String, depois: String): Boolean =
        normalizar(antes) != normalizar(depois)

    /** `editorial_quality_tier`. */
    internal fun nivel(agente: String): Int = when (EspacoUnicode.caixaBaixaAscii(agente)) {
        "claude", "codex" -> 3
        "gemini" -> 2
        "deepseek", "grok", "perplexity" -> 1
        else -> 0
    }

    /**
     * `quality_guard_blocks_revision`: recusa quando a mudança é substantiva,
     * o revisor tem nível menor que o autor atual, o texto tinha pelo menos 400
     * pontos de código e encolheu para menos de 85% disso.
     */
    public fun bloqueiaRevisao(
        autorAtual: String?,
        revisor: String,
        antes: String,
        depois: String,
        mudancaSubstantiva: Boolean,
    ): Boolean {
        if (!mudancaSubstantiva) return false
        if (autorAtual == null) return false
        if (nivel(revisor) >= nivel(autorAtual)) return false
        val caracteresAntes = EspacoUnicode.contarPontosDeCodigo(antes).toLong()
        val caracteresDepois = EspacoUnicode.contarPontosDeCodigo(depois).toLong()
        return caracteresAntes >= 400 && caracteresDepois * 100 < caracteresAntes * 85
    }
}
