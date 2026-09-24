package dev.lcv.maestro.protocolo

/*
 * Os tipos da integridade de links e do registro de evidência web, portados
 * de `maestro-app/src-tauri/src/lib.rs` (linhas 290–446) e de
 * `web_evidence.rs` (linhas 59–179), em `68528f9`.
 *
 * Cada enum guarda o nome do `serde` (`snake_case`; o método HTTP em
 * `UPPERCASE`), e cada classe sabe virar [ValorJson] com os nomes de campo do
 * canônico, porque as linhas de link entram no contexto de falha da auditoria
 * final que vai ao prompt.
 */

/** `LinkClassification`. */
public enum class ClassificacaoDoLink(internal val json: String) {
    VERIFICADO_SUSTENTA_A_AFIRMACAO("verified_supports_claim"),
    VERIFICADO_MAS_FRACO("verified_but_weak"),
    REDIRECIONADO_VERIFICADO("redirected_verified"),
    TIPO_DE_CONTEUDO_DIVERGENTE("content_type_mismatch"),
    NAO_ENCONTRADO("not_found"),
    PROIBIDO("forbidden"),
    EXIGE_AUTENTICACAO("auth_required"),
    EXIGE_CAPTCHA("captcha_required"),
    PAYWALL("paywall"),
    TEMPO_ESGOTADO("timeout"),
    ERRO_DE_DNS("dns_error"),
    ERRO_DE_TLS("tls_error"),
    MALFORMADO("malformed"),
    SUSPEITA_DE_ALUCINACAO("suspected_hallucination"),
    EM_QUARENTENA("quarantined"),
}

/** `LinkCrossReviewStatus`. */
public enum class StatusDaRevisao(internal val json: String) {
    DISPENSADA("not_needed"),
    PENDENTE("pending"),
    ACEITA("accepted"),
    REJEITADA("rejected"),
}

/** `LinkReviewDecision`. */
public enum class DecisaoDeRevisao(internal val json: String) {
    ACEITAR("accept"),
    REJEITAR("reject"),
    QUARENTENA("quarantine"),
}

/** `LinkCorrectionAction`. */
public enum class AcaoDeCorrecao(internal val json: String) {
    SUBSTITUIR("replace"),
    REMOVER("remove"),
    REESCREVER("reword"),
}

/** `LinkEvidenceRedirect` e `WebEvidenceRedirect`, que têm a mesma forma. */
public data class Redirecionamento(val url: String, val status: Int) {
    internal fun json(): ValorJson =
        ValorJson.objeto("url" to ValorJson.texto(url), "status" to ValorJson.numero(status))
}

/** `LinkCorrectionCandidate`. */
public data class CandidatoDeCorrecao(
    val candidatoId: String,
    val acao: AcaoDeCorrecao,
    val url: String?,
    val titulo: String?,
    val provedor: String,
    val consulta: String?,
    val evidenciaWebId: String?,
    val justificativa: String,
    val propostoEm: String,
) {
    internal fun json(): ValorJson = ValorJson.objeto(
        "candidate_id" to ValorJson.texto(candidatoId),
        "action" to ValorJson.texto(acao.json),
        "url" to ValorJson.texto(url),
        "title" to ValorJson.texto(titulo),
        "provider" to ValorJson.texto(provedor),
        "query" to ValorJson.texto(consulta),
        "web_evidence_id" to ValorJson.texto(evidenciaWebId),
        "rationale" to ValorJson.texto(justificativa),
        "proposed_at" to ValorJson.texto(propostoEm),
    )
}

/**
 * `LinkAuditRow`: um link do texto, com a evidência mecânica e a revisão
 * editorial. Os quatro últimos campos são a projeção legada do `audit_links`,
 * que o canônico mantém estável.
 */
public data class LinhaDeLink(
    val versaoDoEsquema: String,
    val linkId: String,
    val artefatoDeOrigem: String,
    val impressaoDaOrigem: String,
    val textoDaAncora: String?,
    val textoAoRedor: String,
    val urlOriginal: String,
    val urlNormalizada: String,
    val mudancasDaNormalizacao: List<String>,
    val urlFinal: String?,
    val cadeiaDeRedirecionamento: List<Redirecionamento>,
    val statusHttp: Int?,
    val tipoDeConteudo: String?,
    val sha256: String?,
    val verificadoEm: String,
    val sustentaAfirmacao: Boolean?,
    val classificacao: ClassificacaoDoLink,
    val candidatosDeCorrecao: List<CandidatoDeCorrecao>,
    val statusDaRevisao: StatusDaRevisao,
    val decisaoDeRevisao: DecisaoDeRevisao?,
    val revisadoPor: String?,
    val notaDaRevisao: String?,
    val revisadoEm: String?,
    val evidenciaWebId: String?,
    val url: String,
    val status: String,
    val invalidade: String,
    val tom: String,
) {
    internal fun json(): ValorJson = ValorJson.objeto(
        "schema_version" to ValorJson.texto(versaoDoEsquema),
        "link_id" to ValorJson.texto(linkId),
        "source_artifact" to ValorJson.texto(artefatoDeOrigem),
        "source_fingerprint" to ValorJson.texto(impressaoDaOrigem),
        "anchor_text" to ValorJson.texto(textoDaAncora),
        "surrounding_text" to ValorJson.texto(textoAoRedor),
        "original_url" to ValorJson.texto(urlOriginal),
        "normalized_url" to ValorJson.texto(urlNormalizada),
        "normalization_changes" to ValorJson.textos(mudancasDaNormalizacao),
        "final_url" to ValorJson.texto(urlFinal),
        "redirect_chain" to ValorJson.Lista(cadeiaDeRedirecionamento.map { it.json() }),
        "http_status" to ValorJson.numero(statusHttp),
        "content_type" to ValorJson.texto(tipoDeConteudo),
        "sha256" to ValorJson.texto(sha256),
        "checked_at" to ValorJson.texto(verificadoEm),
        "claim_supported" to ValorJson.logico(sustentaAfirmacao),
        "classification" to ValorJson.texto(classificacao.json),
        "correction_candidates" to ValorJson.Lista(candidatosDeCorrecao.map { it.json() }),
        "cross_review_status" to ValorJson.texto(statusDaRevisao.json),
        "review_decision" to ValorJson.texto(decisaoDeRevisao?.json),
        "reviewed_by" to ValorJson.texto(revisadoPor),
        "review_note" to ValorJson.texto(notaDaRevisao),
        "reviewed_at" to ValorJson.texto(revisadoEm),
        "web_evidence_id" to ValorJson.texto(evidenciaWebId),
        "url" to ValorJson.texto(url),
        "status" to ValorJson.texto(status),
        "invalidity" to ValorJson.texto(invalidade),
        "tone" to ValorJson.texto(tom),
    )
}

