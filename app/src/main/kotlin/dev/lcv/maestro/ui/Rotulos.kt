/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.protocolo.AcaoDeCorrecao
import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.DecisaoDeRevisao
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.StatusDaRevisao
import dev.lcv.maestro.sessao.Agentes
import dev.lcv.maestro.sessao.Agentes.rotulo
import dev.lcv.maestro.sessao.Estados

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

    // ── Revisão de links: os rótulos do painel de integridade do desktop
    // (`LinkIntegrityPanel.tsx` e `EvidenceScreen.tsx`, `maestro-app` `0e17817`). ──

    /** `classificationLabels` (`LinkIntegrityPanel.tsx:21-37`). */
    fun classificacao(valor: ClassificacaoDoLink): String = when (valor) {
        ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO -> "Verificado e sustenta a afirmação"
        ClassificacaoDoLink.VERIFICADO_MAS_FRACO -> "Verificado, mas fraco"
        ClassificacaoDoLink.REDIRECIONADO_VERIFICADO -> "Redirecionado e verificado"
        ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE -> "Tipo de conteúdo divergente"
        ClassificacaoDoLink.NAO_ENCONTRADO -> "Não encontrado"
        ClassificacaoDoLink.PROIBIDO -> "Acesso proibido"
        ClassificacaoDoLink.EXIGE_AUTENTICACAO -> "Autenticação necessária"
        ClassificacaoDoLink.EXIGE_CAPTCHA -> "CAPTCHA necessário"
        ClassificacaoDoLink.PAYWALL -> "Conteúdo pago"
        ClassificacaoDoLink.TEMPO_ESGOTADO -> "Tempo esgotado"
        ClassificacaoDoLink.ERRO_DE_DNS -> "Falha de DNS"
        ClassificacaoDoLink.ERRO_DE_TLS -> "Falha de TLS"
        ClassificacaoDoLink.MALFORMADO -> "URL malformada"
        ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO -> "Possível alucinação"
        ClassificacaoDoLink.EM_QUARENTENA -> "Em quarentena"
    }

    /** `crossReviewLabels` (`LinkIntegrityPanel.tsx:39-44`). */
    fun revisao(valor: StatusDaRevisao): String = when (valor) {
        StatusDaRevisao.DISPENSADA -> "Não necessária"
        StatusDaRevisao.PENDENTE -> "Pendente"
        StatusDaRevisao.ACEITA -> "Aceita"
        StatusDaRevisao.REJEITADA -> "Rejeitada"
    }

    /** `reviewDecisionLabels` (`LinkIntegrityPanel.tsx:46-50`). */
    fun decisao(valor: DecisaoDeRevisao): String = when (valor) {
        DecisaoDeRevisao.ACEITAR -> "Aceitar"
        DecisaoDeRevisao.REJEITAR -> "Rejeitar"
        DecisaoDeRevisao.QUARENTENA -> "Colocar em quarentena"
    }

    /** `claimSupportLabel` (`LinkIntegrityPanel.tsx:81-88`). */
    fun suporte(linha: LinhaDeLink): String = when (linha.sustentaAfirmacao) {
        null -> "Ainda não julgado editorialmente"
        true -> if (linha.decisaoDeRevisao == DecisaoDeRevisao.ACEITAR) {
            "Suporte à afirmação aceito explicitamente"
        } else {
            "Sinal armazenado sem aceite editorial concluído"
        }
        false -> "Não sustenta a afirmação"
    }

    /** A ação de um candidato de correção (`LinkIntegrityPanel.tsx:548-552`). */
    fun acaoDeCorrecao(valor: AcaoDeCorrecao): String = when (valor) {
        AcaoDeCorrecao.SUBSTITUIR -> "Substituir"
        AcaoDeCorrecao.REMOVER -> "Remover"
        AcaoDeCorrecao.REESCREVER -> "Reformular texto"
    }

    /** `stateLabels` (`EvidenceScreen.tsx:83-91`). */
    fun estadoDaEvidencia(valor: EstadoDaEvidencia): String = when (valor) {
        EstadoDaEvidencia.NA_FILA -> "Na fila"
        EstadoDaEvidencia.COLETANDO -> "Coletando"
        EstadoDaEvidencia.PRONTA -> "Pronta"
        EstadoDaEvidencia.VENCIDA -> "Desatualizada"
        EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR -> "Ação humana"
        EstadoDaEvidencia.BLOQUEADA -> "Bloqueada"
        EstadoDaEvidencia.FALHOU -> "Falhou"
    }

    /**
     * O modo de acesso de uma evidência, que o desktop mostra cru
     * (`EvidenceScreen.tsx:792`). Aqui em português: "Coleta HTTP" e "Captura
     * do operador" são os nomes que a mesma tela usa (292 e 632); os outros
     * dois seguem o mesmo molde.
     */
    fun modoDeAcesso(valor: ModoDeAcesso): String = when (valor) {
        ModoDeAcesso.COLETA_HTTP -> "Coleta HTTP"
        ModoDeAcesso.COLETA_RENDERIZADA -> "Coleta renderizada"
        ModoDeAcesso.API_OFICIAL -> "API oficial"
        ModoDeAcesso.CAPTURA_ASSISTIDA_PELO_OPERADOR -> "Captura do operador"
    }
}
