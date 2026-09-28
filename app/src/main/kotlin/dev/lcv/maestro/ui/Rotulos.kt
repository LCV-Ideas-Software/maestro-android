/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.sessao.Agentes
import dev.lcv.maestro.sessao.Agentes.rotulo
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.MarkdownDoArtefato

/**
 * `statusLabel`, `agentLabel`, `isRunning` e `isResumable` do web
 * (`MaestroAiModule.tsx:205-265`), em Kotlin puro para os testes de JVM. Os
 * rótulos ficam aqui, e não em `strings.xml`, pela mesma razão das mensagens
 * do `:core:sessao`: são dado do porte, conferido palavra por palavra contra
 * a origem, e o produto é só em português.
 */
object Rotulos {

    /** Os 18 rótulos do web, na ordem do web, mais o estado que só existe no Android. */
    val STATUS: Map<String, String> = linkedMapOf(
        "queued" to "Na fila",
        "running" to "Em execução",
        "converged" to "Concluída",
        "paused_cost_limit" to "Pausada por custo",
        "paused_time_limit" to "Pausada por tempo",
        "blocked_max_cycles" to "Sem unanimidade",
        "paused_cycle_limit" to "Limite de turnos atingido",
        "paused_round_incomplete" to "Rodada incompleta",
        "paused_self_review" to "Auto-revisão bloqueada",
        "paused_final_audit" to "Pausada na auditoria final",
        "paused_reviewer_outage" to "Pausada por falha de revisor",
        "paused_draft_unavailable" to "Pausada sem rascunho inicial",
        "paused_resume_state_invalid" to "Pausada por integridade da retomada",
        "blocked_revision_contract" to "Bloqueada por contrato",
        "blocked_link_audit" to "Bloqueada por link inválido",
        "blocked_cancelled" to "Cancelada",
        "error" to "Erro",
        // Só no Android (especificação, seção 6.2; revisão cruzada de 28/09/2026, emenda A6).
        Estados.AGUARDANDO_AUTENTICACAO to "Pausada aguardando autenticação",
    )

    /** `statusLabel[status] || status`. */
    fun status(status: String): String = STATUS[status] ?: status

    /** `agentLabel`: o nome do agente, o próprio valor quando desconhecido, ou "Maestro AI" quando vazio. */
    fun agente(agente: String?): String = Agentes.porChave(agente)?.rotulo ?: agente?.takeIf { it.isNotEmpty() } ?: "Maestro AI"

    /** `isRunning`. */
    fun emExecucao(status: String?): Boolean = status in Estados.ATIVOS

    /**
     * `isResumable`, com o conjunto do Android: [Estados.RETOMAVEIS] inclui
     * `pausada_aguardando_autenticacao`, que o web não tem (emenda A6).
     */
    fun retomavel(status: String?, textoFinal: String?): Boolean = status in Estados.RETOMAVEIS && textoFinal == null

    /**
     * A pílula da aba **Links**: `ok ? 'válido' : status ? 'inválido <status>' :
     * error || 'inválido'`. O `ok` do web é a linha que o motor não conta como
     * inválida — a mesma regra que conta os links inválidos do artefato
     * (`MarkdownDoArtefato.contarInvalidos`) —, e o `error` é o motivo que o
     * motor grava.
     */
    fun link(linha: LinhaDeLink): String = when {
        MarkdownDoArtefato.contarInvalidos(listOf(linha)) == 0 -> "válido"
        linha.statusHttp != null -> "inválido ${linha.statusHttp}"
        linha.invalidade.isNotEmpty() -> linha.invalidade
        else -> "inválido"
    }
}
