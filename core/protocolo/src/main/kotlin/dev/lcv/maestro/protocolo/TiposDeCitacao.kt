package dev.lcv.maestro.protocolo

/*
 * Os tipos do motor de citações ABNT, portados de
 * `maestro-app/src-tauri/src/abnt_citation.rs` (linhas 24–202 em `68528f9`).
 *
 * Cada enum guarda o nome que o `serde` usa no JSON (`rename_all =
 * "snake_case"`) e o nome que o `{:?}` do Rust imprime na tabela de auditoria,
 * porque os dois aparecem em saídas que o canônico produz. A ordem dos campos
 * de cada classe é a do `struct` do canônico: o hash do manifesto depende dela
 * (ver [JsonRust]).
 */

/** `CitationType`. */
public enum class TipoDeCitacao(internal val json: String, internal val depuracao: String) {
    CITACAO_DIRETA("direct_quote", "DirectQuote"),
    CITACAO_INDIRETA("indirect_quote", "IndirectQuote"),
    PARAFRASE("paraphrase", "Paraphrase"),
    APUD("apud", "Apud"),
    MENCAO_GENERICA("generic_mention", "GenericMention"),
}

/** `CitationSourceAccess`. */
public enum class AcessoAFonte(internal val json: String) {
    DOCUMENTO_INTEGRAL_ABERTO("full_document_opened"),
    EXCERTO_CONSULTADO("excerpt_consulted"),
    MEMORIA_CONSOLIDADA("consolidated_memory"),
    INFERENCIA_CONTEXTUAL("contextual_inference"),
    HIPOTESE_NAO_VERIFICADA("unverified_hypothesis"),
}

/** `CitationVerificationStatus`. */
public enum class StatusDeVerificacao(internal val json: String, internal val depuracao: String) {
    VERIFICADA("verified", "Verified"),
    EXIGE_EVIDENCIA("needs_evidence", "NeedsEvidence"),
    EM_QUARENTENA("quarantined", "Quarantined"),
}

/** `CitationRisk`. */
public enum class RiscoSeErrada(internal val json: String) {
    BAIXO("low"),
    MEDIO("medium"),
    ALTO("high"),
}

/** `MaestroPeerStatus`: o veredito do par determinístico do Maestro. */
public enum class StatusDoParMaestro(internal val json: String) {
    PRONTO("ready"),
    NAO_PRONTO("not_ready"),
    EXIGE_EVIDENCIA("needs_evidence"),
}

/** `CitationSourceType`. */
public enum class TipoDeFonte(internal val json: String) {
    LIVRO("book"),
    CAPITULO("chapter"),
    ARTIGO("article"),
    ONLINE("online"),
    OUTRO("other"),
}

/** `CitationAuditCitation` (`citation.v1`). */
public data class Citacao(
    val versaoDoEsquema: String,
    val claimId: String,
    val tipo: TipoDeCitacao,
    val autorExibido: String,
    val chaveDoAutor: String,
    val ano: String,
    val localizador: String?,
    val fonteId: String,
    val acesso: AcessoAFonte,
    val verificacao: StatusDeVerificacao,
    val riscoSeErrada: RiscoSeErrada,
    val textoOriginal: String?,
    val textoNormalizado: String?,
    val notaNormalizada: String?,
) {
    internal fun escrever(json: JsonRust) = json.objeto {
        campo("schema_version", versaoDoEsquema)
        campo("claim_id", claimId)
        campo("citation_type", tipo.json)
        campo("author_display", autorExibido)
        campo("author_key", chaveDoAutor)
        campo("year", ano)
        campo("locator", localizador)
        campo("source_id", fonteId)
        campo("source_access", acesso.json)
        campo("verification_status", verificacao.json)
        campo("risk_if_wrong", riscoSeErrada.json)
        campo("original_text", textoOriginal)
        campo("normalized_text", textoNormalizado)
        campo("normalized_footnote", notaNormalizada)
    }

    /** O mesmo objeto como [ValorJson], para os contextos da auditoria final. */
    internal fun json(): ValorJson = ValorJson.objeto(
        "schema_version" to ValorJson.texto(versaoDoEsquema),
        "claim_id" to ValorJson.texto(claimId),
        "citation_type" to ValorJson.texto(tipo.json),
        "author_display" to ValorJson.texto(autorExibido),
        "author_key" to ValorJson.texto(chaveDoAutor),
        "year" to ValorJson.texto(ano),
        "locator" to ValorJson.texto(localizador),
        "source_id" to ValorJson.texto(fonteId),
        "source_access" to ValorJson.texto(acesso.json),
        "verification_status" to ValorJson.texto(verificacao.json),
        "risk_if_wrong" to ValorJson.texto(riscoSeErrada.json),
        "original_text" to ValorJson.texto(textoOriginal),
        "normalized_text" to ValorJson.texto(textoNormalizado),
        "normalized_footnote" to ValorJson.texto(notaNormalizada),
    )
}

