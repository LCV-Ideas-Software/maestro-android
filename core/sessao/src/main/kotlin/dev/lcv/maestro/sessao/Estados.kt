package dev.lcv.maestro.sessao

/**
 * Os estados da sessão, com os nomes do web, e os dois conjuntos que decidem
 * o que pode ser retomado e o que ainda roda.
 */
public object Estados {
    public const val NA_FILA: String = "queued"
    public const val RODANDO: String = "running"
    public const val ERRO: String = "error"
    public const val CANCELADA: String = "blocked_cancelled"
    public const val RETOMADA_INVALIDA: String = "paused_resume_state_invalid"
    public const val LIMITE_DE_CICLOS: String = "paused_cycle_limit"

    /**
     * Só no Android: a janela de autenticação do Keystore venceu no meio da
     * sessão; a chave está intacta e o que se pede é autenticação, nunca a
     * chave (especificação, seções 4.2 e 6.2; revisão cruzada de 25/09/2026,
     * emenda A1).
     */
    public const val AGUARDANDO_AUTENTICACAO: String = "pausada_aguardando_autenticacao"
    public const val MENSAGEM_AGUARDANDO_AUTENTICACAO: String = "Sessao pausada aguardando autenticacao do usuario."

    /** `ACTIVE_SESSION_STATUSES` (`sessions.ts:4695`). */
    public val ATIVOS: Set<String> = setOf(NA_FILA, RODANDO)

    /**
     * `RESUMABLE_STATUSES` (`sessions.ts:2828-2842`), os treze do web — `error`
     * incluído, que é o que a varredura do web e a reconciliação daqui gravam —
     * mais o de autenticação, que só existe aqui.
     */
    public val RETOMAVEIS: Set<String> = setOf(
        "paused_cost_limit",
        "paused_time_limit",
        LIMITE_DE_CICLOS,
        "paused_round_incomplete",
        "paused_final_audit",
        "paused_self_review",
        "paused_reviewer_outage",
        "paused_draft_unavailable",
        RETOMADA_INVALIDA,
        CANCELADA,
        "blocked_max_cycles",
        "blocked_link_audit",
        ERRO,
        AGUARDANDO_AUTENTICACAO,
    )
}
