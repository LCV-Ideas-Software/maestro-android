package dev.lcv.maestro.protocolo

import java.time.Instant

/**
 * A auditoria do candidato a texto final: o portão que decide se um texto pode
 * ser entregue. Porte de `maestro-app/src-tauri/src/session_orchestration.rs`
 * (linhas 2611–2766 e 2927–3087 em `68528f9`), com os cinco estágios do Rust
 * atual, por decisão do operador de 24/09/2026 (MAEANDR-18):
 *
 * 1. integridade bibliográfica — marcador de evidência pendente ou lacuna
 *    ([IntegridadeBibliografica]);
 * 2. citações ABNT — sem manifesto, toda citação detectada bloqueia
 *    ([AuditoriaAbnt]);
 * 3. capacidade — no máximo [IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS] links;
 * 4. o motor de integridade de links tem de responder;
 * 5. nenhum link sai sem revisão explícita contra a URL e o hash atuais.
 *
 * Os estágios curto-circuitam na ordem: o primeiro que falha decide. Cada
 * falha traz o motivo e o contexto que o canônico põe no prompt do turno
 * seguinte, com as chaves na ordem do `serde_json` ([ValorJson]).
 */
public object AuditoriaFinal {

    /** Uma recusa: o motivo e o pacote determinístico que vai ao agente. */
    public data class Falha(val motivo: String, val contexto: ValorJson)

    /** `run_link_integrity_audit`, já com os colaboradores amarrados. */
    public fun interface MotorDeLinks {
        /** O resultado, ou lança [IntegridadeDeLinks.Falha]. */
        public fun auditar(texto: String): ResultadoDosLinks
    }

    private fun texto(valor: String?) = ValorJson.texto(valor)

    /** `final_release_audit_failure`: a auditoria sem manifesto. */
    public fun falha(texto: String, motorDeLinks: MotorDeLinks, agora: Instant): Falha? =
        falhaComCitacoes(texto, null, null, null, motorDeLinks, agora)

    /** `final_release_audit_failure_with_citations`. */
    public fun falhaComCitacoes(
        texto: String,
        hashDoProtocolo: String?,
        manifesto: ManifestoDeCitacoes?,
        manifestoAnterior: ManifestoDeCitacoes?,
        motorDeLinks: MotorDeLinks,
        agora: Instant,
    ): Falha? {
        IntegridadeBibliografica.validarCandidato(texto)?.let { motivo ->
            return Falha(
                motivo,
                ValorJson.objeto(
                    "gate" to texto("bibliographic_integrity"),
                    "policy" to texto("final_text_must_not_hide_unverified_references"),
                ),
            )
        }
        val citacoes = when (val saida = AuditoriaAbnt.auditar(texto, hashDoProtocolo, manifesto, manifestoAnterior, agora)) {
            is AuditoriaAbnt.Saida.Concluida -> saida.resultado
            is AuditoriaAbnt.Saida.Recusada -> return Falha(
                "final candidate ABNT citation engine failed closed",
                ValorJson.objeto(
                    "gate" to texto("abnt_citation_engine"),
                    "error" to texto(Saneamento.texto(saida.motivo, 500)),
                    "policy" to texto("maestro_peer_must_be_observable_before_release"),
                ),
            )
        }
        if (AuditoriaAbnt.bloqueiaLiberacao(citacoes)) {
            return Falha(
                "final candidate has unresolved deterministic citation evidence",
                ValorJson.objeto(
                    "gate" to texto("abnt_citation"),
                    "audit_id" to texto(citacoes.auditId),
                    "protocol_hash" to texto(citacoes.hashDoProtocolo),
                    "maestro_peer_status" to texto(citacoes.statusDoParMaestro.json),
                    "citations" to ValorJson.Lista(citacoes.citacoes.map { it.json() }),
                    "normalized_references" to ValorJson.textos(citacoes.referenciasNormalizadas),
                    "markdown_references" to ValorJson.textos(citacoes.referenciasMarkdown),
                    "html_references" to ValorJson.textos(citacoes.referenciasHtml),
                    "blockers" to ValorJson.Lista(citacoes.bloqueios.map { it.json() }),
                    "audit_table_markdown" to texto(citacoes.tabelaMarkdown),
                    "semantic_diff" to texto(citacoes.diffSemantico),
                    "required_action" to
                        texto("correct_format_verify_quarantine_or_supply_citation_manifest_then_revalidate"),
                    "policy" to texto("all_active_ai_peers_and_maestro_peer_must_be_ready"),
                ),
            )
        }
        val ocorrencias = IntegridadeDeLinks.contarOcorrencias(texto)
        if (ocorrencias > IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS) {
            return Falha(
                "final candidate exceeds link audit capacity",
                ValorJson.objeto(
                    "gate" to texto("link_audit_capacity"),
                    "link_occurrences_found" to ValorJson.numero(ocorrencias),
                    "max_link_occurrences" to ValorJson.numero(IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS),
                    "policy" to texto("final_text_must_not_contain_unaudited_public_links"),
                ),
            )
        }
        val links = try {
            motorDeLinks.auditar(texto)
        } catch (erro: IntegridadeDeLinks.Falha) {
            return Falha(
                "final candidate link-integrity engine failed closed",
                ValorJson.objeto(
                    "gate" to texto("link_integrity_engine"),
                    "error" to texto(Saneamento.texto(erro.message.orEmpty(), 500)),
                    "policy" to texto("link_integrity_must_be_observable_before_release"),
                ),
            )
        }
        if (IntegridadeDeLinks.exigeResolucaoEditorial(links)) {
            return Falha(
                "final candidate has unresolved link-integrity evidence",
                ValorJson.objeto(
                    "gate" to texto("link_integrity"),
                    "urls_found" to ValorJson.numero(links.urlsEncontradas),
                    "checked" to ValorJson.numero(links.verificadas),
                    "ok" to ValorJson.numero(links.ok),
                    "failed" to ValorJson.numero(links.falhas),
                    "pending_review" to ValorJson.numero(links.revisaoPendente),
                    "blocked" to ValorJson.numero(links.bloqueadas),
                    "rows" to ValorJson.Lista(links.linhas.map { it.json() }),
                    "required_action" to
                        texto("correct_remove_reword_quarantine_or_record_explicit_review_then_revalidate"),
                    "policy" to texto("only_reviewed_current_url_and_hash_links_may_be_released"),
                ),
            )
        }
        return null
    }