/** `CitationAuthor`. */
public data class AutorDaFonte(val autorExibido: String, val chaveDoAutor: String)

/** `CitationSource`. */
public data class Fonte(
    val fonteId: String,
    val tipo: TipoDeFonte,
    val autores: List<AutorDaFonte>,
    val titulo: String,
    val subtitulo: String?,
    val edicao: String?,
    val local: String?,
    val editora: String?,
    val ano: String,
    val tituloDoConjunto: String?,
    val volume: String?,
    val numero: String?,
    val paginas: String?,
    val url: String?,
    val doi: String?,
    val acessadoEm: String?,
    val sha256DaVerificacao: String?,
    val verificacao: StatusDeVerificacao,
    val proibida: Boolean,
    val motivoDaQuarentena: String?,
) {
    internal fun escrever(json: JsonRust) = json.objeto {
        campo("source_id", fonteId)
        campo("source_type", tipo.json)
        campo("authors") {
            lista {
                for (autor in autores) {
                    item {
                        objeto {
                            campo("author_display", autor.autorExibido)
                            campo("author_key", autor.chaveDoAutor)
                        }
                    }
                }
            }
        }
        campo("title", titulo)
        campo("subtitle", subtitulo)
        campo("edition", edicao)
        campo("place", local)
        campo("publisher", editora)
        campo("year", ano)
        campo("container_title", tituloDoConjunto)
        campo("volume", volume)
        campo("issue", numero)
        campo("pages", paginas)
        campo("url", url)
        campo("doi", doi)
        campo("accessed_at", acessadoEm)
        campo("verification_sha256", sha256DaVerificacao)
        campo("verification_status", verificacao.json)
        campo("prohibited", proibida)
        campo("quarantine_reason", motivoDaQuarentena)
    }
}

/** `CitationManifest` (`citation_manifest.v1`). */
public data class ManifestoDeCitacoes(
    val versaoDoEsquema: String,
    val hashDoProtocolo: String,
    val citacoes: List<Citacao>,
    val fontes: List<Fonte>,
) {
    internal fun escrever(json: JsonRust) = json.objeto {
        campo("schema_version", versaoDoEsquema)
        campo("protocol_hash", hashDoProtocolo)
        campo("citations") { lista { for (citacao in citacoes) item { citacao.escrever(this) } } }
        campo("sources") { lista { for (fonte in fontes) item { fonte.escrever(this) } } }
    }
}

/** `CitationAuditBlocker`. */
public data class BloqueioDeCitacao(
    val codigo: String,
    val mensagem: String,
    val severidade: String,
    val claimId: String?,
    val fonteId: String?,
    val trecho: String?,
    val exigeEvidencia: Boolean,
) {
    internal fun json(): ValorJson = ValorJson.objeto(
        "code" to ValorJson.texto(codigo),
        "message" to ValorJson.texto(mensagem),
        "severity" to ValorJson.texto(severidade),
        "claim_id" to ValorJson.texto(claimId),
        "source_id" to ValorJson.texto(fonteId),
        "excerpt" to ValorJson.texto(trecho),
        "needs_evidence" to ValorJson.logico(exigeEvidencia),
    )
}

/** `CitationAuditResult` (`maestro_peer.v1`). */
public data class ResultadoAbnt(
    val versaoDoEsquema: String,
    val auditId: String,
    val verificadoEm: String,
    val hashDoProtocolo: String?,
    val statusDoParMaestro: StatusDoParMaestro,
    val citacoes: List<Citacao>,
    val referenciasNormalizadas: List<String>,
    val referenciasMarkdown: List<String>,
    val referenciasHtml: List<String>,
    val bloqueios: List<BloqueioDeCitacao>,
    val tabelaMarkdown: String,
    val diffSemantico: String,
)
