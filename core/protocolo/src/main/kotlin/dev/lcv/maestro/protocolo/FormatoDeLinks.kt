package dev.lcv.maestro.protocolo

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode

/**
 * O formato gravado das linhas de link e dos registros de evidência: o mesmo
 * JSON do canônico (`LinkAuditRow` em `link_integrity.rs`, `WebEvidenceRecord`
 * em `web_evidence.rs`), com as chaves `snake_case` de [LinhaDeLink.json] e a
 * leitura de volta. Fica aqui, e não em quem grava, porque os nomes JSON dos
 * enums são deste módulo; o `:core:sessao` grava e lê por estas funções.
 *
 * A leitura é estrita no que importa: chave ausente, tipo errado ou nome de
 * enum desconhecido devolvem `null`, que o canônico trata como "registro que
 * não é válido" (`load_record` falha e a listagem o ignora).
 */
public object FormatoDeLinks {

    /** A linha como o canônico a grava: um objeto JSON, com 2 espaços de recuo. */
    public fun serializarLinha(linha: LinhaDeLink): String = linha.json().bonito()

    /** Uma lista de linhas como objeto JSON de lista (`serde_json::to_string_pretty`). */
    public fun serializarLinhas(linhas: List<LinhaDeLink>): String = ValorJson.Lista(linhas.map { it.json() }).bonito()

    /** A linha gravada por [serializarLinha], ou `null` se não for válida. */
    public fun lerLinha(texto: String): LinhaDeLink? {
        val no = arvore(texto) ?: return null
        if (!no.isObject) return null
        return try {
            LinhaDeLink(
                versaoDoEsquema = obrigatorio(no, "schema_version"),
                linkId = obrigatorio(no, "link_id"),
                artefatoDeOrigem = obrigatorio(no, "source_artifact"),
                impressaoDaOrigem = obrigatorio(no, "source_fingerprint"),
                textoDaAncora = opcional(no, "anchor_text"),
                textoAoRedor = obrigatorio(no, "surrounding_text"),
                urlOriginal = obrigatorio(no, "original_url"),
                urlNormalizada = obrigatorio(no, "normalized_url"),
                mudancasDaNormalizacao = textos(no, "normalization_changes"),
                urlFinal = opcional(no, "final_url"),
                cadeiaDeRedirecionamento = lista(no, "redirect_chain").map { item ->
                    Redirecionamento(obrigatorio(item, "url"), inteiro(item, "status"))
                },
                statusHttp = inteiroOpcional(no, "http_status"),
                tipoDeConteudo = opcional(no, "content_type"),
                sha256 = opcional(no, "sha256"),
                verificadoEm = obrigatorio(no, "checked_at"),
                sustentaAfirmacao = logicoOpcional(no, "claim_supported"),
                classificacao = enum(obrigatorio(no, "classification"), ClassificacaoDoLink.entries) { it.json },
                classificacaoMecanica = enum(obrigatorio(no, "mechanical_classification"), ClassificacaoDoLink.entries) { it.json },
                candidatosDeCorrecao = lista(no, "correction_candidates").map { item ->
                    CandidatoDeCorrecao(
                        candidatoId = obrigatorio(item, "candidate_id"),
                        acao = enum(obrigatorio(item, "action"), AcaoDeCorrecao.entries) { it.json },
                        url = opcional(item, "url"),
                        titulo = opcional(item, "title"),
                        provedor = obrigatorio(item, "provider"),
                        consulta = opcional(item, "query"),
                        evidenciaWebId = opcional(item, "web_evidence_id"),
                        justificativa = obrigatorio(item, "rationale"),
                        propostoEm = obrigatorio(item, "proposed_at"),
                    )
                },
                statusDaRevisao = enum(obrigatorio(no, "cross_review_status"), StatusDaRevisao.entries) { it.json },
                decisaoDeRevisao = opcional(no, "review_decision")?.let { enum(it, DecisaoDeRevisao.entries) { d -> d.json } },
                revisadoPor = opcional(no, "reviewed_by"),
                notaDaRevisao = opcional(no, "review_note"),
                revisadoEm = opcional(no, "reviewed_at"),
                evidenciaWebId = opcional(no, "web_evidence_id"),
                url = obrigatorio(no, "url"),
                status = obrigatorio(no, "status"),
                invalidade = obrigatorio(no, "invalidity"),
                tom = obrigatorio(no, "tone"),
            )
        } catch (erro: FormatoInvalido) {
            null
        }
    }

    /** `WebEvidenceRecord` gravado como o canônico: um objeto JSON com 2 espaços de recuo. */
    public fun serializarEvidencia(registro: RegistroDeEvidencia): String = ValorJson.objeto(
        "id" to ValorJson.texto(registro.id),
        "schema_version" to ValorJson.texto(registro.versaoDoEsquema),
        "state" to ValorJson.texto(registro.estado.json),
        "url" to ValorJson.texto(registro.url),
        "method" to ValorJson.texto(registro.metodo.json),
        "access_mode" to ValorJson.texto(registro.modoDeAcesso.json),
        "status" to ValorJson.numero(registro.status),
        "final_url" to ValorJson.texto(registro.urlFinal),
        "title" to ValorJson.texto(registro.titulo),
        "content_type" to ValorJson.texto(registro.tipoDeConteudo),
        "sha256" to ValorJson.texto(registro.sha256),
        "retrieved_at" to ValorJson.texto(registro.coletadaEm),
        "expires_at" to ValorJson.texto(registro.expiraEm),
        "cache_ttl" to ValorJson.texto(registro.validadeDoCache),
        "cache_state" to ValorJson.texto(registro.estadoDoCache.json),
        "robots_state" to ValorJson.texto(registro.estadoDoRobots.json),
        "copyright_state" to ValorJson.texto(registro.estadoDosDireitos.json),
        "interaction_state" to ValorJson.texto(registro.estadoDeInteracao.json),
        "human_resolved" to ValorJson.logico(registro.resolvidaPorPessoa),
        "byte_count" to ValorJson.numero(registro.bytes),
        "duration_ms" to ValorJson.numero(registro.duracaoMs),
        "redirect_chain" to ValorJson.Lista(registro.cadeiaDeRedirecionamento.map { it.json() }),
        "curl_command" to ValorJson.texto(registro.comandoCurl),
        "provider" to ValorJson.texto(registro.provedor),
        "query" to ValorJson.texto(registro.consulta),
        "artifact_name" to ValorJson.texto(registro.nomeDoArtefato),
        "notes" to ValorJson.textos(registro.notas),
        "created_at" to ValorJson.texto(registro.criadaEm),
        "updated_at" to ValorJson.texto(registro.atualizadaEm),
    ).bonito()

