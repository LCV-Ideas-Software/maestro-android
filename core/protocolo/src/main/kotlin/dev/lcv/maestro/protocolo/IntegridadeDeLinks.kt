package dev.lcv.maestro.protocolo

import java.time.Instant
import java.util.TreeMap

/**
 * O motor de integridade de links: o quarto e o quinto estágios da auditoria
 * do candidato final. Porte de `maestro-app/src-tauri/src/link_integrity.rs`
 * (linhas 165–970 em `68528f9`), por decisão do operador de 24/09/2026.
 *
 * O que o canônico diz de si, e vale aqui: *"Mechanical reachability is
 * deliberately separated from editorial claim support. A successful HTTP
 * response remains `verified_but_weak` and `pending` until an explicit review
 * is recorded against the exact source fingerprint, claim context, normalized
 * URL, and content hash."* O identificador de cada link inclui o SHA-256 do
 * **texto inteiro**: qualquer edição, em qualquer ponto, faz todos os links
 * voltarem a pendentes.
 *
 * Tudo o que depende de biblioteca ou de aparelho chega por interface, como a
 * `FonteDeChave` do `:core:provedores`: o parser de URL ([AnalisadorDeUrl]),
 * a coleta da evidência ([ColetorDeEvidencia]), a busca ([BuscadorDeEvidencia])
 * e a gravação dos registros ([RegistroDeLinks]). Este módulo continua sem
 * rede e sem Android.
 */
public object IntegridadeDeLinks {

    internal const val ESQUEMA = "link_evidence.v1"
    internal const val ARTEFATO_DE_ORIGEM = "operator/current-editor"
    public const val MAXIMO_DE_OCORRENCIAS: Int = 30
    private const val MAXIMO_DE_CONTEXTO = 360
    private const val MAXIMO_DA_NOTA = 1200
    private const val MAXIMO_DA_LISTAGEM = 100

    /** Revisores aceitos por `review_link_integrity`. */
    private val REVISORES = setOf("operator", "claude", "codex", "gemini", "agy", "deepseek", "grok", "perplexity")

    /** Falha de gravação ou de regra de negócio: o `Err(String)` do canônico. */
    public class Falha(motivo: String) : Exception(motivo)

    /** O que o parser de URL (WHATWG, o `reqwest::Url` do canônico) diz de uma URL. */
    public fun interface AnalisadorDeUrl {
        /** A URL analisada, ou `null` quando não parseia. */
        public fun analisar(url: String): UrlAnalisada?
    }

    /**
     * Uma URL analisada. [esquema] em caixa baixa, [serializada] é o
     * `to_string()` do canônico, e [ipDoHost] traz os bytes do host quando ele
     * é um IP literal — nunca por DNS.
     */
    public data class UrlAnalisada(
        val esquema: String,
        val host: String?,
        val usuario: String,
        val senha: String?,
        val caminho: String,
        val serializada: String,
        val ipDoHost: ByteArray? = null,
    )

    /** `fetch_web_evidence_inner(None, GET, force_revalidate = false)`. */
    public fun interface ColetorDeEvidencia {
        /** O registro coletado; lança [Falha] com a mensagem do canônico quando falha. */
        public fun coletar(url: String): RegistroDeEvidencia
    }

    /** `search_web_evidence_inner`. */
    public fun interface BuscadorDeEvidencia {
        public fun buscar(consulta: String, provedor: String, limite: Int): List<RegistroDeEvidencia>
    }

    /**
     * A gravação dos registros de link. O canônico grava um JSON por link e
     * um diário `events.ndjson`, sob uma trava de processo; aqui a
     * implementação é do `:core:sessao`.
     */
    public interface RegistroDeLinks {
        /** Executa [bloco] com exclusão mútua sobre os registros. */
        public fun <T> emTransacao(bloco: () -> T): T

        /** O registro gravado, ou `null` se não existe ou não é válido. */
        public fun carregar(linkId: String): LinhaDeLink?

        public fun salvar(linha: LinhaDeLink)

        /** `append_event`: uma linha no diário de auditoria. */
        public fun anotar(tipo: String, linha: LinhaDeLink)

        /** Todos os registros gravados, para a listagem. */
        public fun todos(): List<LinhaDeLink>
    }

    /** `ExtractedLink`; [inicio] é índice UTF-16, só usado para ordenar. */
    internal data class LinkExtraido(
        val inicio: Int,
        val urlOriginal: String,
        val textoDaAncora: String?,
        val textoAoRedor: String,
    )

    // ── extração (linhas 165–295) ────────────────────────────────────────────

