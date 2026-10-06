package dev.lcv.maestro.protocolo

import java.time.Instant
import java.util.TreeMap
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * O motor de integridade de links: o quarto e o quinto estágios da auditoria
 * do candidato final. Porte de `maestro-app/src-tauri/src/link_integrity.rs`
 * (linhas 165–970 em `68528f9`), por decisão do operador de 24/09/2026, e
 * reportado de `16a8cff` (MAESTRO-34, linhas 1–1260) na #77 (MAEANDR-26).
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

    /** As classificações mecânicas de link que passou, as únicas que o aceite admite. */
    private val ACEITAVEIS = setOf(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, ClassificacaoDoLink.REDIRECIONADO_VERIFICADO)

    private const val URL_ALTERADA_PELO_SANEAMENTO =
        "URL longa demais ou com padrao de segredo; a auditoria nao busca uma URL diferente da do texto"

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
     * é um IP literal — nunca por DNS. [caminho], [query] e [fragmento] vêm
     * como estão na URL, ainda codificados (`path()`, `query()` e
     * `fragment()` da crate `url`; a query sem o `?` e o fragmento sem o `#`,
     * ou `null` quando ausentes). O que o canônico de `16a8cff` lê a mais da
     * `Url` (#77) deriva desses: [semFragmento] e [segmentosDoCaminho].
     */
    public data class UrlAnalisada(
        val esquema: String,
        val host: String?,
        val usuario: String,
        val senha: String?,
        val caminho: String,
        val serializada: String,
        val query: String?,
        val fragmento: String?,
        val ipDoHost: ByteArray? = null,
    ) {
        /** A forma serializada sem o fragmento (`set_fragment(None)` e `to_string()`), para [mesmaUrlDeRede]. */
        val semFragmento: String
            get() = fragmento?.let { serializada.removeSuffix("#$it") } ?: serializada

        /**
         * `path_segments()` da crate `url`: o caminho depois da primeira barra, partido nas barras; `null` quando a
         * URL não tem caminho hierárquico (o `mailto:`). É o que a regra do parâmetro sensível lê ([ParametroSensivel]).
         */
        val segmentosDoCaminho: List<String>?
            get() = caminho.takeIf { it.startsWith("/") }?.removePrefix("/")?.split('/')
    }

    /**
     * A leitura pelo `java.net.URI` de um esquema que não é `http` nem `https` (`mailto:`, que a auditoria reconhece
     * sem coletar; `javascript:`, `data:`, que ela recusa): só o bastante para dizer qual é o esquema e o que a regra
     * do parâmetro sensível lê. Sem esquema, ou malformada, é `null`. A URL sem caminho hierárquico (opaca) tem a
     * query depois do `?` da parte específica do esquema, como no WHATWG (`cannot-be-a-base`). É o analisador dos
     * dublês de teste e o ramo não-http do analisador real, uma leitura só.
     */
    public fun analisarPorUri(url: String): UrlAnalisada? {
        val uri = try {
            java.net.URI(url)
        } catch (erro: java.net.URISyntaxException) {
            return null
        }
        val esquema = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return null
        val info = uri.rawUserInfo
        val especifica = uri.rawSchemeSpecificPart
        return UrlAnalisada(
            esquema = esquema,
            host = uri.host,
            usuario = info?.substringBefore(':') ?: "",
            senha = info?.takeIf { ':' in it }?.substringAfter(':'),
            caminho = uri.rawPath ?: especifica ?: "",
            // A crate `url` serializa o esquema em caixa baixa (`MAILTO:` vira `mailto:`) e o resto fica como está.
            serializada = esquema + url.substring(uri.scheme.length),
            query = if (uri.isOpaque) especifica?.takeIf { '?' in it }?.substringAfter('?') else uri.rawQuery,
            fragmento = uri.rawFragment,
        )
    }

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

    // ── extração (`extract_links`, linhas 251–390 em 16a8cff) ───────────────

    /**
     * `ExtractedLink`. [inicio], [urlInicio] e [urlFim] são índices UTF-16 da `String`, a unidade de
     * `SourceSpan.getInputIndex()` e de `MatchResult.range` (o canônico usa bytes porque o Rust indexa bytes). A
     * máscara do passo seguinte troca caractere por espaço e preserva o comprimento, como o canônico preserva o `len()`.
     */
    internal data class LinkExtraido(
        val inicio: Int,
        val urlInicio: Int,
        val urlFim: Int,
        val urlOriginal: String,
        val textoDaAncora: String?,
        val textoAoRedor: String,
    )

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

    /** `clean_url_tail`. */
    private fun limparCauda(valor: String): String =
        EspacoUnicode.aparar(valor).trim('"', '\'', '<', '>').trimEnd('.', ',', ';', ':')

    /**
     * `clean_bare_url_tail`: tira do fim da URL solta a pontuação (`.`, `,`, `;`, `:`, `]`, `}`) e o `)` que não tem
     * par; conta os parênteses uma vez só, em tempo linear.
     */
    private fun limparCaudaSolta(valor: String): String {
        val abre = valor.count { it == '(' }
        var fecha = valor.count { it == ')' }
        var fim = valor.length
        while (fim > 0) {
            val ultimo = valor[fim - 1]
            val fechaSemPar = ultimo == ')' && fecha > abre
            if (!fechaSemPar && ultimo !in ".,;:]}") break
            if (ultimo == ')') fecha--
            fim--
        }
        return valor.substring(0, fim)
    }

    private fun sobrepoe(inicio: Int, fim: Int, cobertos: List<IntRange>): Boolean =
        cobertos.any { inicio < it.last + 1 && fim > it.first }

    /**
     * O padrão da URL solta do canônico (linha 366): `(?i)(?:https?://|mailto:|ftps?://|tel:|javascript:|data:|file:|blob:)[^\s<>"']+`.
     * O `\s` do Rust é `White_Space` do Unicode ([TextoRust.ESPACO_CLASSE]).
     */
    private val SOLTO = Regex(
        "(?:https?://|mailto:|ftps?://|tel:|javascript:|data:|file:|blob:)[^${TextoRust.ESPACO_CLASSE}<>\"']+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * O padrão da máscara (`source_with_rejected_urls_masked`): o [SOLTO], mais as grafias de autoridade dos esquemas
     * especiais do WHATWG (`http`, `https`, `ftp`, `ws`, `wss`), que a crate `url` lê com qualquer sequência de barras,
     * nenhuma inclusive (`https:/u:p@h`, `https:\u:p@h`, `ftp:u:p@h`), como o `HttpUrl` lê as de http(s), e a
     * autoridade sem esquema (`//u:p@h`). Só mascara, e só o literal sensível; a extração continua pelo padrão do
     * canônico (achados do Codex, do Grok e do DeepSeek no cross-review da #77, 06/10/2026).
     */
    private val MASCARAVEL = Regex(
        "(?:(?:https?|ftp|wss?):[/\\\\]*|ftps://|[/\\\\]{2}|mailto:|tel:|javascript:|data:|file:|blob:)[^${TextoRust.ESPACO_CLASSE}<>\"']+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * O leitor do texto, com as posições de origem; os limites de aninhamento são os padrão da biblioteca, a proteção
     * dela contra entrada maliciosa. O texto final é Markdown, e esta é a especificação dele (decisão do operador de
     * 24/09/2026).
     */
    private val LEITOR: Parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

    /** Um link ou imagem aberto durante a visita: o início, o destino e a âncora que os filhos vão compondo. */
    private class LinkAberto(val inicio: Int, val fim: Int, val destino: String) {
        val ancora = StringBuilder()
    }

    /**
     * `extract_links`, sobre a commonmark-java em vez do pulldown-cmark, no que a biblioteca entrega como ela é:
     * definições de referência e código (em linha, cercado e indentado) ficam cobertos; links e imagens, aninhados
     * inclusive, entram no ponto de uso com o destino que o leitor dá (o da definição, na referência; `mailto:` mais o
     * endereço, no autolink de e-mail, pela especificação); a URL solta é o padrão do canônico sobre o texto cru, fora
     * do que está coberto. Não se porta o tratamento de HTML cru (`<a href>` e comentários): no texto final ele é
     * recusado antes deste portão (decisão do operador de 25/09/2026; `AuditoriaAbnt`). A diferença que a biblioteca
     * faz em entrada anormal (caractere de controle, entidade inválida, aninhamento acima de 100, Markdown malformado)
     * fica como ela entrega. Falha fechada: o leitor que falhar, ou o parágrafo que vier sem posição de origem (defeito
     * da commonmark-java 0.30.0 com o título de definição não fechado; teste-sentinela), reprovam o texto em vez de
     * deixar passar uma URL sem conferência.
     */
    internal fun extrair(texto: String): List<LinkExtraido> {
        val documento = try {
            LEITOR.parse(texto)
        } catch (erro: RuntimeException) {
            throw Falha("link-integrity extraction failed: ${erro.message}")
        }
        val links = mutableListOf<LinkExtraido>()
        val cobertos = mutableListOf<IntRange>()
        val abertos = ArrayDeque<LinkAberto>()
        documento.accept(
            object : AbstractVisitor() {
                override fun visit(paragrafo: Paragraph) {
                    if (paragrafo.sourceSpans.isEmpty()) {
                        throw Falha(
                            "link-integrity extraction lost the source position of a paragraph: " +
                                Saneamento.texto(textoDe(paragrafo), 120),
                        )
                    }
                    visitChildren(paragrafo)
                }
                override fun visit(definicao: LinkReferenceDefinition) {
                    cobertos += faixa(definicao)
                }
                override fun visit(bloco: FencedCodeBlock) {
                    cobertos += faixa(bloco)
                }
                override fun visit(bloco: IndentedCodeBlock) {
                    cobertos += faixa(bloco)
                }
                override fun visit(codigo: Code) {
                    cobertos += faixa(codigo)
                    abertos.forEach { it.ancora.append(codigo.literal) }
                }
                override fun visit(texto: Text) {
                    abertos.forEach { it.ancora.append(texto.literal) }
                }
                override fun visit(link: Link) = linkOuImagem(link, link.destination)
                override fun visit(imagem: Image) = linkOuImagem(imagem, imagem.destination)
                private fun linkOuImagem(no: Node, destino: String) {
                    val trecho = faixa(no)
                    val aberto = LinkAberto(trecho.first, trecho.last + 1, destino)
                    abertos.addLast(aberto)
                    visitChildren(no)
                    abertos.removeLast()
                    fechar(aberto)?.let { links += it }
                    cobertos += trecho
                }
                private fun fechar(aberto: LinkAberto): LinkExtraido? {
                    val url = aberto.destino
                    val fonte = texto.substring(aberto.inicio, aberto.fim)
                    val posicao = fonte.indexOf(url)
                    val (urlInicio, urlFim) =
                        if (posicao >= 0) aberto.inicio + posicao to aberto.inicio + posicao + url.length else aberto.inicio to aberto.fim
                    // O destino interno (relativo) não é auditado: sem `//`, sem `\`, sem controle e sem `:` antes do
                    // primeiro `/`, `?` ou `#`. O `#…` também não.
                    val interno = !url.startsWith("//") && '\\' !in url &&
                        url.codePoints().noneMatch { controle(it) } &&
                        ':' !in url.split('/', '?', '#').first()
                    if (url.startsWith("#") || interno) return null
                    val ancora = EspacoUnicode.aparar(aberto.ancora.toString())
                    return LinkExtraido(
                        inicio = aberto.inicio,
                        urlInicio = urlInicio,
                        urlFim = urlFim,
                        urlOriginal = url,
                        textoDaAncora = ancora.takeIf { it.isNotEmpty() }?.let { Saneamento.texto(it, 240) },
                        textoAoRedor = textoAoRedor(texto, aberto.inicio, aberto.fim),
                    )
                }
            },
        )
        for (achado in SOLTO.findAll(texto)) {
            val inicio = achado.range.first
            val fim = achado.range.last + 1
            if (sobrepoe(inicio, fim, cobertos)) continue
            val url = limparCaudaSolta(achado.value)
            links += LinkExtraido(
                inicio = inicio,
                urlInicio = inicio,
                urlFim = inicio + url.length,
                urlOriginal = limparCauda(url),
                textoDaAncora = null,
                textoAoRedor = textoAoRedor(texto, inicio, fim),
            )
        }
        return links.sortedBy { it.inicio }
    }

    /** A faixa de origem de um nó, `[início, fim)`, do primeiro ao último `SourceSpan`; sem posição, falha fechada. */
    private fun faixa(no: Node): IntRange {
        val spans = no.sourceSpans
        if (spans.isEmpty()) {
            throw Falha("link-integrity extraction lost the source position of a ${no.javaClass.simpleName}")
        }
        val ultimo = spans.last()
        return spans.first().inputIndex until ultimo.inputIndex + ultimo.length
    }

    /** O texto dos nós de texto de dentro, para a mensagem da falha. */
    private fun textoDe(no: Node): String {
        val construtor = StringBuilder()
        no.accept(
            object : AbstractVisitor() {
                override fun visit(texto: Text) {
                    construtor.append(texto.literal)
                }
            },
        )
        return construtor.toString()
    }

    /** `count_link_occurrences`; lança [Falha] quando [extrair] reprova o texto, e quem conta a trata como a auditoria. */
    public fun contarOcorrencias(texto: String): Int = extrair(texto).size

    // ── normalização e identidade (`normalize_url` a `base_row`, linhas 392–497 em 16a8cff) ──────────

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
        if ((analisada.esquema == "http" || analisada.esquema == "https") && analisada.host == null) {
            return Normalizacao.Recusada("URL http/https sem host")
        }
        // O canônico (`16a8cff.rs:405-411`) só recusa a credencial em http e https, e só a que o parser lê. Aqui a
        // autoridade de qualquer esquema leva a mesma recusa, como o parser a lê ou como o texto a mostra
        // ([USUARIO_NA_AUTORIDADE]): `mailto://u:p@h/` o `java.net.URI` lê com usuário e senha; em
        // `mailto://u:p@exa_mple.org/` ele lê uma autoridade "registry-based", sem usuário nem senha, e só o texto a
        // mostra. Sem isto a senha iria para a URL normalizada de um link aceitável (achados do Codex no cross-review da
        // #77, rodadas 5 e 6, 06/10/2026). É o que garante que todo link que [sensivel] redige é recusado: cada leitura
        // da redação tem a sua recusa aqui, ou no portão do saneamento em [auditar]. O `mailto:` opaco não tem
        // autoridade e não é alcançado.
        if (analisada.usuario.isNotEmpty() || analisada.senha != null || USUARIO_NA_AUTORIDADE.containsMatchIn(aparado)) {
            return Normalizacao.Recusada(CREDENCIAIS_EMBUTIDAS)
        }
        if (ParametroSensivel.tem(analisada)) return Normalizacao.Recusada(PARAMETRO_SENSIVEL)
        val mudancas = mutableListOf<String>()
        if (aparado != valor) mudancas += "whitespace_removed"
        if (analisada.serializada != aparado) mudancas += "url_parser_normalization"
        return Normalizacao.Normalizada(analisada.serializada, mudancas)
    }

    /** `char::is_control`: categoria Cc. */
    private fun controle(pontoDeCodigo: Int): Boolean =
        Character.getType(pontoDeCodigo) == Character.CONTROL.toInt()

    /**
     * `same_network_url`: as duas URLs parseiam e são a mesma sem o fragmento. A que não parseia conta como
     * diferente, como no canônico. O fragmento não sai da página: a coleta o tira (`UrlPublica`), e um link com
     * `#secao` não é um redirecionamento.
     */
    internal fun mesmaUrlDeRede(esquerda: String, direita: String, analisador: AnalisadorDeUrl): Boolean {
        val primeira = analisador.analisar(esquerda) ?: return false
        val segunda = analisador.analisar(direita) ?: return false
        return primeira.semFragmento == segunda.semFragmento
    }

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
        classificacaoMecanica = ClassificacaoDoLink.VERIFICADO_MAS_FRACO,
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

    // ── classificação (`content_type_mismatch` a `apply_preserved_review`, linhas 499–694 em 16a8cff) ──

    /** `content_type_mismatch`: o caminho promete PDF e a resposta não, ou o contrário. */
    private fun tipoDivergente(url: String, tipoDeConteudo: String?, analisador: AnalisadorDeUrl): Boolean {
        val caminhoEhPdf = analisador.analisar(url)?.caminho
            ?.let { EspacoUnicode.caixaBaixaAscii(it).endsWith(".pdf") } ?: false
        val respostaEhPdf = tipoDeConteudo?.let { EspacoUnicode.caixaBaixaAscii(it).startsWith("application/pdf") }
            ?: false
        return caminhoEhPdf != respostaEhPdf && (caminhoEhPdf || respostaEhPdf)
    }

    /**
     * `mechanical_failure_class`, na ordem do canônico de `16a8cff` (#77):
     *
     * 1. um 403 com pedido de login é "proibido", antes de tudo; só o 401 é
     *    "exige autenticação" (a coleta grava 401 e 403 como login exigido);
     * 2. captcha, login e paywall têm classe própria (o motor de evidências
     *    grava essas interações junto com o estado "ação do operador", então a
     *    classe só existe nesse estado, e é lida antes dele);
     * 3. toda outra interação pendente vai para quarentena, inclusive a
     *    "resolvida por pessoa" sem o booleano que a confirma
     *    ([interacaoConcluida]);
     * 4. a evidência bloqueada, a coleta que não terminou (na fila, em coleta,
     *    vencida, à espera do operador) e a pronta com cache que não está
     *    fresco vão para quarentena antes de o código HTTP ser lido: o código
     *    guardado é de uma coleta que não vale;
     * 5. depois, o código HTTP e o estado falhou; a pronta sem código também
     *    vai para quarentena.
     *
     * A quarentena da interação pendente e da coleta que não terminou nasceu
     * no porte, como divergência do Rust de `68528f9`, onde caíam em
     * "passou"; o canônico passou a fazer o mesmo em `16a8cff`.
     */
    internal fun classeDeFalhaMecanica(registro: RegistroDeEvidencia): ClassificacaoDoLink? {
        val interacao = registro.estadoDeInteracao
        if (registro.status == 403 && interacao == EstadoDeInteracao.EXIGE_LOGIN) return ClassificacaoDoLink.PROIBIDO
        when (interacao) {
            EstadoDeInteracao.EXIGE_CAPTCHA -> return ClassificacaoDoLink.EXIGE_CAPTCHA
            EstadoDeInteracao.EXIGE_LOGIN -> return ClassificacaoDoLink.EXIGE_AUTENTICACAO
            EstadoDeInteracao.PAYWALL -> return ClassificacaoDoLink.PAYWALL
            else -> Unit
        }
        if (coletaNaoVale(registro)) return ClassificacaoDoLink.EM_QUARENTENA
        val status = registro.status
        return when {
            status == 401 -> ClassificacaoDoLink.EXIGE_AUTENTICACAO
            status == 403 -> ClassificacaoDoLink.PROIBIDO
            status == 404 || status == 410 -> ClassificacaoDoLink.NAO_ENCONTRADO
            status != null && status !in 200..299 -> ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO
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

    /**
     * A coleta cuja prova não vale, o predicado único da quarentena e do tom `blocked` (especificação, seção 2.2):
     * alguém ainda precisa agir antes de a evidência valer. É a interação pendente (captcha, login, paywall,
     * consentimento, download, ou a "resolvida por pessoa" sem o booleano que a confirma), a coleta que não terminou
     * (na fila, em coleta, vencida, à espera do operador) ou bloqueada, e a pronta sem cache fresco ou sem código HTTP.
     */
    private fun coletaNaoVale(registro: RegistroDeEvidencia): Boolean =
        !interacaoConcluida(registro) ||
            (registro.estado != EstadoDaEvidencia.PRONTA && registro.estado != EstadoDaEvidencia.FALHOU) ||
            (
                registro.estado == EstadoDaEvidencia.PRONTA &&
                    (registro.estadoDoCache != EstadoDoCache.FRESCO || registro.status == null)
                )

    /**
     * Nada ficou pendente com uma pessoa entre a coleta e o conteúdo: nenhuma
     * interação, ou a interação resolvida por uma pessoa com o booleano que o
     * confirma (`HumanResolved` com `human_resolved`, como no canônico).
     */
    private fun interacaoConcluida(registro: RegistroDeEvidencia): Boolean =
        registro.estadoDeInteracao == EstadoDeInteracao.NENHUMA ||
            (registro.estadoDeInteracao == EstadoDeInteracao.RESOLVIDA_POR_PESSOA && registro.resolvidaPorPessoa)

    /**
     * O tom da linha cuja evidência falhou na verificação mecânica. `blocked`
     * é o que precisa de alguém agir antes de valer — o mesmo predicado da
     * quarentena, [coletaNaoVale]. `error` é a coleta que terminou sem
     * pendência e falhou: pronta com código ruim, ou falhou. O canônico só
     * dava `blocked` à bloqueada; o resumo da auditoria conta as linhas
     * `blocked` em `bloqueadas`.
     */
    private fun tomDaFalha(evidencia: RegistroDeEvidencia): String =
        if (coletaNaoVale(evidencia)) "blocked" else "error"

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
                classificacaoMecanica = classe,
                statusDaRevisao = StatusDaRevisao.PENDENTE,
                status = evidencia.status?.let { "HTTP $it" } ?: "falha mecanica",
                invalidade = evidencia.notas.lastOrNull()?.let { Saneamento.texto(it, 180) }
                    ?: "o link nao passou pela verificacao mecanica",
                tom = tomDaFalha(evidencia),
            )
        }
        if (tipoDivergente(comEvidencia.urlNormalizada, comEvidencia.tipoDeConteudo, analisador)) {
            return comEvidencia.copy(
                classificacao = ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE,
                classificacaoMecanica = ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE,
                status = comEvidencia.statusHttp?.let { "HTTP $it" } ?: "tipo divergente",
                invalidade = "o tipo de conteudo nao corresponde ao destino declarado",
                tom = "error",
            )
        }
        val redirecionado = comEvidencia.urlFinal
            ?.let { !mesmaUrlDeRede(it, comEvidencia.urlNormalizada, analisador) } ?: false
        val passou = if (redirecionado) {
            ClassificacaoDoLink.REDIRECIONADO_VERIFICADO
        } else {
            ClassificacaoDoLink.VERIFICADO_MAS_FRACO
        }
        return comEvidencia.copy(
            classificacao = passou,
            classificacaoMecanica = passou,
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
            // A classe mecânica já diz se houve redirecionamento (`aplicarEvidencia`, pela URL de rede sem o fragmento).
            classificacao = if (linha.classificacaoMecanica == ClassificacaoDoLink.REDIRECIONADO_VERIFICADO) {
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
     * As condições mecânicas do aceite (`mechanically_acceptable`, de
     * `16a8cff`): o motivo da recusa, ou `null` se o link pode ser aceito.
     * Duas diferenças do canônico, decididas pelo operador (#77): o `mailto:`
     * é aceito sem coleta (decisão 1), e a recusa diz o motivo em três
     * mensagens, em vez de uma só (decisão 6).
     */
    private fun motivoParaNaoAceitar(linha: LinhaDeLink): String? {
        val correio = linha.urlNormalizada.startsWith("mailto:")
        val alcancavel = linha.statusHttp?.let { it in 200..299 } ?: false
        if (!alcancavel && !correio) {
            return "cannot accept a link that did not pass mechanical validation"
        }
        // Sem hash do conteúdo, o aceite não fica preso a conteúdo nenhum: a
        // revisão conferia `null` com `null`, e mudar o destino nunca a
        // derrubava. Nasceu no porte, como divergência do Rust de `68528f9`;
        // o canônico passou a exigir o hash em `16a8cff`, em qualquer caixa
        // ([AuditoriaAbnt.sha256Valido], a mesma regra da ABNT). O `mailto:`, que não é coletado, segue sem hash.
        if (!correio && linha.sha256?.let(AuditoriaAbnt::sha256Valido) != true) {
            return "cannot accept a link without the content hash of its evidence"
        }
        if (linha.classificacaoMecanica == ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE) {
            return "content-type mismatch must be corrected before acceptance"
        }
        // Captcha, login, paywall ou evidência bloqueada servidos com 200 não
        // sustentam um aceite. Nasceu no porte, como divergência do Rust de
        // `68528f9`, que só conferia o código HTTP; o canônico passou a fazer
        // o mesmo em `16a8cff`.
        if (linha.classificacaoMecanica !in ACEITAVEIS) {
            return "cannot accept a link that did not pass mechanical validation"
        }
        return null
    }

    /**
     * `apply_preserved_review`: a revisão anterior só vale se origem, contexto,
     * âncora, URL normalizada, URL final, cadeia de redirecionamentos e hash do
     * conteúdo forem exatamente os mesmos. A mesma URL com o mesmo conteúdo,
     * servida por outro caminho de redirecionamento, mantém o id do link, e o
     * aceite dado a um caminho não vale para o outro (`16a8cff`, #77).
     *
     * Um aceite só é preservado se a verificação nova ainda cumprir as
     * condições do aceite ([motivoParaNaoAceitar]). Nasceu no porte, como
     * divergência: o Rust de `68528f9` o preservava só pelo hash, e um link que
     * agora responde com erro ou captcha voltaria aceito sem revisão; o
     * canônico passou a fazer o mesmo em `16a8cff`.
     */
    internal fun preservarRevisao(linha: LinhaDeLink, anterior: LinhaDeLink): LinhaDeLink {
        val decisao = anterior.decisaoDeRevisao
        if (anterior.impressaoDaOrigem != linha.impressaoDaOrigem ||
            anterior.textoAoRedor != linha.textoAoRedor ||
            anterior.textoDaAncora != linha.textoDaAncora ||
            anterior.urlNormalizada != linha.urlNormalizada ||
            anterior.urlFinal != linha.urlFinal ||
            anterior.cadeiaDeRedirecionamento != linha.cadeiaDeRedirecionamento ||
            anterior.sha256 != linha.sha256 ||
            decisao == null ||
            (decisao == DecisaoDeRevisao.ACEITAR && motivoParaNaoAceitar(linha) != null)
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
            classificacaoMecanica = ClassificacaoDoLink.MALFORMADO,
            status = "URL invalida",
            invalidade = Saneamento.texto(erro, 180),
            tom = "blocked",
        )

    // ── redação e máscara (`redacted_extracted_link`, `source_with_rejected_urls_masked`, `safe_context_link`) ──

    private const val CREDENCIAIS_EMBUTIDAS = "credenciais embutidas na URL sao proibidas"
    private const val PARAMETRO_SENSIVEL = "parametro de credencial na URL e proibido"
    private const val URL_BLOQUEADA = "<blocked URL>"
    private const val CONTEXTO_REDIGIDO = "<redacted context>"

    /**
     * Usuário ou senha na autoridade de uma URL que tem autoridade, parseável ou não: um esquema especial do WHATWG
     * (`http`, `https`, `ftp`, `ws`, `wss`) com qualquer sequência de `/` e `\` depois dos dois-pontos, nenhuma
     * inclusive (as grafias que a crate `url` lê como `ftp://u:p@h`, e o `HttpUrl` como `https://u:p@h`), ou duas ou
     * mais barras com ou sem esquema (`gopher://u:p@h`, a relativa de protocolo `//u:p@h`), e tudo até o `@`. O
     * esquema opaco não tem autoridade: `mailto:leitor@example.org` é endereço, não credencial (achados do Gemini,
     * do Codex e do DeepSeek no cross-review da #77, 06/10/2026).
     */
    private const val AUTORIDADE_COM_USUARIO = "(?:(?:https?|ftp|wss?):[/\\\\]*|(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?[/\\\\]{2,})[^/\\\\?#]*@"

    /** O cinto da autoridade no início da URL: o que o parser leria como autoridade dela. */
    private val USUARIO_NA_AUTORIDADE = Regex("^$AUTORIDADE_COM_USUARIO", RegexOption.IGNORE_CASE)

    /**
     * O mesmo cinto sem a âncora, para o texto que nenhum parser lê: a autoridade com credencial pode vir depois de uma
     * barra (`x https://example.com/r/https://u:p@h/`), e nada diz onde a URL começa. Na URL que o parser lê, a âncora
     * fica: a autoridade é a que o parser dá, como no canônico (achado do Grok no cross-review da #77, rodada 7,
     * 06/10/2026).
     */
    private val USUARIO_NO_TEXTO = Regex(AUTORIDADE_COM_USUARIO, RegexOption.IGNORE_CASE)

    /**
     * O link que sai redigido do registro, da tela e do contexto enviado ao agente (decisão 2 do operador,
     * 05/10/2026): o que carrega credencial. O canônico redige todo link recusado, `javascript:` e `ftp://`
     * inclusive; aqui os demais aparecem por inteiro, para serem achados e corrigidos. Por isso a regra não olha o
     * esquema nem o motivo da recusa, e sim o que qualquer leitor do porte lê como credencial: o padrão de segredo
     * conhecido ([Saneamento.ocultarSegredos]); o usuário na autoridade, em toda grafia, parseável ou não
     * ([USUARIO_NA_AUTORIDADE]) sobre o texto aparado, que é o que a normalização lê (o destino entre `<` e `>` guarda os
     * espaços à volta); e o usuário, a senha e o parâmetro de credencial ([ParametroSensivel]) que o parser lê da URL de
     * qualquer esquema, sem os caracteres de controle que só serviriam para o parser não a ler (dentro do nome da
     * credencial, `tok<U+0001>en`, o controle também esconde o sufixo da leitura textual). O que nenhum parser lê é
     * lido como texto inteiro: o cinto da autoridade corre sem âncora sobre o texto todo ([USUARIO_NO_TEXTO]), porque a
     * autoridade com credencial pode vir depois de uma barra, e o texto passa pela regra do parâmetro com o caminho todo
     * ([comoTexto]). O registro de bloqueio não é lugar onde a credencial sobreviva (achados do Codex, do DeepSeek e do
     * Grok no cross-review da #77, rodadas 5 a 7, 06/10/2026).
     *
     * Todo link que esta regra redige, [normalizar] recusa, ou o portão do saneamento em [auditar] recusa: o padrão de
     * segredo altera o texto saneado; o cinto da autoridade é a mesma recusa de credencial em [normalizar]; a leitura
     * do parser recusa usuário, senha e parâmetro lá; e o texto sem parser já é "URL malformada" lá. Por isso a linha
     * redigida nunca leva uma URL normalizada.
     */
    private fun sensivel(url: String, analisador: AnalisadorDeUrl): Boolean {
        if (Saneamento.ocultarSegredos(url) != url) return true
        val legivel = EspacoUnicode.aparar(url).filterNot { controle(it.code) || it == '\u202E' }
        if (USUARIO_NA_AUTORIDADE.containsMatchIn(legivel)) return true
        val analisada = analisador.analisar(legivel)
            ?: return USUARIO_NO_TEXTO.containsMatchIn(legivel) || ParametroSensivel.tem(comoTexto(legivel))
        return analisada.usuario.isNotEmpty() || analisada.senha != null || ParametroSensivel.tem(analisada)
    }

    /**
     * A URL que nenhum parser lê, como texto: o que vem antes do primeiro `?` ou `#` é o caminho inteiro, com a barra
     * inicial que [UrlAnalisada.segmentosDoCaminho] exige, para a regra do segmento valer em cada pedaço entre `/` e
     * `\` (`https://exa mple.com/api_key=x/y` tem a chave com valor no quarto pedaço); a query e o fragmento, como na
     * URL. Usuário e senha ficam com o cinto da autoridade, em [sensivel].
     */
    private fun comoTexto(url: String): UrlAnalisada {
        val semFragmento = url.substringBefore('#')
        return UrlAnalisada(
            esquema = "",
            host = null,
            usuario = "",
            senha = null,
            caminho = "/" + semFragmento.substringBefore('?'),
            serializada = url,
            query = if ('?' in semFragmento) semFragmento.substringAfter('?') else null,
            fragmento = if ('#' in url) url.substringAfter('#') else null,
        )
    }

    /**
     * `rejected_url_for_record`: a URL que pode ser gravada de um link redigido. Para `http` e `https`, só
     * `esquema://host[:porta]/`, sem usuário, senha, caminho, query e fragmento; o resto, e o que não parseia, é
     * `<blocked URL>`. O link redigido nunca é coletado. Pública porque o coletor (`:core:provedores`) grava com ela o
     * registro de bloqueio (`blocked_request_record`).
     */
    public fun urlParaRegistro(url: String, analisador: AnalisadorDeUrl): String {
        val analisada = analisador.analisar(url) ?: return URL_BLOQUEADA
        if (analisada.esquema != "http" && analisada.esquema != "https") return URL_BLOQUEADA
        val depoisDoEsquema = analisada.serializada.substringAfter("://")
        val autoridade = depoisDoEsquema.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return Saneamento.texto("${analisada.esquema}://$autoridade/", 2_048)
    }

    /** `redacted_extracted_link`: mesmas posições, URL redigida, sem âncora e com o contexto redigido. */
    private fun redigido(extraido: LinkExtraido, analisador: AnalisadorDeUrl): LinkExtraido = extraido.copy(
        urlOriginal = urlParaRegistro(extraido.urlOriginal, analisador),
        textoDaAncora = null,
        textoAoRedor = CONTEXTO_REDIGIDO,
    )

    /**
     * `source_with_rejected_urls_masked`: o texto com cada URL sensível trocada por espaços, caractere a caractere,
     * na posição que a extração deu, e também cada literal de URL sensível que o padrão da máscara ([MASCARAVEL])
     * ache no texto, dentro de código, âncora e definição inclusive. As quebras de linha ficam. É deste texto que
     * sai o contexto dos outros links.
     */
    internal fun textoMascarado(texto: String, extraidos: List<LinkExtraido>, analisador: AnalisadorDeUrl): String {
        val caracteres = texto.toCharArray()
        fun mascarar(inicio: Int, fim: Int) {
            for (i in inicio until fim) if (caracteres[i] != '\r' && caracteres[i] != '\n') caracteres[i] = ' '
        }
        for (link in extraidos) {
            if (sensivel(link.urlOriginal, analisador)) mascarar(link.urlInicio, link.urlFim)
        }
        for (achado in MASCARAVEL.findAll(texto)) {
            val literal = limparCaudaSolta(achado.value)
            if (sensivel(literal, analisador)) {
                mascarar(achado.range.first, achado.range.first + literal.length)
            }
        }
        return String(caracteres)
    }

    /**
     * `safe_context_link`: a âncora e o contexto do link vêm da extração do texto mascarado, achado pelo mesmo
     * início e pela mesma URL; se não for achado, fica sem âncora e com o contexto redigido.
     */
    private fun contextoSeguro(extraido: LinkExtraido, mascarados: List<LinkExtraido>): LinkExtraido =
        mascarados.firstOrNull { it.inicio == extraido.inicio && it.urlOriginal == extraido.urlOriginal }
            ?: extraido.copy(textoDaAncora = null, textoAoRedor = CONTEXTO_REDIGIDO)

    // ── a auditoria (`run_link_integrity_audit_for_source`, linhas 803–958 em 16a8cff) ───────────────

    /** `save_record_unlocked`: só se grava registro com esquema e identificador válidos. */
    private fun gravar(registro: RegistroDeLinks, linha: LinhaDeLink) {
        if (linha.versaoDoEsquema != ESQUEMA || !idValido(linha.linkId)) {
            throw Falha("refusing to persist an invalid link-integrity record")
        }
        registro.salvar(linha)
    }

    /**
     * `save_audit_record` e o `append_event` que o segue: preserva a revisão anterior, se ainda couber, e
     * grava a linha e a entrada do diário na mesma transação. Divergência do canônico, que grava os dois
     * arquivos em sequência: aqui a falha do diário desfaz a linha, e a linha nunca muda sem a entrada
     * que a explica (achado do Codex na #78).
     */
    private fun salvarDaAuditoria(registro: RegistroDeLinks, linha: LinhaDeLink): LinhaDeLink =
        registro.emTransacao {
            val final = registro.carregar(linha.linkId)?.let { preservarRevisao(linha, it) } ?: linha
            gravar(registro, final)
            registro.anotar("audit", final)
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
        val mascarados = extrair(textoMascarado(texto, extraidos, analisador))
        if (extraidos.size > MAXIMO_DE_OCORRENCIAS) {
            throw Falha(
                "link-integrity capacity exceeded: found ${extraidos.size} link occurrences; " +
                    "maximum is $MAXIMO_DE_OCORRENCIAS",
            )
        }
        for (extraido in extraidos) {
            val normalizacao = normalizar(extraido.urlOriginal, analisador).let { lida ->
                // `normalized_url_is_safe_to_collect`: a URL que o saneamento
                // alteraria (cortada em 1.000 pontos de código, ou com padrão de
                // segredo trocado por `<redacted>`) é recusada, para o que se
                // coleta, o que se revisa e o que está no texto serem a mesma
                // URL. Nasceu no porte, como divergência do Rust de `68528f9`,
                // que coletava a URL alterada; o canônico passou a fazer o
                // mesmo em `16a8cff`.
                if (lida is Normalizacao.Normalizada && Saneamento.texto(lida.url, 1000) != lida.url) {
                    Normalizacao.Recusada(URL_ALTERADA_PELO_SANEAMENTO)
                } else {
                    lida
                }
            }
            // O link sensível sai redigido; os outros levam a âncora e o contexto do texto mascarado. A ocorrência do
            // redigido é contada pela URL redigida: dois links recusados da mesma origem ficam com ids distintos.
            val redigir = sensivel(extraido.urlOriginal, analisador)
            val seguro = if (redigir) redigido(extraido, analisador) else contextoSeguro(extraido, mascarados)
            val chave = if (redigir) seguro.urlOriginal else (normalizacao as? Normalizacao.Normalizada)?.url ?: extraido.urlOriginal
            val ocorrencia = (ocorrencias[chave] ?: 0) + 1
            ocorrencias[chave] = ocorrencia
            var linha = when (normalizacao) {
                is Normalizacao.Normalizada ->
                    linhaBase(seguro, impressao, normalizacao.url, normalizacao.mudancas, ocorrencia, relogio())
                is Normalizacao.Recusada -> {
                    val malformada = salvarDaAuditoria(registro, linhaMalformada(seguro, impressao, ocorrencia, normalizacao.motivo, relogio()))
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
                    val classe = when {
                        minuscula.contains("timeout") -> ClassificacaoDoLink.TEMPO_ESGOTADO
                        minuscula.contains("dns") || minuscula.contains("resolve") -> ClassificacaoDoLink.ERRO_DE_DNS
                        minuscula.contains("tls") || minuscula.contains("certificate") -> ClassificacaoDoLink.ERRO_DE_TLS
                        else -> ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO
                    }
                    linha.copy(
                        classificacao = classe,
                        classificacaoMecanica = classe,
                        status = "falha mecanica",
                        invalidade = Saneamento.texto(mensagem, 180),
                        tom = "error",
                    )
                }
            }
            val gravada = salvarDaAuditoria(registro, linha)
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

    // ── listagem, revisão e correção (linhas 960–1260 em 16a8cff) ─────────────────────────────────

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

    /**
     * `LinkIntegrityReviewRequest`: a decisão e a versão da linha que a tela mostrou. A URL final e a cadeia
     * de redirecionamentos são obrigatórias, como no canônico: sem elas, uma decisão tomada sobre a linha lida
     * valeria para a mesma URL servida por outro caminho de redirecionamento (#77).
     */
    public data class PedidoDeRevisao(
        val linkId: String,
        val decisao: DecisaoDeRevisao,
        val nota: String,
        val revisor: String,
        val esperada: IdentidadeDaEvidencia,
    )

    /**
     * A versão da evidência que a tela leu (`reviewed_evidence_matches`): a URL normalizada, o hash do conteúdo, a
     * URL final e a cadeia de redirecionamentos, item a item e em ordem. A decisão só vale para esta versão.
     */
    public data class IdentidadeDaEvidencia(
        val urlNormalizada: String,
        val sha256: String?,
        val urlFinal: String?,
        val cadeia: List<Redirecionamento>,
    ) {
        public companion object {
            public fun de(linha: LinhaDeLink): IdentidadeDaEvidencia =
                IdentidadeDaEvidencia(linha.urlNormalizada, linha.sha256, linha.urlFinal, linha.cadeiaDeRedirecionamento)
        }
    }

    /** `reviewed_evidence_matches`: a linha ainda é a versão que a tela leu. */
    internal fun evidenciaRevisadaConfere(linha: LinhaDeLink, esperada: IdentidadeDaEvidencia): Boolean =
        IdentidadeDaEvidencia.de(linha) == esperada

    /** `review_link_integrity`: a revisão explícita que tira um link de pendente. */
    public fun revisar(pedido: PedidoDeRevisao, registro: RegistroDeLinks, agora: Instant): LinhaDeLink {
        val revisor = Saneamento.curto(EspacoUnicode.aparar(pedido.revisor), 64)
        if (revisor !in REVISORES) throw Falha("reviewer identity is not allowlisted")
        val nota = Saneamento.texto(EspacoUnicode.aparar(pedido.nota), MAXIMO_DA_NOTA)
        if (EspacoUnicode.contarPontosDeCodigo(nota) < 10) {
            throw Falha("review note must contain at least 10 characters")
        }
        val revisada = atualizar(registro, pedido.linkId, "review") { linha ->
            if (!evidenciaRevisadaConfere(linha, pedido.esperada)) {
                throw Falha(
                    "link URL, redirect identity, or content hash changed since it was read; reload before reviewing",
                )
            }
            if (pedido.decisao == DecisaoDeRevisao.ACEITAR) {
                motivoParaNaoAceitar(linha)?.let { throw Falha(it) }
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
        return revisada
    }

    /**
     * `update_record` e o `append_event` que o segue: carrega, altera e grava a linha e a entrada
     * [tipoDoDiario] do diário sob a mesma transação (ver [salvarDaAuditoria]).
     */
    private fun atualizar(registro: RegistroDeLinks, linkId: String, tipoDoDiario: String, alterar: (LinhaDeLink) -> LinhaDeLink): LinhaDeLink =
        registro.emTransacao {
            if (!idValido(linkId)) throw Falha("invalid link-integrity id")
            val atual = registro.carregar(linkId) ?: throw Falha("failed to read link-integrity record")
            val alterada = alterar(atual)
            gravar(registro, alterada)
            registro.anotar(tipoDoDiario, alterada)
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
        val atualizada = atualizar(registro, pedido.linkId, "correction_candidates") { mais ->
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
        return atualizada
    }
}
