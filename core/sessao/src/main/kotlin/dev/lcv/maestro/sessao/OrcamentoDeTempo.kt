package dev.lcv.maestro.sessao

import java.time.Instant

/**
 * `remainingSessionMs` e `sessionTimeExhausted` (`sessions.ts:1083-1093`):
 * `null` é sem limite; esgotado é "restam menos de 2 s", conferido antes do
 * rascunho e antes de cada turno. A âncora é `criadaEm` numa execução nova e
 * o instante da retomada numa retomada (`sessions.ts:3384-3386`). O relógio é
 * injetado (especificação, seção 8).
 */
public object OrcamentoDeTempo {
    public const val CORTE_MS: Long = 2_000

    public fun restanteMs(ancora: Instant, tetoDeMinutos: Int?, agora: Instant): Long? {
        if (tetoDeMinutos == null || tetoDeMinutos <= 0) return null
        val fim = ancora.toEpochMilli() + tetoDeMinutos.toLong() * 60_000
        return maxOf(0L, fim - agora.toEpochMilli())
    }

    public fun esgotado(ancora: Instant, tetoDeMinutos: Int?, agora: Instant): Boolean {
        val restante = restanteMs(ancora, tetoDeMinutos, agora) ?: return false
        return restante < CORTE_MS
    }
}
