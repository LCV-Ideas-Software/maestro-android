package dev.lcv.maestro.protocolo

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Os manifestos de citações que vêm anexados à sessão: porte de
 * `citation_manifests_from_attachments` (`abnt_citation.rs`, linhas 1387–1442
 * em `68528f9`) e da leitura tipada que o `serde` faz ali.
 *
 * No desktop o manifesto é anexo da sessão; no Android também será, e quem
 * entrega os anexos é o `:core:sessao` (decisão do operador de 24/09/2026).
 * Aqui só se decide, sobre nome, tipo e bytes, qual anexo é manifesto e se ele
 * é válido.
 *
 * **A leitura segue o `serde`**: campo obrigatório ausente é erro; `null` em
 * campo que não é opcional é erro; lista ausente vira vazia, mas lista `null`
 * é erro; `Option` ausente ou `null` vira ausente; campo desconhecido é
 * ignorado; enum só aceita o nome exato em `snake_case`; tipo trocado (número
 * no lugar de texto) é erro.
 *
 * **Uma divergência deliberada, mais estrita.** O canônico lê o anexo primeiro
 * como `serde_json::Value`, e nesse passo **chave repetida passa: vale a
 * última**. Num portão de integridade isso é ambiguidade — dois leitores do
 * mesmo arquivo podem ver manifestos diferentes. Aqui o manifesto com chave
 * repetida é **recusado**, com a mesma mensagem de manifesto inválido.
 */
public object ManifestosDosAnexos {

    /** Um anexo da sessão: o nome original, o tipo de mídia e como ler os bytes. */
    public class Anexo(
        public val nomeOriginal: String,
        public val tipoDeMidia: String,
        private val conteudo: () -> ByteArray,
    ) {
        internal fun ler(): ByteArray = conteudo()
    }

    /** `CitationManifestAttachments`. */
    public data class Manifestos(val atual: ManifestoDeCitacoes?, val anterior: ManifestoDeCitacoes?)

    public sealed interface Saida {
        public data class Lidos(val manifestos: Manifestos) : Saida

        /** O `Err` do canônico: a sessão não pode seguir com esses anexos. */
        public data class Recusados(val motivo: String) : Saida
    }

    /** Leitor tolerante a chave repetida, para decidir se o anexo é JSON e de que esquema. */
    private val LEITOR_DE_CLASSIFICACAO: JsonMapper = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** Leitor estrito, o mesmo do relatório do agente: chave repetida é recusada. */
    private val LEITOR_ESTRITO: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** `citation_manifests_from_attachments`. */
    public fun extrair(anexos: List<Anexo>): Saida {
        var atual: ManifestoDeCitacoes? = null
        var anterior: ManifestoDeCitacoes? = null
        for (anexo in anexos) {
            val nome = anexo.nomeOriginal.lowercase(Locale.ROOT)
            val tipo = anexo.tipoDeMidia.lowercase(Locale.ROOT)
            val nomeExplicito = nome.contains("citation-manifest") ||
                nome.contains("citation_manifest") ||
                nome.contains("manifesto-citacoes") ||
                nome.contains("manifesto_citacoes")
            if (!nome.endsWith(".json") && tipo != "application/json" && !nomeExplicito) continue
            val bytes = try {
                anexo.ler()
            } catch (erro: Exception) {
                return Saida.Recusados("failed to read citation manifest attachment: ${erro.message}")
            }
            val texto = utf8Estrito(bytes)
            val raiz = texto?.let(::lerJson)
            if (texto == null || raiz == null) {
                if (nomeExplicito) return Saida.Recusados("citation manifest attachment is not valid JSON")
                continue
            }
            val esquema = raiz.get("schema_version")
            if (esquema == null || !esquema.isTextual || esquema.textValue() != AuditoriaAbnt.ESQUEMA_DO_MANIFESTO) {
                if (nomeExplicito) {
                    return Saida.Recusados(
                        "citation manifest attachment must use ${AuditoriaAbnt.ESQUEMA_DO_MANIFESTO}",
                    )
                }
                continue
            }
            val manifesto = when (val lido = lerManifesto(texto)) {
                is Leitura.Ok -> lido.manifesto
                is Leitura.Erro -> return Saida.Recusados("citation manifest payload is invalid: ${lido.motivo}")
            }
            val ehAnterior = nome.contains("previous") || nome.contains("anterior")
            if (ehAnterior) {
                if (anterior != null) return Saida.Recusados("multiple previous citation manifests were supplied")
                anterior = manifesto
            } else {
                if (atual != null) return Saida.Recusados("multiple current citation manifests were supplied")
                atual = manifesto
            }
        }
        return Saida.Lidos(Manifestos(atual, anterior))
    }