    /**
     * Os bloqueios de citação que pedem ação do operador — evidência ou um
     * manifesto novo —, e não mais turnos pagos.
     */
    private val CODIGOS_DE_ATUALIZACAO_DO_MANIFESTO = setOf(
        "manifest_schema_invalid",
        "manifest_protocol_hash_missing",
        "protocol_hash_mismatch",
        "manifest_capacity_exceeded",
        "source_id_missing",
        "source_id_duplicate",
        "claim_id_missing",
        "claim_id_duplicate",
        "citation_schema_invalid",
        "citation_source_missing",
        "citation_canonical_author_mismatch",
        "canonical_author_mismatch",
        "canonical_author_key_malformed",
        "canonical_author_display_mismatch",
        "source_quarantined",
        "prohibited_source",
        "reference_without_body_use",
        "reference_not_in_manifest",
    )

    /**
     * `citation_operator_evidence_failure`: a sessão pausa antes do próximo
     * revisor pago quando o que falta só o operador pode dar. Se o motor ABNT
     * recusar a auditoria, não há o que dizer aqui (`.ok()?`).
     */
    public fun falhaDeEvidenciaDoOperador(
        texto: String,
        hashDoProtocolo: String?,
        manifesto: ManifestoDeCitacoes?,
        manifestoAnterior: ManifestoDeCitacoes?,
        agora: Instant,
    ): Falha? {
        val auditoria = (AuditoriaAbnt.auditar(texto, hashDoProtocolo, manifesto, manifestoAnterior, agora)
            as? AuditoriaAbnt.Saida.Concluida)?.resultado ?: return null
        val exige = auditoria.bloqueios.any { it.exigeEvidencia || it.codigo in CODIGOS_DE_ATUALIZACAO_DO_MANIFESTO }
        if (!exige) return null
        return Falha(
            "citation gate requires operator evidence or an updated citation manifest",
            ValorJson.objeto(
                "gate" to texto("abnt_citation_operator_evidence"),
                "audit_id" to texto(auditoria.auditId),
                "protocol_hash" to texto(auditoria.hashDoProtocolo),
                "maestro_peer_status" to texto(auditoria.statusDoParMaestro.json),
                "blockers" to ValorJson.Lista(auditoria.bloqueios.map { it.json() }),
                "normalized_references" to ValorJson.textos(auditoria.referenciasNormalizadas),
                "audit_table_markdown" to texto(auditoria.tabelaMarkdown),
                "semantic_diff" to texto(auditoria.diffSemantico),
                "required_action" to
                    texto("attach_or_replace_citation_manifest_then_resume; do_not_spend_additional_peer_turns"),
                "policy" to texto("operator_evidence_blockers_pause_before_the_next_paid_reviewer"),
            ),
        )
    }

    // ── o turno que não revisou o texto (linhas 2611–2766) ───────────────────

    private const val MOTIVO_NAO_PRONTO_SEM_MUDANCA =
        "NOT_READY unchanged is not a valid serial-review outcome: the reviewer must either return READY " +
            "unchanged when no blocker remains, or return a revised complete text that resolves the concrete " +
            "blocker."

    private fun contextoNaoProntoSemMudanca(status: String, saida: TurnoSerial.Saida) = ValorJson.objeto(
        "kind" to texto("not_ready_unchanged_without_corrective_text"),
        "status" to texto(status),
        "has_operator_evidence_required" to ValorJson.logico(saida.exigeEvidenciaDoOperador),
        "policy" to texto("detector_must_correct_or_approve_current_version"),
    )