    /** `surrounding_text`: janela de 180 bytes UTF-8 para cada lado, como no Rust. */
    private fun textoAoRedor(texto: String, inicio: Int, fim: Int): String {
        val bytes = texto.toByteArray(Charsets.UTF_8)
        val inicioEmBytes = TextoRust.bytesAte(texto, inicio)
        val fimEmBytes = TextoRust.bytesAte(texto, fim)
        var esquerda = maxOf(0, inicioEmBytes - MAXIMO_DE_CONTEXTO / 2)
        while (esquerda > 0 && continuacao(bytes[esquerda])) esquerda--
        var direita = minOf(fimEmBytes + MAXIMO_DE_CONTEXTO / 2, bytes.size)
        while (direita < bytes.size && continuacao(bytes[direita])) direita++
        val janela = String(bytes, esquerda, direita - esquerda, Charsets.UTF_8)
        return Saneamento.texto(EspacoUnicode.dividirPorEspacos(janela).joinToString(" "), 500)
    }

    /** Byte de continuação UTF-8 (`10xxxxxx`): não é fronteira de caractere. */
    private fun continuacao(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

    /** `strip_html`. Rust (linha 194): `(?is)<[^>]+>` */
    private fun semHtml(valor: String): String {
        val semTags = TAG.replace(valor, " ")
        val decodificado = semTags.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
        return Saneamento.texto(EspacoUnicode.dividirPorEspacos(decodificado).joinToString(" "), 240)
    }

    private val TAG = Regex("<[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** `clean_url_tail`. */
    private fun limparCauda(valor: String): String =
        EspacoUnicode.aparar(valor).trim('"', '\'', '<', '>').trimEnd('.', ',', ';', ':')

    private fun sobrepoe(inicio: Int, fim: Int, cobertos: List<IntRange>): Boolean =
        cobertos.any { inicio < it.last + 1 && fim > it.first }

    /**
     * Os três padrões de `extract_links`. Rust (linhas 231, 251 e 274):
     * `(?s)\[([^\]\n]{0,240})\]\(\s*((?:[a-zA-Z][a-zA-Z0-9+.-]*:)[^)\s]+)(?:\s+["'][^"']*["'])?\s*\)`
     * `(?is)<a\b[^>]*\bhref\s*=\s*["']((?:[a-z][a-z0-9+.-]*:)[^"']+)["'][^>]*>(.*?)</a>`
     * `(?i)(?:https?://|mailto:|ftps?://|tel:|javascript:|data:|file:|blob:)[^\s<>"')\]]+`
     */
    private val MARKDOWN = Regex(
        "\\[([^\\]\\n]{0,240})\\]\\(${TextoRust.ESPACO}*((?:[a-zA-Z][a-zA-Z0-9+.-]*:)" +
            "[^)${TextoRust.ESPACO_CLASSE}]+)(?:${TextoRust.ESPACO}+[\"'][^\"']*[\"'])?${TextoRust.ESPACO}*\\)",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val HTML = Regex(
        "<a${TextoRust.LIMITE}[^>]*${TextoRust.LIMITE}href${TextoRust.ESPACO}*=${TextoRust.ESPACO}*[\"']" +
            "((?:[a-z][a-z0-9+.-]*:)[^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val SOLTO = Regex(
        "(?:https?://|mailto:|ftps?://|tel:|javascript:|data:|file:|blob:)" +
            "[^${TextoRust.ESPACO_CLASSE}<>\"')\\]]+",
        RegexOption.IGNORE_CASE,
    )

    /** `extract_links`. */
    internal fun extrair(texto: String): List<LinkExtraido> {
        val links = mutableListOf<LinkExtraido>()
        val cobertos = mutableListOf<IntRange>()
        for (achado in MARKDOWN.findAll(texto)) {
            val url = achado.groups[2] ?: continue
            val inicio = achado.range.first
            val fim = achado.range.last + 1
            links += LinkExtraido(
                inicio = inicio,
                urlOriginal = limparCauda(url.value),
                textoDaAncora = achado.groups[1]
                    ?.let { Saneamento.texto(EspacoUnicode.aparar(it.value), 240) }
                    ?.takeIf { it.isNotEmpty() },
                textoAoRedor = textoAoRedor(texto, inicio, fim),
            )
            cobertos += achado.range
        }
        for (achado in HTML.findAll(texto)) {
            val inicio = achado.range.first
            val fim = achado.range.last + 1
            if (sobrepoe(inicio, fim, cobertos)) continue
            val url = achado.groups[1] ?: continue
            links += LinkExtraido(
                inicio = inicio,
                urlOriginal = limparCauda(url.value),
                textoDaAncora = achado.groups[2]?.let { semHtml(it.value) }?.takeIf { it.isNotEmpty() },
                textoAoRedor = textoAoRedor(texto, inicio, fim),
            )
            cobertos += achado.range
        }
        for (achado in SOLTO.findAll(texto)) {
            val inicio = achado.range.first
            val fim = achado.range.last + 1
            if (sobrepoe(inicio, fim, cobertos)) continue
            links += LinkExtraido(
                inicio = inicio,
                urlOriginal = limparCauda(achado.value),
                textoDaAncora = null,
                textoAoRedor = textoAoRedor(texto, inicio, fim),
            )
        }
        return links.sortedBy { it.inicio }
    }

    /** `count_link_occurrences`. */
    public fun contarOcorrencias(texto: String): Int = extrair(texto).size

    // ── normalização e identidade (linhas 297–386) ───────────────────────────

    /** O `Ok((normalizada, mudanças))` ou o `Err(motivo)` de `normalize_url`. */
    internal sealed interface Normalizacao {
        data class Normalizada(val url: String, val mudancas: List<String>) : Normalizacao
        data class Recusada(val motivo: String) : Normalizacao
    }

    /** `normalize_url`. */
    internal fun normalizar(valor: String, analisador: AnalisadorDeUrl): Normalizacao {
        val aparado = EspacoUnicode.aparar(valor)
        if (aparado.isEmpty() || aparado.codePoints().anyMatch { controle(it) || it == 0x202E }) {
            return Normalizacao.Recusada("URL vazia ou com caracteres de controle")
        }
        val analisada = analisador.analisar(aparado)
            ?: return Normalizacao.Recusada("URL malformada ou incompleta")
        if (analisada.esquema !in setOf("http", "https", "mailto")) {
            return Normalizacao.Recusada("somente http, https e mailto sao permitidos")
        }
        if (analisada.esquema == "http" || analisada.esquema == "https") {
            if (analisada.host == null) return Normalizacao.Recusada("URL http/https sem host")
            if (analisada.usuario.isNotEmpty() || analisada.senha != null) {
                return Normalizacao.Recusada("credenciais embutidas na URL sao proibidas")
            }
        }
        val mudancas = mutableListOf<String>()
        if (aparado != valor) mudancas += "whitespace_removed"
        if (analisada.serializada != aparado) mudancas += "url_parser_normalization"
        return Normalizacao.Normalizada(analisada.serializada, mudancas)
    }

    /** `char::is_control`: categoria Cc. */
    private fun controle(pontoDeCodigo: Int): Boolean =
        Character.getType(pontoDeCodigo) == Character.CONTROL.toInt()

    /** `link_id`. */
    internal fun idDoLink(
        impressaoDaOrigem: String,
        urlNormalizada: String,
        textoDaAncora: String?,
        textoAoRedor: String,
        ocorrencia: Int,
    ): String = TextoRust.sha256(
        "$ARTEFATO_DE_ORIGEM|$impressaoDaOrigem|$urlNormalizada|${textoDaAncora ?: ""}|$textoAoRedor|$ocorrencia",
    )

    /** `is_valid_link_id`: 64 dígitos hexadecimais minúsculos. */
    internal fun idValido(valor: String): Boolean =
        valor.length == 64 && valor.all { it in '0'..'9' || it in 'a'..'f' }

    /** `base_row`. */
    internal fun linhaBase(
        extraido: LinkExtraido,
        impressaoDaOrigem: String,
        urlNormalizada: String,
        mudancas: List<String>,
        ocorrencia: Int,
        agora: Instant,
    ): LinhaDeLink = LinhaDeLink(
        versaoDoEsquema = ESQUEMA,
        linkId = idDoLink(impressaoDaOrigem, urlNormalizada, extraido.textoDaAncora, extraido.textoAoRedor, ocorrencia),
        artefatoDeOrigem = ARTEFATO_DE_ORIGEM,
        impressaoDaOrigem = impressaoDaOrigem,
        textoDaAncora = extraido.textoDaAncora,
        textoAoRedor = extraido.textoAoRedor,
        urlOriginal = Saneamento.texto(extraido.urlOriginal, 1000),
        urlNormalizada = Saneamento.texto(urlNormalizada, 1000),
        mudancasDaNormalizacao = mudancas,
        urlFinal = null,
        cadeiaDeRedirecionamento = emptyList(),
        statusHttp = null,
        tipoDeConteudo = null,
        sha256 = null,
        verificadoEm = TextoRust.rfc3339(agora),
        sustentaAfirmacao = null,
        classificacao = ClassificacaoDoLink.VERIFICADO_MAS_FRACO,
        candidatosDeCorrecao = emptyList(),
        statusDaRevisao = StatusDaRevisao.PENDENTE,
        decisaoDeRevisao = null,
        revisadoPor = null,
        notaDaRevisao = null,
        revisadoEm = null,
        evidenciaWebId = null,
        url = Saneamento.texto(extraido.urlOriginal, 240),
        status = "revisao editorial pendente",
        invalidade = "acessibilidade mecanica ainda nao comprova suporte a afirmacao",
        tom = "warn",
    )

    // ── classificação (linhas 388–575) ───────────────────────────────────────

    /** `content_type_mismatch`: o caminho promete PDF e a resposta não, ou o contrário. */
    private fun tipoDivergente(url: String, tipoDeConteudo: String?, analisador: AnalisadorDeUrl): Boolean {
        val caminhoEhPdf = analisador.analisar(url)?.caminho
            ?.let { EspacoUnicode.caixaBaixaAscii(it).endsWith(".pdf") } ?: false
        val respostaEhPdf = tipoDeConteudo?.let { EspacoUnicode.caixaBaixaAscii(it).startsWith("application/pdf") }
            ?: false
        return caminhoEhPdf != respostaEhPdf && (caminhoEhPdf || respostaEhPdf)
    }

    /** `mechanical_failure_class`. */
    private fun classeDeFalhaMecanica(registro: RegistroDeEvidencia): ClassificacaoDoLink? {
        when (registro.estadoDeInteracao) {
            EstadoDeInteracao.EXIGE_CAPTCHA -> return ClassificacaoDoLink.EXIGE_CAPTCHA
            EstadoDeInteracao.EXIGE_LOGIN -> return ClassificacaoDoLink.EXIGE_AUTENTICACAO
            EstadoDeInteracao.PAYWALL -> return ClassificacaoDoLink.PAYWALL
            else -> Unit
        }
        val status = registro.status
        return when {
            status == 401 -> ClassificacaoDoLink.EXIGE_AUTENTICACAO
            status == 403 -> ClassificacaoDoLink.PROIBIDO
            status == 404 || status == 410 -> ClassificacaoDoLink.NAO_ENCONTRADO
            status != null && status !in 200..299 -> ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO
            registro.estado == EstadoDaEvidencia.BLOQUEADA -> ClassificacaoDoLink.EM_QUARENTENA
            registro.estado == EstadoDaEvidencia.FALHOU -> {
                val notas = EspacoUnicode.caixaBaixaAscii(registro.notas.joinToString(" "))
                when {
                    notas.contains("timed out") || notas.contains("timeout") -> ClassificacaoDoLink.TEMPO_ESGOTADO
                    notas.contains("dns") || notas.contains("resolve") -> ClassificacaoDoLink.ERRO_DE_DNS
                    notas.contains("tls") || notas.contains("certificate") -> ClassificacaoDoLink.ERRO_DE_TLS
                    else -> ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO
                }
            }
            else -> null
        }
    }

    /** `apply_web_evidence`. */
    internal fun aplicarEvidencia(
        linha: LinhaDeLink,
        evidencia: RegistroDeEvidencia,
        analisador: AnalisadorDeUrl,
    ): LinhaDeLink {
        val comEvidencia = linha.copy(
            evidenciaWebId = evidencia.id,
            urlFinal = evidencia.urlFinal,
            cadeiaDeRedirecionamento = evidencia.cadeiaDeRedirecionamento.map {
                Redirecionamento(Saneamento.texto(it.url, 1000), it.status)
            },
            statusHttp = evidencia.status,
            tipoDeConteudo = evidencia.tipoDeConteudo,
            sha256 = evidencia.sha256,
            verificadoEm = evidencia.coletadaEm ?: evidencia.atualizadaEm,
        )
        classeDeFalhaMecanica(evidencia)?.let { classe ->
            return comEvidencia.copy(
                classificacao = classe,
                statusDaRevisao = StatusDaRevisao.PENDENTE,
                status = evidencia.status?.let { "HTTP $it" } ?: "falha mecanica",
                invalidade = evidencia.notas.lastOrNull()?.let { Saneamento.texto(it, 180) }
                    ?: "o link nao passou pela verificacao mecanica",
                tom = if (evidencia.estado == EstadoDaEvidencia.BLOQUEADA) "blocked" else "error",
            )
        }
        if (tipoDivergente(comEvidencia.urlNormalizada, comEvidencia.tipoDeConteudo, analisador)) {
            return comEvidencia.copy(
                classificacao = ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE,
                status = comEvidencia.statusHttp?.let { "HTTP $it" } ?: "tipo divergente",
                invalidade = "o tipo de conteudo nao corresponde ao destino declarado",
                tom = "error",
            )
        }
        val redirecionado = comEvidencia.urlFinal?.let { it != comEvidencia.urlNormalizada } ?: false
        return comEvidencia.copy(
            classificacao = if (redirecionado) {
                ClassificacaoDoLink.REDIRECIONADO_VERIFICADO
            } else {
                ClassificacaoDoLink.VERIFICADO_MAS_FRACO
            },
            statusDaRevisao = StatusDaRevisao.PENDENTE,
            status = comEvidencia.statusHttp?.let { "HTTP $it" } ?: "acessivel",
            invalidade = if (redirecionado) {
                "redirecionamento verificado; aceite editorial explicito ainda necessario"
            } else {
                "acessivel, mas suporte a afirmacao ainda nao foi julgado"
            },
            tom = "warn",
        )
    }

    /** O efeito de uma decisão de revisão sobre a linha — comum à revisão e à preservação. */
    private fun aplicarDecisao(linha: LinhaDeLink, decisao: DecisaoDeRevisao): LinhaDeLink = when (decisao) {
        DecisaoDeRevisao.ACEITAR -> linha.copy(
            sustentaAfirmacao = true,
            statusDaRevisao = StatusDaRevisao.ACEITA,
            classificacao = if (linha.urlFinal?.let { it != linha.urlNormalizada } == true) {
                ClassificacaoDoLink.REDIRECIONADO_VERIFICADO
            } else {
                ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO
            },
            invalidade = "suporte aceito explicitamente para esta afirmacao, URL e hash",
            tom = "ok",
        )
        DecisaoDeRevisao.REJEITAR -> linha.copy(
            sustentaAfirmacao = false,
            statusDaRevisao = StatusDaRevisao.REJEITADA,
            classificacao = ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO,
            invalidade = "link rejeitado pela revisao editorial",
            tom = "error",
        )
        DecisaoDeRevisao.QUARENTENA -> linha.copy(
            sustentaAfirmacao = false,
            statusDaRevisao = StatusDaRevisao.REJEITADA,
            classificacao = ClassificacaoDoLink.EM_QUARENTENA,
            invalidade = "link mantido em quarentena editorial",
            tom = "blocked",
        )
    }

    /**
     * `apply_preserved_review`: a revisão anterior só vale se origem, contexto,
     * âncora, URL normalizada e hash do conteúdo forem exatamente os mesmos.
     */
    internal fun preservarRevisao(linha: LinhaDeLink, anterior: LinhaDeLink): LinhaDeLink {
        val decisao = anterior.decisaoDeRevisao
        if (anterior.impressaoDaOrigem != linha.impressaoDaOrigem ||
            anterior.textoAoRedor != linha.textoAoRedor ||
            anterior.textoDaAncora != linha.textoDaAncora ||
            anterior.urlNormalizada != linha.urlNormalizada ||
            anterior.sha256 != linha.sha256 ||
            decisao == null
        ) {
            return linha
        }
        return aplicarDecisao(
            linha.copy(
                decisaoDeRevisao = decisao,
                revisadoPor = anterior.revisadoPor,
                notaDaRevisao = anterior.notaDaRevisao,
                revisadoEm = anterior.revisadoEm,
                candidatosDeCorrecao = anterior.candidatosDeCorrecao,
            ),
            decisao,
        )
    }

    /** `malformed_row`. */
    private fun linhaMalformada(
        extraido: LinkExtraido,
        impressaoDaOrigem: String,
        ocorrencia: Int,
        erro: String,
        agora: Instant,
    ): LinhaDeLink = linhaBase(extraido, impressaoDaOrigem, extraido.urlOriginal, emptyList(), ocorrencia, agora)
        .copy(
            classificacao = ClassificacaoDoLink.MALFORMADO,
            status = "URL invalida",
            invalidade = Saneamento.texto(erro, 180),
            tom = "blocked",
        )

    // ── a auditoria (linhas 577–688) ─────────────────────────────────────────

    /** `save_record_unlocked`: só se grava registro com esquema e identificador válidos. */
    private fun gravar(registro: RegistroDeLinks, linha: LinhaDeLink) {
        if (linha.versaoDoEsquema != ESQUEMA || !idValido(linha.linkId)) {
            throw Falha("refusing to persist an invalid link-integrity record")
        }
        registro.salvar(linha)
    }

    /** `save_audit_record`: preserva a revisão anterior, se ainda couber, e grava. */
    private fun salvarDaAuditoria(registro: RegistroDeLinks, linha: LinhaDeLink): LinhaDeLink =
        registro.emTransacao {
            val final = registro.carregar(linha.linkId)?.let { preservarRevisao(linha, it) } ?: linha
            gravar(registro, final)
            final
        }

    /** `run_link_integrity_audit`: o resultado, ou lança [Falha]. */
    public fun auditar(
        texto: String,
        analisador: AnalisadorDeUrl,
        coletor: ColetorDeEvidencia,
        registro: RegistroDeLinks,
        relogio: () -> Instant,
    ): ResultadoDosLinks {
        val impressao = TextoRust.sha256(texto)
        val verificadoEm = TextoRust.rfc3339(relogio())
        val ocorrencias = TreeMap<String, Int>(OrdemRust)
        val linhas = mutableListOf<LinhaDeLink>()
        val extraidos = extrair(texto)
        if (extraidos.size > MAXIMO_DE_OCORRENCIAS) {
            throw Falha(
                "link-integrity capacity exceeded: found ${extraidos.size} link occurrences; " +
                    "maximum is $MAXIMO_DE_OCORRENCIAS",
            )
        }
        for (extraido in extraidos) {
            val normalizacao = normalizar(extraido.urlOriginal, analisador)
            val chave = (normalizacao as? Normalizacao.Normalizada)?.url ?: extraido.urlOriginal
            val ocorrencia = (ocorrencias[chave] ?: 0) + 1
            ocorrencias[chave] = ocorrencia
            var linha = when (normalizacao) {
                is Normalizacao.Normalizada ->
                    linhaBase(extraido, impressao, normalizacao.url, normalizacao.mudancas, ocorrencia, relogio())
                is Normalizacao.Recusada -> {
                    val malformada = salvarDaAuditoria(
                        registro,
                        linhaMalformada(extraido, impressao, ocorrencia, normalizacao.motivo, relogio()),
                    )
                    registro.anotar("audit", malformada)
                    linhas += malformada
                    continue
                }
            }
            linha = if (linha.urlNormalizada.startsWith("mailto:")) {
                linha.copy(
                    status = "mailto sintaticamente valido",
                    invalidade = "destino mailto requer julgamento editorial explicito",
                    classificacao = ClassificacaoDoLink.VERIFICADO_MAS_FRACO,
                    statusDaRevisao = StatusDaRevisao.PENDENTE,
                    tom = "warn",
                )
            } else {
                try {
                    aplicarEvidencia(linha, coletor.coletar(linha.urlNormalizada), analisador)
                } catch (erro: Falha) {
                    val mensagem = erro.message.orEmpty()
                    val minuscula = EspacoUnicode.caixaBaixaAscii(mensagem)
                    linha.copy(
                        classificacao = when {
                            minuscula.contains("timeout") -> ClassificacaoDoLink.TEMPO_ESGOTADO
                            minuscula.contains("dns") || minuscula.contains("resolve") -> ClassificacaoDoLink.ERRO_DE_DNS
                            minuscula.contains("tls") || minuscula.contains("certificate") ->
                                ClassificacaoDoLink.ERRO_DE_TLS
                            else -> ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO
                        },
                        status = "falha mecanica",
                        invalidade = Saneamento.texto(mensagem, 180),
                        tom = "error",
                    )
                }
            }
            val gravada = salvarDaAuditoria(registro, linha)
            registro.anotar("audit", gravada)
            linhas += gravada
        }
        return ResultadoDosLinks(
            versaoDoEsquema = "link_integrity_audit.v1",
            auditId = TextoRust.sha256("$impressao|$verificadoEm"),
            artefatoDeOrigem = ARTEFATO_DE_ORIGEM,
            verificadoEm = verificadoEm,
            urlsEncontradas = linhas.size,
            verificadas = linhas.count { it.urlNormalizada.startsWith("http") },
            ok = linhas.count { it.tom == "ok" },
            falhas = linhas.count { it.tom == "error" || it.tom == "blocked" },
            revisaoPendente = linhas.count { it.statusDaRevisao == StatusDaRevisao.PENDENTE },
            bloqueadas = linhas.count { it.tom == "blocked" },
            linhas = linhas,
        )
    }

    /** `audit_requires_editorial_resolution`: falha ou revisão pendente seguram o texto. */
    public fun exigeResolucaoEditorial(resultado: ResultadoDosLinks): Boolean =
        resultado.falhas > 0 || resultado.revisaoPendente > 0

    // ── listagem, revisão e correção (linhas 690–966) ────────────────────────

    /** `LinkIntegrityListRequest`. */
    public data class PedidoDeListagem(
        val consulta: String? = null,
        val classificacoes: List<ClassificacaoDoLink> = emptyList(),
        val statusDaRevisao: List<StatusDaRevisao> = emptyList(),
        val artefatoDeOrigem: String? = null,
        val soPendentes: Boolean = false,
        val limite: Int? = null,
        val cursor: String? = null,
    )

    /** `LinkIntegrityListResult`. */
    public data class Listagem(val itens: List<LinhaDeLink>, val proximoCursor: String?, val total: Int)

    /** `list_link_integrity_records`. */
    public fun listar(pedido: PedidoDeListagem, registro: RegistroDeLinks): Listagem {
        val consulta = pedido.consulta
            ?.let { EspacoUnicode.aparar(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let(EspacoUnicode::caixaBaixaAscii)
        val itens = registro.todos().filter { linha ->
            (pedido.classificacoes.isEmpty() || linha.classificacao in pedido.classificacoes) &&
                (pedido.statusDaRevisao.isEmpty() || linha.statusDaRevisao in pedido.statusDaRevisao) &&
                (!pedido.soPendentes || linha.statusDaRevisao == StatusDaRevisao.PENDENTE) &&
                (pedido.artefatoDeOrigem == null || linha.artefatoDeOrigem == pedido.artefatoDeOrigem) &&
                (consulta == null || EspacoUnicode.caixaBaixaAscii(
                    "${linha.urlOriginal} ${linha.urlNormalizada} ${linha.textoDaAncora ?: ""} " +
                        "${linha.textoAoRedor} ${linha.notaDaRevisao ?: ""}",
                ).contains(consulta))
        }.sortedWith { esquerda, direita ->
            OrdemRust.compare(direita.verificadoEm, esquerda.verificadoEm).takeIf { it != 0 }
                ?: OrdemRust.compare(esquerda.linkId, direita.linkId)
        }
        val total = itens.size
        // `parse::<usize>` do Rust: aceita `+`, recusa negativo e não número.
        val inicio = (pedido.cursor ?: "0").toULongOrNull()
            ?.let { if (it > total.toULong()) total else it.toInt() }
            ?: throw Falha("invalid link-integrity cursor")
        val limite = (pedido.limite ?: 30).coerceIn(1, MAXIMO_DA_LISTAGEM)
        val fim = minOf(inicio + limite, total)
        return Listagem(itens.subList(inicio, fim), if (fim < total) fim.toString() else null, total)
    }

    /** `LinkIntegrityReviewRequest`. */
    public data class PedidoDeRevisao(
        val linkId: String,
        val decisao: DecisaoDeRevisao,
        val nota: String,
        val revisor: String,
        val urlNormalizadaEsperada: String,
        val sha256Esperado: String?,
    )

    /** `review_link_integrity`: a revisão explícita que tira um link de pendente. */
    public fun revisar(pedido: PedidoDeRevisao, registro: RegistroDeLinks, agora: Instant): LinhaDeLink {
        val revisor = Saneamento.curto(EspacoUnicode.aparar(pedido.revisor), 64)
        if (revisor !in REVISORES) throw Falha("reviewer identity is not allowlisted")
        val nota = Saneamento.texto(EspacoUnicode.aparar(pedido.nota), MAXIMO_DA_NOTA)
        if (EspacoUnicode.contarPontosDeCodigo(nota) < 10) {
            throw Falha("review note must contain at least 10 characters")
        }
        val revisada = atualizar(registro, pedido.linkId) { linha ->
            if (linha.urlNormalizada != pedido.urlNormalizadaEsperada || linha.sha256 != pedido.sha256Esperado) {
                throw Falha("link URL or content hash changed since it was read; reload before reviewing")
            }
            if (pedido.decisao == DecisaoDeRevisao.ACEITAR) {
                val alcancavel = linha.statusHttp?.let { it in 200..299 } ?: false
                if (!alcancavel && !linha.urlNormalizada.startsWith("mailto:")) {
                    throw Falha("cannot accept a link that did not pass mechanical validation")
                }
                if (linha.classificacao == ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE) {
                    throw Falha("content-type mismatch must be corrected before acceptance")
                }
            }
            aplicarDecisao(
                linha.copy(
                    decisaoDeRevisao = pedido.decisao,
                    revisadoPor = revisor,
                    notaDaRevisao = nota,
                    revisadoEm = TextoRust.rfc3339(agora),
                ),
                pedido.decisao,
            )
        }
        registro.anotar("review", revisada)
        return revisada
    }

    /** `update_record`: carrega, altera e grava sob a trava. */
    private fun atualizar(registro: RegistroDeLinks, linkId: String, alterar: (LinhaDeLink) -> LinhaDeLink): LinhaDeLink =
        registro.emTransacao {
            if (!idValido(linkId)) throw Falha("invalid link-integrity id")
            val atual = registro.carregar(linkId) ?: throw Falha("failed to read link-integrity record")
            val alterada = alterar(atual)
            gravar(registro, alterada)
            alterada
        }

    /** `LinkCorrectionProposalRequest`. */
    public data class PedidoDeCorrecao(
        val linkId: String,
        val provedor: String,
        val consulta: String? = null,
        val limite: Int? = null,
    )

    /** `default_correction_query`. */
    private fun consultaPadrao(linha: LinhaDeLink): String {
        val semente = linha.textoDaAncora?.takeIf { EspacoUnicode.contarPontosDeCodigo(it) >= 4 } ?: linha.textoAoRedor
        return Saneamento.texto(semente, 300)
    }

    /** `propose_link_corrections`. */
    public fun proporCorrecoes(
        pedido: PedidoDeCorrecao,
        registro: RegistroDeLinks,
        buscador: BuscadorDeEvidencia,
        agora: Instant,
    ): LinhaDeLink {
        if (!idValido(pedido.linkId)) throw Falha("invalid link-integrity id")
        val linha = registro.carregar(pedido.linkId) ?: throw Falha("failed to read link-integrity record")
        val provedor = Saneamento.curto(EspacoUnicode.aparar(pedido.provedor), 80)
        if (provedor.isEmpty()) throw Falha("correction provider is required")
        val consulta = pedido.consulta
            ?.let { EspacoUnicode.aparar(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { Saneamento.texto(it, 300) }
            ?: consultaPadrao(linha)
        if (consulta.isEmpty()) throw Falha("correction search query is empty")
        val itens = buscador.buscar(consulta, provedor, (pedido.limite ?: 8).coerceIn(1, 12))
        val propostoEm = TextoRust.rfc3339(agora)
        val candidatos = itens.map { item ->
            val alvo = item.urlFinal ?: item.url
            CandidatoDeCorrecao(
                candidatoId = TextoRust.sha256("${linha.linkId}|replace|$provedor|$alvo"),
                acao = AcaoDeCorrecao.SUBSTITUIR,
                url = alvo,
                titulo = item.titulo,
                provedor = provedor,
                consulta = consulta,
                evidenciaWebId = item.id,
                justificativa = "candidato retornado por API oficial/configurada; exige revisao e nova " +
                    "verificacao mecanica",
                propostoEm = propostoEm,
            )
        } + CandidatoDeCorrecao(
            candidatoId = TextoRust.sha256("${linha.linkId}|remove"),
            acao = AcaoDeCorrecao.REMOVER,
            url = null,
            titulo = null,
            provedor = "maestro",
            consulta = null,
            evidenciaWebId = null,
            justificativa = "remover o link ou a afirmacao quando nenhuma fonte confiavel sustentar o trecho",
            propostoEm = propostoEm,
        ) + CandidatoDeCorrecao(
            candidatoId = TextoRust.sha256("${linha.linkId}|reword"),
            acao = AcaoDeCorrecao.REESCREVER,
            url = null,
            titulo = null,
            provedor = "maestro",
            consulta = null,
            evidenciaWebId = null,
            justificativa = "reescrever ou estreitar a afirmacao sem apresentar evidencia nao verificada",
            propostoEm = propostoEm,
        )
        val vistos = HashSet<String>()
        val unicos = candidatos.filter { vistos.add(it.candidatoId) }
        val atualizada = atualizar(registro, pedido.linkId) { mais ->
            if (mais.impressaoDaOrigem != linha.impressaoDaOrigem ||
                mais.urlNormalizada != linha.urlNormalizada ||
                mais.sha256 != linha.sha256
            ) {
                throw Falha(
                    "link source, URL, or content hash changed during correction search; reload and retry",
                )
            }
            mais.copy(candidatosDeCorrecao = unicos)
        }
        registro.anotar("correction_candidates", atualizada)
        return atualizada
    }
}