    /**
     * Os bytes como UTF-8 estrito, ou `null`. O `serde_json::from_slice` só lê
     * UTF-8 válido; o Jackson, lendo bytes, detecta sozinho UTF-16 e UTF-32 e
     * leria um manifesto que o canônico recusa. Por isso o anexo é decodificado
     * aqui, recusando sequência malformada, e o Jackson lê o texto.
     */
    private fun utf8Estrito(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (erro: CharacterCodingException) {
        null
    }

    /**
     * `serde_json::from_slice::<Value>`: JSON estrito, sem texto depois do
     * valor. Duas coisas que o `serde_json` recusa e o Jackson aceitaria em
     * silêncio são recusadas aqui como no canônico: a marca BOM no começo e o
     * escape de surrogate sem par (`"\uD800"`), que viraria um texto que o
     * UTF-8 não representa.
     */
    private fun lerJson(texto: String): JsonNode? {
        if (texto.startsWith('\uFEFF')) return null
        return try {
            LEITOR_DE_CLASSIFICACAO.readTree(texto)?.takeUnless { it.isMissingNode || temSurrogateSemPar(it) }
        } catch (erro: JacksonException) {
            null
        }
    }

    /** Se algum texto ou chave do JSON tem surrogate UTF-16 sem par. */
    private fun temSurrogateSemPar(no: JsonNode): Boolean = when {
        no.isTextual -> surrogateSemPar(no.textValue())
        no.isObject -> no.properties().any { (chave, valor) -> surrogateSemPar(chave) || temSurrogateSemPar(valor) }
        no.isArray -> no.any(::temSurrogateSemPar)
        else -> false
    }

    private fun surrogateSemPar(valor: String): Boolean {
        var indice = 0
        while (indice < valor.length) {
            val atual = valor[indice]
            if (Character.isLowSurrogate(atual)) return true
            if (Character.isHighSurrogate(atual)) {
                if (indice + 1 >= valor.length || !Character.isLowSurrogate(valor[indice + 1])) return true
                indice++
            }
            indice++
        }
        return false
    }

    internal sealed interface Leitura {
        data class Ok(val manifesto: ManifestoDeCitacoes) : Leitura
        data class Erro(val motivo: String) : Leitura
    }

    /** Leitura estrita do manifesto, com as regras do `serde` descritas no topo. */
    internal fun lerManifesto(texto: String): Leitura {
        val raiz = try {
            LEITOR_ESTRITO.readTree(texto)
        } catch (erro: JacksonException) {
            return Leitura.Erro(LeituraDoRelatorio.primeiraLinha(erro))
        }
        return try {
            Leitura.Ok(manifesto(raiz))
        } catch (erro: CampoInvalido) {
            Leitura.Erro(erro.message.orEmpty())
        }
    }

    private class CampoInvalido(motivo: String) : Exception(motivo)

    private fun objeto(no: JsonNode?, esperado: String): JsonNode {
        if (no == null || !no.isObject) throw CampoInvalido("invalid type: ${tipo(no)}, expected $esperado")
        return no
    }

    private fun tipo(no: JsonNode?): String = when {
        no == null || no.isNull -> "null"
        no.isTextual -> "string"
        no.isBoolean -> "boolean"
        no.isNumber -> "number"
        no.isArray -> "sequence"
        no.isObject -> "map"
        else -> "value"
    }

    /** Campo de texto obrigatório. */
    private fun texto(pai: JsonNode, nome: String): String {
        val no = pai.get(nome) ?: throw CampoInvalido("missing field `$nome`")
        if (!no.isTextual) throw CampoInvalido("invalid type: ${tipo(no)}, expected a string for `$nome`")
        return no.textValue()
    }

    /** `Option<String>`: ausente ou `null` é ausente; outro tipo é erro. */
    private fun textoOpcional(pai: JsonNode, nome: String): String? {
        val no = pai.get(nome) ?: return null
        if (no.isNull) return null
        if (!no.isTextual) throw CampoInvalido("invalid type: ${tipo(no)}, expected a string for `$nome`")
        return no.textValue()
    }

    /** `bool` com `#[serde(default)]`: ausente é `false`; `null` é erro. */
    private fun logicoPadraoFalso(pai: JsonNode, nome: String): Boolean {
        val no = pai.get(nome) ?: return false
        if (!no.isBoolean) throw CampoInvalido("invalid type: ${tipo(no)}, expected a boolean for `$nome`")
        return no.booleanValue()
    }

    /** `Vec` com `#[serde(default)]`: ausente é vazia; `null` é erro. */
    private fun lista(pai: JsonNode, nome: String): List<JsonNode> {
        val no = pai.get(nome) ?: return emptyList()
        if (!no.isArray) throw CampoInvalido("invalid type: ${tipo(no)}, expected a sequence for `$nome`")
        return no.toList()
    }

    private fun <T : Enum<T>> enum(pai: JsonNode, nome: String, valores: Array<T>, json: (T) -> String): T {
        val bruto = texto(pai, nome)
        return valores.firstOrNull { json(it) == bruto }
            ?: throw CampoInvalido(
                "unknown variant `$bruto`, expected one of ${valores.joinToString(", ") { "`${json(it)}`" }}",
            )
    }

    private fun manifesto(raiz: JsonNode?): ManifestoDeCitacoes {
        val no = objeto(raiz, "struct CitationManifest")
        return ManifestoDeCitacoes(
            versaoDoEsquema = texto(no, "schema_version"),
            hashDoProtocolo = texto(no, "protocol_hash"),
            citacoes = lista(no, "citations").map(::citacao),
            fontes = lista(no, "sources").map(::fonte),
        )
    }

    private fun citacao(bruto: JsonNode): Citacao {
        val no = objeto(bruto, "struct CitationAuditCitation")
        return Citacao(
            versaoDoEsquema = texto(no, "schema_version"),
            claimId = texto(no, "claim_id"),
            tipo = enum(no, "citation_type", TipoDeCitacao.entries.toTypedArray()) { it.json },
            autorExibido = texto(no, "author_display"),
            chaveDoAutor = texto(no, "author_key"),
            ano = texto(no, "year"),
            localizador = textoOpcional(no, "locator"),
            fonteId = texto(no, "source_id"),
            acesso = enum(no, "source_access", AcessoAFonte.entries.toTypedArray()) { it.json },
            verificacao = enum(no, "verification_status", StatusDeVerificacao.entries.toTypedArray()) { it.json },
            riscoSeErrada = enum(no, "risk_if_wrong", RiscoSeErrada.entries.toTypedArray()) { it.json },
            textoOriginal = textoOpcional(no, "original_text"),
            textoNormalizado = textoOpcional(no, "normalized_text"),
            notaNormalizada = textoOpcional(no, "normalized_footnote"),
        )
    }

    private fun autor(bruto: JsonNode): AutorDaFonte {
        val no = objeto(bruto, "struct CitationAuthor")
        return AutorDaFonte(autorExibido = texto(no, "author_display"), chaveDoAutor = texto(no, "author_key"))
    }

    private fun fonte(bruto: JsonNode): Fonte {
        val no = objeto(bruto, "struct CitationSource")
        return Fonte(
            fonteId = texto(no, "source_id"),
            tipo = enum(no, "source_type", TipoDeFonte.entries.toTypedArray()) { it.json },
            autores = lista(no, "authors").map(::autor),
            titulo = texto(no, "title"),
            subtitulo = textoOpcional(no, "subtitle"),
            edicao = textoOpcional(no, "edition"),
            local = textoOpcional(no, "place"),
            editora = textoOpcional(no, "publisher"),
            ano = texto(no, "year"),
            tituloDoConjunto = textoOpcional(no, "container_title"),
            volume = textoOpcional(no, "volume"),
            numero = textoOpcional(no, "issue"),
            paginas = textoOpcional(no, "pages"),
            url = textoOpcional(no, "url"),
            doi = textoOpcional(no, "doi"),
            acessadoEm = textoOpcional(no, "accessed_at"),
            sha256DaVerificacao = textoOpcional(no, "verification_sha256"),
            verificacao = enum(no, "verification_status", StatusDeVerificacao.entries.toTypedArray()) { it.json },
            proibida = logicoPadraoFalso(no, "prohibited"),
            motivoDaQuarentena = textoOpcional(no, "quarantine_reason"),
        )
    }
}
