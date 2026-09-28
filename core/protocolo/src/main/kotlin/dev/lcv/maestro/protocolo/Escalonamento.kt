package dev.lcv.maestro.protocolo

/**
 * A convergência por aprovações estáveis e a escolha do revisor de cada turno
 * (`sessions.ts:1634-1701`, porte do web de `session_orchestration.rs:2595-2671`).
 *
 * Convergiu quando todo agente da escala que não é o autor da versão atual está
 * no conjunto de aprovações estáveis; o líder do rascunho (redator de
 * fechamento) só é escalável depois que todos os outros completaram um turno
 * válido nesta rodada; uma vaga nominal inelegível é redesenhada
 * pseudoaleatoriamente entre os pendentes. As funções são puras e genéricas
 * no tipo do agente: o `:core:sessao` as chama com `Provedor`.
 */
public object Escalonamento {

    /** `reviewerRedrawReason`: os quatro motivos, com os nomes do web. */
    public const val NOMINAL_E_O_AUTOR: String = "nominal_reviewer_is_current_author"
    public const val FECHAMENTO_ESPERA_O_CIRCUITO: String = "original_author_closure_waiting_for_full_peer_circuit"
    public const val NOMINAL_JA_APROVOU: String = "nominal_reviewer_already_approved_current_version"
    public const val NOMINAL_INELEGIVEL: String = "nominal_reviewer_ineligible"

    /** `hasAllIndependentApprovals`: sem autor ainda (rascunho não produzido), nunca convergiu. */
    public fun <T : Any> temTodasAsAprovacoesIndependentes(escala: List<T>, autorAtual: T?, estaveis: Set<T>): Boolean {
        if (autorAtual == null) return false
        val exigidos = escala.filter { it != autorAtual }
        return exigidos.isNotEmpty() && exigidos.all { it in estaveis }
    }

    /** `closingTurnHasRequiredPriorReviews`: uma escala só com o líder nunca está pronta para fechar. */
    public fun <T : Any> fechamentoTemRevisoesPrevias(escala: List<T>, lider: T, validos: Set<T>, autorAtual: T?): Boolean {
        val exigidos = escala.filter { it != lider && it != autorAtual }
        return escala.any { it != lider } && exigidos.all { it in validos }
    }

    /**
     * `selectSerialReviewerIndex`: o índice da vaga nominal se ela está
     * pendente; senão um pendente escolhido por `semente % pendentes`; `null`
     * quando ninguém está pendente. [semente] é um valor de justiça de
     * escalonamento, não criptográfico (o web usa `Math.random`).
     */
    public fun <T : Any> escolherRevisor(
        escala: List<T>,
        indiceNominal: Int,
        autorAtual: T,
        lider: T,
        validos: Set<T>,
        estaveis: Set<T>,
        semente: Long,
    ): Int? {
        if (escala.isEmpty()) return null
        val indice = Math.floorMod(indiceNominal, escala.size)
        val fechamentoPronto = fechamentoTemRevisoesPrevias(escala, lider, validos, autorAtual)
        fun pendente(agente: T): Boolean = agente != autorAtual && agente !in estaveis && (agente != lider || fechamentoPronto)
        if (pendente(escala[indice])) return indice
        val pendentes = escala.indices.filter { pendente(escala[it]) }
        if (pendentes.isEmpty()) return null
        return pendentes[Math.floorMod(semente, pendentes.size.toLong()).toInt()]
    }

    /** `reviewerRedrawReason`: por que a vaga nominal não foi usada. */
    public fun <T : Any> motivoDoRedesenho(
        escala: List<T>,
        indiceNominal: Int,
        autorAtual: T,
        lider: T,
        validos: Set<T>,
        estaveis: Set<T>,
    ): String {
        val nominal = escala.getOrNull(Math.floorMod(indiceNominal, maxOf(1, escala.size)))
        if (nominal == autorAtual) return NOMINAL_E_O_AUTOR
        if (nominal == lider && !fechamentoTemRevisoesPrevias(escala, lider, validos, autorAtual)) return FECHAMENTO_ESPERA_O_CIRCUITO
        if (nominal != null && nominal in estaveis) return NOMINAL_JA_APROVOU
        return NOMINAL_INELEGIVEL
    }
}