/** `LinkAuditResult`. */
public data class ResultadoDosLinks(
    val versaoDoEsquema: String,
    val auditId: String,
    val artefatoDeOrigem: String,
    val verificadoEm: String,
    val urlsEncontradas: Int,
    val verificadas: Int,
    val ok: Int,
    val falhas: Int,
    val revisaoPendente: Int,
    val bloqueadas: Int,
    val linhas: List<LinhaDeLink>,
)

/** `WebEvidenceMethod`, com o `rename_all = "UPPERCASE"` do canônico. */
public enum class MetodoHttp(internal val json: String) { GET("GET"), HEAD("HEAD") }

/** `WebEvidenceAccessMode`. */
public enum class ModoDeAcesso(internal val json: String) {
    COLETA_HTTP("http_fetch"),
    COLETA_RENDERIZADA("rendered_fetch"),
    API_OFICIAL("official_api"),
    CAPTURA_ASSISTIDA_PELO_OPERADOR("operator_assisted_browser_capture"),
}

/** `WebEvidenceState`. */
public enum class EstadoDaEvidencia(internal val json: String) {
    NA_FILA("queued"),
    COLETANDO("collecting"),
    PRONTA("ready"),
    VENCIDA("stale"),
    EXIGE_ACAO_DO_OPERADOR("operator_action_required"),
    BLOQUEADA("blocked"),
    FALHOU("failed"),
}

/** `WebEvidenceCacheState`. */
public enum class EstadoDoCache(internal val json: String) {
    FRESCO("fresh"),
    VENCIDO("stale"),
    REVALIDANDO("revalidating"),
    AUSENTE("missing"),
}

/** `WebEvidenceRobotsState`. */
public enum class EstadoDoRobots(internal val json: String) {
    PERMITIDO("allowed"),
    PROIBIDO("disallowed"),
    INDISPONIVEL("unavailable"),
    NAO_SE_APLICA("not_applicable"),
}

/** `WebEvidenceCopyrightState`. */
public enum class EstadoDosDireitos(internal val json: String) {
    PUBLICO("public"),
    LICENCIADO("licensed"),
    FORNECIDO_PELO_OPERADOR("operator_provided"),
    DESCONHECIDO("unknown"),
}

/** `WebEvidenceInteractionState`. */
public enum class EstadoDeInteracao(internal val json: String) {
    NENHUMA("none"),
    EXIGE_CAPTCHA("captcha_required"),
    EXIGE_LOGIN("login_required"),
    EXIGE_CONSENTIMENTO("consent_required"),
    CONFIRMAR_DOWNLOAD("download_confirmation"),
    PAYWALL("paywall"),
    RESOLVIDA_POR_PESSOA("human_resolved"),
}

/** `WebEvidenceRecord`: uma evidência web coletada, com a proveniência. */
public data class RegistroDeEvidencia(
    val id: String,
    val versaoDoEsquema: String,
    val estado: EstadoDaEvidencia,
    val url: String,
    val metodo: MetodoHttp,
    val modoDeAcesso: ModoDeAcesso,
    val status: Int?,
    val urlFinal: String?,
    val titulo: String?,
    val tipoDeConteudo: String?,
    val sha256: String?,
    val coletadaEm: String?,
    val expiraEm: String?,
    val validadeDoCache: String,
    val estadoDoCache: EstadoDoCache,
    val estadoDoRobots: EstadoDoRobots,
    val estadoDosDireitos: EstadoDosDireitos,
    val estadoDeInteracao: EstadoDeInteracao,
    val resolvidaPorPessoa: Boolean,
    val bytes: Long?,
    val duracaoMs: Long?,
    val cadeiaDeRedirecionamento: List<Redirecionamento>,
    val comandoCurl: String?,
    val provedor: String?,
    val consulta: String?,
    val nomeDoArtefato: String?,
    val notas: List<String>,
    val criadaEm: String,
    val atualizadaEm: String,
)