    /** `UnrevisedSerialTurnAuditDecision`. */
    public enum class DecisaoDoTurnoSemRevisao { PRONTO_RECUSADO, REPETICAO_CORRETIVA_EXIGIDA }

    /** A decisão, o motivo e o contexto. */
    public data class Decisao(val decisao: DecisaoDoTurnoSemRevisao, val motivo: String, val contexto: ValorJson)

    /**
     * O que a sessão sabe das citações: o hash do protocolo e os manifestos
     * anexados. Sem anexo, a sessão do canônico usa um manifesto vazio preso
     * ao hash do protocolo ([AuditoriaAbnt.manifestoVazio]).
     *
     * Divergência do canônico, corrigindo uma falha dele: lá os auxiliares
     * abaixo chamam `final_release_audit_failure`, que audita **sem
     * manifesto**, e um revisor `READY` sem mudança sobre texto com manifesto
     * válido é recusado por `structured_manifest_missing`. O laço principal do
     * canônico escapa porque passa o resultado já calculado com o manifesto
     * ([decisaoComFalhaDaLiberacao]); a retomada da sessão
     * (`restore_circular_resume_progress`) não escapa. Aqui os auxiliares
     * recebem este contexto.
     */
    public data class ContextoDeCitacoes(
        val hashDoProtocolo: String?,
        val manifesto: ManifestoDeCitacoes?,
        val manifestoAnterior: ManifestoDeCitacoes?,
    )

    private fun falhaNoContexto(texto: String, citacoes: ContextoDeCitacoes, motor: MotorDeLinks, agora: Instant) =
        falhaComCitacoes(texto, citacoes.hashDoProtocolo, citacoes.manifesto, citacoes.manifestoAnterior, motor, agora)

    /** `ready_unchanged_release_audit_failure`, com as citações da sessão. */
    public fun falhaDeProntoSemMudanca(
        status: String,
        saida: TurnoSerial.Saida,
        rascunhoAtual: String,
        citacoes: ContextoDeCitacoes,
        motorDeLinks: MotorDeLinks,
        agora: Instant,
    ): Falha? = if (status == "READY" && saida.textoFinal == null) {
        falhaNoContexto(rascunhoAtual, citacoes, motorDeLinks, agora)
    } else {
        null
    }

    /** `not_ready_unchanged_release_audit_failure`, com as citações da sessão. */
    public fun falhaDeNaoProntoSemMudanca(
        status: String,
        saida: TurnoSerial.Saida,
        rascunhoAtual: String,
        citacoes: ContextoDeCitacoes,
        motorDeLinks: MotorDeLinks,
        agora: Instant,
    ): Falha? {
        if (status != "NOT_READY" || saida.textoFinal != null) return null
        return falhaNoContexto(rascunhoAtual, citacoes, motorDeLinks, agora)
            ?: Falha(MOTIVO_NAO_PRONTO_SEM_MUDANCA, contextoNaoProntoSemMudanca(status, saida))
    }

    /** `unrevised_serial_turn_audit_decision`, com as citações da sessão. */
    public fun decisaoDoTurnoSemRevisao(
        status: String,
        saida: TurnoSerial.Saida,
        rascunhoAtual: String,
        citacoes: ContextoDeCitacoes,
        motorDeLinks: MotorDeLinks,
        agora: Instant,
    ): Decisao? {
        val falhaDaLiberacao = if (saida.textoFinal == null) {
            falhaNoContexto(rascunhoAtual, citacoes, motorDeLinks, agora)
        } else {
            null
        }
        return decisaoComFalhaDaLiberacao(status, saida, falhaDaLiberacao)
    }

    /** `unrevised_serial_turn_audit_decision_with_release_failure`. */
    public fun decisaoComFalhaDaLiberacao(status: String, saida: TurnoSerial.Saida, falhaDaLiberacao: Falha?): Decisao? {
        if (saida.textoFinal != null) return null
        if (status == "READY") {
            return falhaDaLiberacao?.let {
                Decisao(DecisaoDoTurnoSemRevisao.PRONTO_RECUSADO, it.motivo, it.contexto)
            }
        }
        if (status == "NOT_READY") {
            val falha = falhaDaLiberacao
                ?: Falha(MOTIVO_NAO_PRONTO_SEM_MUDANCA, contextoNaoProntoSemMudanca(status, saida))
            return Decisao(DecisaoDoTurnoSemRevisao.REPETICAO_CORRETIVA_EXIGIDA, falha.motivo, falha.contexto)
        }
        return null
    }

    /** `serial_turn_counts_as_valid_round_agent`, com as citações da sessão. */
    public fun contaComoAgenteValidoDaRodada(
        status: String,
        saida: TurnoSerial.Saida,
        rascunhoAtual: String,
        citacoes: ContextoDeCitacoes,
        motorDeLinks: MotorDeLinks,
        agora: Instant,
    ): Boolean =
        falhaDeProntoSemMudanca(status, saida, rascunhoAtual, citacoes, motorDeLinks, agora) == null &&
            falhaDeNaoProntoSemMudanca(status, saida, rascunhoAtual, citacoes, motorDeLinks, agora) == null
}