    /** O registro gravado por [serializarEvidencia], ou `null` se não for válido. */
    public fun lerEvidencia(texto: String): RegistroDeEvidencia? {
        val no = arvore(texto) ?: return null
        if (!no.isObject) return null
        return try {
            RegistroDeEvidencia(
                id = obrigatorio(no, "id"),
                versaoDoEsquema = obrigatorio(no, "schema_version"),
                estado = enum(obrigatorio(no, "state"), EstadoDaEvidencia.entries) { it.json },
                url = obrigatorio(no, "url"),
                metodo = enum(obrigatorio(no, "method"), MetodoHttp.entries) { it.json },
                modoDeAcesso = enum(obrigatorio(no, "access_mode"), ModoDeAcesso.entries) { it.json },
                status = inteiroOpcional(no, "status"),
                urlFinal = opcional(no, "final_url"),
                titulo = opcional(no, "title"),
                tipoDeConteudo = opcional(no, "content_type"),
                sha256 = opcional(no, "sha256"),
                coletadaEm = opcional(no, "retrieved_at"),
                expiraEm = opcional(no, "expires_at"),
                validadeDoCache = obrigatorio(no, "cache_ttl"),
                estadoDoCache = enum(obrigatorio(no, "cache_state"), EstadoDoCache.entries) { it.json },
                estadoDoRobots = enum(obrigatorio(no, "robots_state"), EstadoDoRobots.entries) { it.json },
                estadoDosDireitos = enum(obrigatorio(no, "copyright_state"), EstadoDosDireitos.entries) { it.json },
                estadoDeInteracao = enum(obrigatorio(no, "interaction_state"), EstadoDeInteracao.entries) { it.json },
                resolvidaPorPessoa = logico(no, "human_resolved"),
                bytes = longoOpcional(no, "byte_count"),
                duracaoMs = longoOpcional(no, "duration_ms"),
                cadeiaDeRedirecionamento = lista(no, "redirect_chain").map { item ->
                    Redirecionamento(obrigatorio(item, "url"), inteiro(item, "status"))
                },
                comandoCurl = opcional(no, "curl_command"),
                provedor = opcional(no, "provider"),
                consulta = opcional(no, "query"),
                nomeDoArtefato = opcional(no, "artifact_name"),
                notas = textos(no, "notes"),
                criadaEm = obrigatorio(no, "created_at"),
                atualizadaEm = obrigatorio(no, "updated_at"),
            )
        } catch (erro: FormatoInvalido) {
            null
        }
    }

    private class FormatoInvalido : RuntimeException()

    private fun arvore(texto: String): JsonNode? = try {
        LeituraDoRelatorio.LEITOR.readTree(texto)
    } catch (erro: JacksonException) {
        null
    }

    private fun obrigatorio(no: JsonNode, nome: String): String {
        val valor = no.get(nome) ?: throw FormatoInvalido()
        if (!valor.isTextual) throw FormatoInvalido()
        return valor.textValue()
    }

    private fun opcional(no: JsonNode, nome: String): String? {
        val valor = no.get(nome) ?: return null
        if (valor.isNull) return null
        if (!valor.isTextual) throw FormatoInvalido()
        return valor.textValue()
    }

    private fun inteiro(no: JsonNode, nome: String): Int = inteiroOpcional(no, nome) ?: throw FormatoInvalido()

    private fun inteiroOpcional(no: JsonNode, nome: String): Int? {
        val valor = no.get(nome) ?: return null
        if (valor.isNull) return null
        if (!valor.canConvertToInt()) throw FormatoInvalido()
        return valor.intValue()
    }

    private fun longoOpcional(no: JsonNode, nome: String): Long? {
        val valor = no.get(nome) ?: return null
        if (valor.isNull) return null
        if (!valor.canConvertToLong()) throw FormatoInvalido()
        return valor.longValue()
    }

    private fun logico(no: JsonNode, nome: String): Boolean = logicoOpcional(no, nome) ?: throw FormatoInvalido()

    private fun logicoOpcional(no: JsonNode, nome: String): Boolean? {
        val valor = no.get(nome) ?: return null
        if (valor.isNull) return null
        if (!valor.isBoolean) throw FormatoInvalido()
        return valor.booleanValue()
    }

    private fun lista(no: JsonNode, nome: String): List<JsonNode> {
        val valor = no.get(nome) ?: throw FormatoInvalido()
        if (!valor.isArray) throw FormatoInvalido()
        return valor.toList()
    }

    private fun textos(no: JsonNode, nome: String): List<String> = lista(no, nome).map {
        if (!it.isTextual) throw FormatoInvalido()
        it.textValue()
    }

    private fun <T : Enum<T>> enum(nome: String, valores: List<T>, json: (T) -> String): T =
        valores.firstOrNull { json(it) == nome } ?: throw FormatoInvalido()
}
