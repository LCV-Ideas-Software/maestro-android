package dev.lcv.maestro.protocolo

import java.time.Instant
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListBlock
import org.commonmark.node.SourceSpan
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * O portão determinístico de citações e referências ABNT — o "par Maestro"
 * da auditoria do candidato final.
 *
 * Porte de `maestro-app/src-tauri/src/abnt_citation.rs` em `68528f9` (linhas
 * 204–1590), por decisão do operador de 24/09/2026 (MAEANDR-18): o Android
 * porta os cinco estágios da auditoria do Rust atual, e este é o segundo.
 *
 * O que o canônico diz de si vale aqui: *"This module never invents
 * bibliographic metadata. Free-text inspection is deliberately conservative;
 * complete formatting is available only when the caller supplies a structured
 * `citation_manifest.v1`."* Sem manifesto, **toda citação detectada no texto
 * bloqueia** (`structured_manifest_missing`).
 *
 * As expressões regulares estão traduzidas pela régua do [TextoRust] — sem
 * `\s`, `\d`, `\w`, `\b` nem `(?U)` —, e cada uma traz ao lado o padrão Rust
 * com a linha, para conferência. Posição que entra em identificador é contada
 * em bytes UTF-8, como no Rust; ordenação de texto segue a do `BTreeSet` do
 * Rust (ordem de ponto de código).
 */
public object AuditoriaAbnt {

    internal const val ESQUEMA_DO_RESULTADO = "maestro_peer.v1"
    internal const val ESQUEMA_DA_CITACAO = "citation.v1"
    public const val ESQUEMA_DO_MANIFESTO: String = "citation_manifest.v1"
    internal const val MAXIMO_DE_CARACTERES = 2_000_000
    internal const val MAXIMO_DE_CITACOES = 500
    internal const val MAXIMO_DE_FONTES = 500

    /** O que a auditoria devolve: o resultado, ou a recusa de auditar. */
    public sealed interface Saida {
        public data class Concluida(val resultado: ResultadoAbnt) : Saida

        /** O `Err` do canônico: a auditoria não chegou a rodar. */
        public data class Recusada(val motivo: String) : Saida
    }

    /** `empty_citation_manifest`. */
    public fun manifestoVazio(hashDoProtocolo: String): ManifestoDeCitacoes =
        ManifestoDeCitacoes(
            versaoDoEsquema = ESQUEMA_DO_MANIFESTO,
            hashDoProtocolo = Saneamento.curto(EspacoUnicode.aparar(hashDoProtocolo), 128),
            citacoes = emptyList(),
            fontes = emptyList(),
        )

    /** `maestro_peer_blocks_release`. */
    public fun bloqueiaLiberacao(resultado: ResultadoAbnt): Boolean =
        resultado.statusDoParMaestro != StatusDoParMaestro.PRONTO

    /** `audit_abnt_citations_inner`, com o relógio injetado. */
    public fun auditar(
        texto: String,
        hashDoProtocolo: String?,
        manifesto: ManifestoDeCitacoes?,
        manifestoAnterior: ManifestoDeCitacoes?,
        agora: Instant,
    ): Saida {
        if (EspacoUnicode.contarPontosDeCodigo(texto) > MAXIMO_DE_CARACTERES) {
            return Saida.Recusada("citation audit input exceeds the safe text limit")
        }
        val hash = hashDoProtocolo
            ?.let { EspacoUnicode.aparar(it) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { Saneamento.curto(it, 128) }
        val referenciasBrutas = secaoDeReferencias(texto)
        val citacoesBrutas = citacoesBrutas(texto)
        val bloqueios = mutableListOf<BloqueioDeCitacao>()
        val citacoes: List<Citacao>
        val referenciasNormalizadas: List<String>
        if (manifesto != null) {
            val (validadas, referencias) =
                validarManifesto(texto, citacoesBrutas, referenciasBrutas, hash, manifesto, bloqueios)
            bloqueios += bloqueiosDePolitica(texto, citacoesBrutas + validadas)
            citacoes = validadas
            referenciasNormalizadas = referencias
        } else {
            citacoes = citacoesBrutas
            bloqueios += bloqueiosDeTextoLivre(texto, citacoes, referenciasBrutas)
            if (citacoes.isNotEmpty()) {
                bloqueios += bloqueio(
                    "structured_manifest_missing",
                    "Citacoes foram detectadas em texto livre; forneca citation_manifest.v1 para provar " +
                        "metadados, acesso e verificacao.",
                    "error", null, null, null, true,
                )
            }
            // Divergência do canônico, corrigindo uma falha dele: sem manifesto, o
            // Rust só confere estes sinais dentro de `validate_manifest`, e um
            // texto com nota bibliográfica, `<cite>` ou `apud` — e nenhuma
            // citação autor-data — saía pronto. Sem manifesto, nenhum sinal pode
            // estar representado, e todos bloqueiam.
            for (sinal in sinaisDeCitacaoSemEstrutura(texto)) {
                bloqueios += bloqueio(
                    "unstructured_citation_signal",
                    "Foi detectada citacao em nota ou HTML sem manifesto estruturado que a represente.",
                    "error", null, null, sinal, true,
                )
            }
            referenciasNormalizadas = emptyList()
        }
        // Divergência do canônico, corrigindo uma falha dele: o Rust para de
        // ler em 500 citações, 500 aspas, 500 sinais por padrão e 500
        // referências, e ignora o resto em silêncio — uma citação sem suporte
        // depois da 500ª nunca era comparada com o manifesto. Aqui o excesso
        // reprova o texto.
        if (excedeCapacidade(texto)) {
            bloqueios += bloqueio(
                "citation_capacity_exceeded",
                "O texto excede o limite seguro de citacoes, aspas, notas ou referencias auditaveis; o " +
                    "excedente nao pode ser verificado.",
                "error", null, null, null, false,
            )
        }
        if (temLacunaLegada(texto)) {
            bloqueios += bloqueio(
                "bibliographic_lacuna",
                "O texto ainda contem marcador de evidencia ou lacuna bibliografica.",
                "error", null, null, null, true,
            )
        }
        val status = when {
            bloqueios.isEmpty() -> StatusDoParMaestro.PRONTO
            bloqueios.any { !it.exigeEvidencia } -> StatusDoParMaestro.NAO_PRONTO
            else -> StatusDoParMaestro.EXIGE_EVIDENCIA
        }
        val bytesDoManifesto = manifesto?.let { JsonRust().also { json -> it.escrever(json) }.bytes() }
            ?: ByteArray(0)
        val auditId = TextoRust.sha256(
            texto.toByteArray(Charsets.UTF_8) +
                (hash ?: "").toByteArray(Charsets.UTF_8) +
                bytesDoManifesto,
        )
        return Saida.Concluida(
            ResultadoAbnt(
                versaoDoEsquema = ESQUEMA_DO_RESULTADO,
                auditId = auditId,
                verificadoEm = TextoRust.rfc3339(agora),
                hashDoProtocolo = hash,
                statusDoParMaestro = status,
                citacoes = citacoes,
                referenciasNormalizadas = referenciasNormalizadas,
                referenciasMarkdown = referenciasNormalizadas.map { "- $it" },
                referenciasHtml = referenciasNormalizadas.map { "<li>${escaparHtml(it)}</li>" },
                bloqueios = bloqueios,
                tabelaMarkdown = tabelaDeAuditoria(citacoes, bloqueios),
                diffSemantico = diffSemantico(manifesto, manifestoAnterior),
            ),
        )
    }

    // ── auxiliares de texto (linhas 220–326) ─────────────────────────────────

    /** `blocker`: todo campo passa pelo saneamento, com os limites do canônico. */
    internal fun bloqueio(
        codigo: String,
        mensagem: String,
        severidade: String,
        claimId: String?,
        fonteId: String?,
        trecho: String?,
        exigeEvidencia: Boolean,
    ): BloqueioDeCitacao = BloqueioDeCitacao(
        codigo = Saneamento.curto(codigo, 80),
        mensagem = Saneamento.texto(mensagem, 500),
        severidade = Saneamento.curto(severidade, 20),
        claimId = claimId?.let { Saneamento.curto(it, 120) },
        fonteId = fonteId?.let { Saneamento.curto(it, 120) },
        trecho = trecho?.let { Saneamento.texto(it, 360) },
        exigeEvidencia = exigeEvidencia,
    )

    /** `ascii_fold`: caixa baixa Unicode, acentos do português dobrados, resto descartado. */
    internal fun dobrarAscii(valor: String): String {
        val construtor = StringBuilder()
        for (caractere in valor.lowercase(Locale.ROOT)) {
            when (caractere) {
                in 'a'..'z', in '0'..'9' -> construtor.append(caractere)
                'á', 'à', 'ã', 'â', 'ä' -> construtor.append('a')
                'é', 'è', 'ê', 'ë' -> construtor.append('e')
                'í', 'ì', 'î', 'ï' -> construtor.append('i')
                'ó', 'ò', 'õ', 'ô', 'ö' -> construtor.append('o')
                'ú', 'ù', 'û', 'ü' -> construtor.append('u')
                'ç' -> construtor.append('c')
                else -> Unit
            }
        }
        return construtor.toString()
    }

    /**
     * Um texto pronto para a busca por valor dobrado, com a regra que o
     * canônico não tem: a comparação por [dobrarAscii] é indefinida quando o
     * dobramento esvazia o valor. Lá o `contains("")` era verdadeiro para
     * qualquer texto, e uma citação, uma referência ou um sinal ausentes
     * passavam por presentes.
     *
     * - valor sem letra nem dígito (só pontuação) nunca está presente;
     * - valor com letras que o dobramento descarta (alfabeto não latino) é
     *   comparado pela [chaveCanonica], sem dobrar.
     */
    internal class TextoDobrado(texto: String) {
        private val dobrado = dobrarAscii(texto)
        private val canonico by lazy { chaveCanonica(texto) }

        fun contem(valor: String): Boolean {
            val agulha = dobrarAscii(valor)
            if (agulha.isNotEmpty()) return dobrado.contains(agulha)
            return representaAlgo(valor) && canonico.contains(chaveCanonica(valor))
        }
    }

    /**
     * Se [valor] tem alguma letra ou dígito: só pontuação não representa nada.
     * Contado por ponto de código: uma letra fora do plano básico (Deseret,
     * por exemplo) é um par de surrogates, e nenhuma das duas unidades é
     * letra sozinha.
     */
    private fun representaAlgo(valor: String): Boolean =
        valor.codePoints().anyMatch { Character.isLetterOrDigit(it) }

    /**
     * O comprimento de [valor] na representação em que [TextoDobrado] o
     * compara: o dobrado ou, quando o dobramento o esvazia, a chave canônica,
     * em pontos de código. O canônico media só o dobrado, e um nome grego
     * tinha comprimento zero.
     */
    private fun comprimentoDobrado(valor: String): Int {
        val dobrado = dobrarAscii(valor)
        if (dobrado.isNotEmpty()) return dobrado.length
        if (!representaAlgo(valor)) return 0
        val canonico = chaveCanonica(valor)
        return canonico.codePointCount(0, canonico.length)
    }

    /**
     * Se [a] e [b] são o mesmo valor pelo dobramento, pela regra de
     * [TextoDobrado]: dois valores que dobram para vazio são comparados pela
     * [chaveCanonica], e não iguais só por isso.
     */
    private fun mesmoValorDobrado(a: String, b: String): Boolean {
        val dobradoA = dobrarAscii(a)
        val dobradoB = dobrarAscii(b)
        if (dobradoA.isEmpty() && dobradoB.isEmpty()) return chaveCanonica(a) == chaveCanonica(b)
        return dobradoA == dobradoB
    }

    /** `canonical_author_key`: espaços colapsados e caixa alta. */
    internal fun chaveCanonica(valor: String): String =
        EspacoUnicode.dividirPorEspacos(valor).joinToString(" ").uppercase(Locale.ROOT)

    /** O primeiro pedaço antes de `,` — `split(',').next()`, que sempre existe. */
    private fun antesDaVirgula(valor: String): String {
        val virgula = valor.indexOf(',')
        return if (virgula < 0) valor else valor.substring(0, virgula)
    }

    /** `displayed_surname`. */
    internal fun sobrenomeExibido(citacao: Citacao): String {
        val primeiro = EspacoUnicode.aparar(antesDaVirgula(citacao.autorExibido))
        return if (primeiro.isNotEmpty()) {
            Saneamento.texto(primeiro, 160)
        } else {
            Saneamento.texto(EspacoUnicode.aparar(citacao.chaveDoAutor), 160)
        }
    }

    /** `source_surname`. */
    private fun sobrenomeDaFonte(fonte: Fonte): String {
        val autor = fonte.autores.firstOrNull() ?: return ""
        val primeiro = EspacoUnicode.aparar(antesDaVirgula(autor.autorExibido))
        return if (primeiro.isNotEmpty()) {
            Saneamento.texto(primeiro, 160)
        } else {
            Saneamento.texto(EspacoUnicode.aparar(autor.chaveDoAutor), 160)
        }
    }

    /** `valid_year`: quatro dígitos ASCII, e opcionalmente uma letra ASCII. */
    internal fun anoValido(valor: String): Boolean {
        val bytes = valor.toByteArray(Charsets.UTF_8)
        fun digito(byte: Byte) = byte in '0'.code.toByte()..'9'.code.toByte()
        fun letra(byte: Byte) =
            byte in 'a'.code.toByte()..'z'.code.toByte() || byte in 'A'.code.toByte()..'Z'.code.toByte()
        return (bytes.size == 4 && bytes.all(::digito)) ||
            (bytes.size == 5 && bytes.take(4).all(::digito) && letra(bytes[4]))
    }

    /** `valid_sha256`: 64 dígitos hexadecimais ASCII, em qualquer caixa. */
    internal fun sha256Valido(valor: String): Boolean =
        valor.length == 64 && valor.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * `locator_is_valid`. Rust (linha 309):
     * `(?i)\b(?:p{1,2}\.|par\.|cap\.|v\.|n\.|item|se[cç][aã]o)\s*[a-z0-9ivxlcdm]+`
     */
    internal fun localizadorValido(localizador: String?): Boolean {
        val valor = localizador?.let { EspacoUnicode.aparar(it) }?.takeIf { it.isNotEmpty() } ?: return false
        return LOCALIZADOR.containsMatchIn(valor)
    }

    private val LOCALIZADOR = Regex(
        "${TextoRust.LIMITE}(?:p{1,2}\\.|par\\.|cap\\.|v\\.|n\\.|item|se[cç][aã]o)" +
            "${TextoRust.ESPACO}*[a-z0-9ivxlcdm]+",
        RegexOption.IGNORE_CASE,
    )

    /** `has_direct_quote_context`. */
    private fun temContextoDeCitacaoDireta(texto: String, inicio: Int): Boolean {
        val comeco = TextoRust.recuarPontosDeCodigo(texto, inicio, 221)
        val contexto = texto.substring(comeco, inicio)
        val aparado = EspacoUnicode.apararFim(contexto)
        if (aparado.endsWith('”') || aparado.endsWith('"')) return true
        val ultima = TextoRust.linhas(contexto).lastOrNull() ?: return false
        return EspacoUnicode.apararInicio(ultima).startsWith('>')
    }

    // ── citações em texto livre (linhas 328–420) ─────────────────────────────

    /**
     * Os dois padrões de `raw_citations`. Rust (linhas 332–333):
     * `(?i)\(([\p{L}][\p{L}\s.'’\-]{1,80}),\s*((?:18|19|20)\d{2}[a-z]?)(?:,\s*([^)]+))?\)`
     * `\b([A-ZÁÀÃÂÉÊÍÓÔÕÚÇ][\p{L}'’\-]+(?:\s+(?:e|da|de|do|dos|das|[A-ZÁÀÃÂÉÊÍÓÔÕÚÇ][\p{L}'’\-]+)){0,3})\s+\(((?:18|19|20)\d{2}[a-z]?)(?:,\s*([^)]+))?\)`
     */
    private val PADROES_DE_CITACAO = listOf(
        Regex(
            "\\(([\\p{L}][\\p{L}${TextoRust.ESPACO_CLASSE}.'’\\-]{1,80})," +
                "${TextoRust.ESPACO}*((?:18|19|20)${TextoRust.DIGITO}{2}[a-z]?)" +
                "(?:,${TextoRust.ESPACO}*([^)]+))?\\)",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "${TextoRust.LIMITE}([A-ZÁÀÃÂÉÊÍÓÔÕÚÇ][\\p{L}'’\\-]+(?:${TextoRust.ESPACO}+" +
                "(?:e|da|de|do|dos|das|[A-ZÁÀÃÂÉÊÍÓÔÕÚÇ][\\p{L}'’\\-]+)){0,3})" +
                "${TextoRust.ESPACO}+\\(((?:18|19|20)${TextoRust.DIGITO}{2}[a-z]?)" +
                "(?:,${TextoRust.ESPACO}*([^)]+))?\\)",
        ),
    )

    /** `raw_citations`. */
    internal fun citacoesBrutas(texto: String): List<Citacao> = lerCitacoesBrutas(texto)
        .map { it.second }
        .sortedWith { esquerda, direita -> OrdemRust.compare(esquerda.claimId, direita.claimId) }

    /** As citações de `raw_citations`, cada uma com a posição em que começa no texto. */
    private fun lerCitacoesBrutas(texto: String): List<Pair<Int, Citacao>> {
        val linhas = mutableListOf<Pair<Int, Citacao>>()
        val vistos = HashSet<Pair<Int, Int>>()
        for (padrao in PADROES_DE_CITACAO) {
            for (achado in padrao.findAll(texto)) {
                val inteiro = achado.value
                val inicio = achado.range.first
                val fim = achado.range.last + 1
                if (!vistos.add(inicio to fim) || linhas.size >= MAXIMO_DE_CITACOES) continue
                val autor = achado.groups[1]?.value ?: ""
                val ano = achado.groups[2]?.value ?: ""
                val localizador = achado.groups[3]
                    ?.let { Saneamento.texto(EspacoUnicode.aparar(it.value), 80) }
                    ?.takeIf { it.isNotEmpty() }
                val autorExibido = Saneamento.texto(EspacoUnicode.aparar(autor), 160)
                val chave = chaveCanonica(autorExibido)
                val normalizado =
                    if (localizador != null) "($autorExibido, $ano, $localizador)" else "($autorExibido, $ano)"
                // O `claim_id` do canônico mede posição em bytes UTF-8.
                val inicioEmBytes = TextoRust.bytesAte(texto, inicio)
                val fimEmBytes = inicioEmBytes + inteiro.toByteArray(Charsets.UTF_8).size
                linhas += inicio to Citacao(
                    versaoDoEsquema = ESQUEMA_DA_CITACAO,
                    claimId = TextoRust.sha256("$inicioEmBytes|$fimEmBytes|$inteiro"),
                    tipo = if (temContextoDeCitacaoDireta(texto, inicio)) {
                        TipoDeCitacao.CITACAO_DIRETA
                    } else {
                        TipoDeCitacao.CITACAO_INDIRETA
                    },
                    autorExibido = autorExibido,
                    chaveDoAutor = chave,
                    ano = ano,
                    localizador = localizador,
                    fonteId = "source-${dobrarAscii(chave)}-$ano",
                    acesso = AcessoAFonte.HIPOTESE_NAO_VERIFICADA,
                    verificacao = StatusDeVerificacao.EXIGE_EVIDENCIA,
                    riscoSeErrada = RiscoSeErrada.MEDIO,
                    textoOriginal = Saneamento.texto(inteiro, 240),
                    textoNormalizado = normalizado,
                    notaNormalizada = null,
                )
            }
        }
        return linhas
    }

    /** `RawReference`. A chave do autor busca pela regra de [TextoDobrado]. */
    internal class ReferenciaBruta(chave: String, val ano: String?, val texto: String) {
        val chave = TextoDobrado(chave)
    }

    /**
     * `reference_section`. Rust (linhas 389 e 397):
     * `(?im)^#{1,6}\s*(?:refer[eê]ncias(?:\s+bibliogr[aá]ficas)?|bibliografia)\s*$`
     * `(?i)\b((?:18|19|20)\d{2}[a-z]?)\b`
     */
    internal fun secaoDeReferencias(texto: String, limite: Int = MAXIMO_DE_FONTES): List<ReferenciaBruta> {
        val faixa = faixaDaSecaoDeReferencias(texto) ?: return emptyList()
        val referencias = mutableListOf<ReferenciaBruta>()
        // A primeira linha da faixa é o cabeçalho.
        for (bruta in TextoRust.linhas(texto.substring(faixa)).drop(1)) {
            val linha = EspacoUnicode.aparar(bruta)
            if (linha.isEmpty()) continue
            val semMarcador = EspacoUnicode.aparar(linha.trimStart('-', '*'))
            if (semMarcador.isEmpty()) continue
            if (referencias.size >= limite) break
            val autor = semMarcador.substringBefore('.')
            val chave = antesDaVirgula(autor)
            referencias += ReferenciaBruta(
                chave = chave,
                ano = ANO_DA_REFERENCIA.find(semMarcador)?.groups?.get(1)?.value,
                texto = Saneamento.texto(semMarcador, 1200),
            )
        }
        return referencias
    }

    /**
     * A seção de referências: do cabeçalho (`reference_section`, Rust linha
     * 389) até a linha que, aparada, começa com `#`, ou até o fim do texto.
     * As linhas terminam em `\n`, como no `str::lines` do Rust.
     */
    private fun faixaDaSecaoDeReferencias(texto: String): IntRange? {
        val cabecalho = CABECALHO_DE_REFERENCIAS.find(texto) ?: return null
        var linha = cabecalho.range.last + 1
        while (linha < texto.length) {
            val quebra = texto.indexOf('\n', linha).let { if (it < 0) texto.length else it }
            if (EspacoUnicode.aparar(texto.substring(linha, quebra)).startsWith('#')) {
                return cabecalho.range.first until linha
            }
            linha = quebra + 1
        }
        return cabecalho.range.first until texto.length
    }

    private val CABECALHO_DE_REFERENCIAS = Regex(
        "^#{1,6}${TextoRust.ESPACO}*(?:refer[eê]ncias(?:${TextoRust.ESPACO}+bibliogr[aá]ficas)?" +
            "|bibliografia)${TextoRust.ESPACO}*$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE, RegexOption.UNIX_LINES),
    )

    private val ANO_DA_REFERENCIA = Regex(
        "${TextoRust.LIMITE}((?:18|19|20)${TextoRust.DIGITO}{2}[a-z]?)${TextoRust.LIMITE}",
        RegexOption.IGNORE_CASE,
    )

    // ── bloqueios de texto livre e de política (linhas 422–640) ──────────────

    /**
     * `quote_blockers`. Rust (linha 423): `[“\"]([^“”\"\n]{12,400})[”\"]`
     */
    private fun bloqueiosDeAspas(texto: String, citacoes: List<Citacao>): List<BloqueioDeCitacao> {
        val bloqueios = mutableListOf<BloqueioDeCitacao>()
        var vistos = 0
        for (achado in ASPAS.findAll(semHtmlCru(texto))) {
            if (vistos++ >= MAXIMO_DE_CITACOES) break
            val inteiro = texto.substring(achado.range)
            val fim = achado.range.last + 1
            if (EspacoUnicode.dividirPorEspacos(inteiro).size < 4) continue
            val depois = TextoRust.avancarPontosDeCodigo(texto, fim, 220)
            val proximo = texto.substring(fim, depois)
            // Texto de citação só de pontuação não liga aspa nenhuma (regra de
            // [TextoDobrado]): `"."` ligaria toda aspa seguida de ponto.
            val citado = citacoes.any { citacao ->
                listOfNotNull(citacao.textoOriginal, citacao.textoNormalizado)
                    .any { representaAlgo(it) && proximo.contains(it) }
            }
            if (!citado) {
                bloqueios += bloqueio(
                    "direct_quote_without_citation",
                    "Trecho entre aspas nao esta ligado a uma citacao estruturada proxima.",
                    "error", null, null, inteiro, true,
                )
            }
        }
        return bloqueios
    }

    private val ASPAS = Regex("[“\"]([^“”\"\\n]{12,400})[”\"]")

    /**
     * O texto com o HTML cru trocado por espaços, do mesmo tamanho: as aspas
     * da marcação somem, e as da prosa ficam nas mesmas posições do texto
     * original.
     *
     * Quem reconhece o HTML é a commonmark-java, pela especificação CommonMark
     * 0.31.2 — o texto final é Markdown, e esta é a especificação dele. Decisão
     * do operador de 24/09/2026: o reconhecimento escrito à mão, que levou seis
     * rodadas de revisão, saiu. Dois passes:
     *
     * 1. HTML cru em linha (seção 6.6: tag, comentário, instrução, declaração
     *    e CDATA), com o bloco HTML desligado. No CommonMark, uma linha que
     *    abre com `<div>` faz do bloco inteiro HTML cru, inclusive a prosa
     *    visível dentro dele; sem o bloco, essas linhas viram parágrafo, cada
     *    tag vira um `HtmlInline` exato, e a prosa continua conferida.
     * 2. Blocos HTML cujo conteúdo o navegador esconde (seção 4.6: `<script>`
     *    e `<style>` do tipo 1, e comentário, instrução, declaração e CDATA,
     *    tipos 2 a 5), que podem atravessar linha em branco. O bloco é
     *    mascarado do início até o fechamento que a especificação define
     *    para o tipo dele (a condição de fim); o que vem depois do fechamento
     *    na mesma linha o navegador mostra, e continua conferido. Sem
     *    fechamento, o bloco vai até o fim do texto, e o navegador também
     *    esconde tudo. `<pre>` e `<textarea>`, também do tipo 1, mostram o
     *    conteúdo ao leitor, e os blocos de elemento (tipos 6 e 7) também:
     *    ficam para o passe 1, que já conferiu a prosa deles.
     *
     * Divergência do canônico, corrigindo uma falha dele: lá a aspa reta era
     * pulada depois de qualquer `<` sem `>` adiante, ou logo depois de um `=`.
     * Prosa como `2 < 3 e "..."` ou `x = "..."` escondia uma citação direta
     * sem fonte; e a aspa que fecha um atributo pareava com a que abre o
     * seguinte, desalinhando as aspas da prosa que vinham depois da tag.
     */
    private fun semHtmlCru(texto: String): String {
        val mascarado = StringBuilder(texto)
        fun mascarar(inicio: Int, fim: Int) {
            for (indice in inicio until fim) mascarado.setCharAt(indice, ' ')
        }
        fun mascarar(trechos: List<SourceSpan>) {
            for (trecho in trechos) mascarar(trecho.inputIndex, trecho.inputIndex + trecho.length)
        }
        fun linha(trecho: SourceSpan) = texto.substring(trecho.inputIndex, trecho.inputIndex + trecho.length)
        MARKDOWN_SEM_BLOCO_HTML.parse(texto).accept(
            object : AbstractVisitor() {
                override fun visit(htmlInline: HtmlInline) = mascarar(htmlInline.sourceSpans)
            },
        )
        MARKDOWN.parse(texto).accept(
            object : AbstractVisitor() {
                override fun visit(htmlBlock: HtmlBlock) {
                    val trechos = htmlBlock.sourceSpans
                    if (trechos.isEmpty()) return
                    val fechamento = BLOCOS_ESCONDIDOS.firstOrNull { it.first.containsMatchIn(linha(trechos.first())) }
                        ?.second ?: return
                    mascarar(trechos.dropLast(1))
                    // Só a última linha pode conter o fechamento: é a condição
                    // de fim do bloco. Sem ele, o bloco foi até o fim do texto.
                    val ultima = trechos.last()
                    val fim = fechamento.find(linha(ultima))?.let { it.range.last + 1 } ?: ultima.length
                    mascarar(ultima.inputIndex, ultima.inputIndex + fim)
                }
            },
        )
        return mascarado.toString()
    }

    /**
     * Os blocos HTML cujo conteúdo o navegador esconde (CommonMark 0.31.2,
     * seção 4.6), cada um com a condição de início e a de fim da
     * especificação: `<script` ou `<style` seguidos de espaço, tabulação, `>`
     * ou fim de linha (tipo 1, sem `<pre` e `<textarea`, cujo conteúdo é
     * visível), até uma tag de fim de tipo 1; `<!--` até `-->`; `<?` até
     * `?>`; `<!` e letra até `>`; `<![CDATA[` até `]]>`. A indentação de até
     * três espaços, que a especificação admite, já vem fora do trecho de
     * origem que a commonmark-java dá ao bloco.
     */
    private val BLOCOS_ESCONDIDOS: List<Pair<Regex, Regex>> = listOf(
        Regex("^<(?:script|style)(?:[ \\t>]|$)", RegexOption.IGNORE_CASE) to
            Regex("</(?:pre|script|style|textarea)>", RegexOption.IGNORE_CASE),
        Regex("^<!--") to Regex("-->"),
        Regex("^<\\?") to Regex("\\?>"),
        Regex("^<![A-Za-z]") to Regex(">"),
        Regex("^<!\\[CDATA\\[") to Regex("]]>"),
    )

    /**
     * O parser da especificação inteira, com a posição de origem de cada
     * bloco. Montado uma vez, serve a qualquer thread, como a documentação da
     * commonmark-java garante.
     */
    private val MARKDOWN: Parser = Parser.builder()
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    /** O mesmo parser sem o bloco HTML, com a posição de origem de cada nó em linha. */
    private val MARKDOWN_SEM_BLOCO_HTML: Parser = Parser.builder()
        .enabledBlockTypes(
            setOf(
                Heading::class.java,
                ThematicBreak::class.java,
                FencedCodeBlock::class.java,
                IndentedCodeBlock::class.java,
                BlockQuote::class.java,
                ListBlock::class.java,
            ),
        )
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    /**
     * `unstructured_citation_signals`. Rust (linhas 474–476):
     * `(?i)<(?:cite|blockquote|q)\b[^>]*>`
     * `(?m)\[\^[^\]\r\n]{1,80}\]`
     * `(?i)\b(?:apud|ibidem|ibid\.|idem|op\.\s*cit\.)\b`
     */
    private fun sinaisDeCitacaoSemEstrutura(texto: String): List<String> {
        val sinais = TreeSet(OrdemRust)
        for (padrao in SINAIS) {
            for ((indice, achado) in padrao.findAll(texto).withIndex()) {
                if (indice >= MAXIMO_DE_CITACOES) break
                sinais += Saneamento.texto(achado.value, 240)
            }
        }
        return sinais.toList()
    }

    private val SINAIS = listOf(
        Regex("<(?:cite|blockquote|q)${TextoRust.LIMITE}[^>]*>", RegexOption.IGNORE_CASE),
        Regex("\\[\\^[^\\]\\r\\n]{1,80}\\]"),
        Regex(
            "${TextoRust.LIMITE}(?:apud|ibidem|ibid\\.|idem|op\\.${TextoRust.ESPACO}*cit\\.)" +
                TextoRust.LIMITE,
            RegexOption.IGNORE_CASE,
        ),
    )

    /**
     * Se o texto tem mais citações, aspas, sinais ou referências do que os
     * leitores acima examinam. Conta até um além do limite, sem cortar.
     */
    internal fun excedeCapacidade(texto: String): Boolean {
        val trechos = HashSet<Pair<Int, Int>>()
        for (padrao in PADROES_DE_CITACAO) {
            for (achado in padrao.findAll(texto)) {
                trechos += achado.range.first to achado.range.last + 1
                if (trechos.size > MAXIMO_DE_CITACOES) return true
            }
        }
        if (ASPAS.findAll(semHtmlCru(texto)).take(MAXIMO_DE_CITACOES + 1).count() > MAXIMO_DE_CITACOES) return true
        if (SINAIS.any { it.findAll(texto).take(MAXIMO_DE_CITACOES + 1).count() > MAXIMO_DE_CITACOES }) return true
        return secaoDeReferencias(texto, MAXIMO_DE_FONTES + 1).size > MAXIMO_DE_FONTES
    }

    /** `document_policy_blockers`. */
    private fun bloqueiosDePolitica(texto: String, citacoes: List<Citacao>): List<BloqueioDeCitacao> {
        val bloqueios = bloqueiosDeAspas(texto, citacoes).toMutableList()
        val dobrado = dobrarAscii(texto)
        if (dobrado.contains("wikipediaorg") || dobrado.contains("ptwikipediaorg")) {
            bloqueios += bloqueio(
                "prohibited_source",
                "Wikipedia foi detectada como suporte bibliografico e exige remocao ou substituicao por " +
                    "fonte permitida.",
                "error", null, null, null, false,
            )
        }
        if (dobrado.contains("protocoloeditorialv") ||
            dobrado.contains("deacordocomoprotocoloeditorial") ||
            dobrado.contains("nesteprotocolo") ||
            dobrado.contains("esteprotocolo")
        ) {
            bloqueios += bloqueio(
                "public_protocol_self_reference",
                "O texto publico contem autorreferencia ao protocolo editorial.",
                "error", null, null, null, false,
            )
        }
        var ultimaPosicao: Int? = null
        for (linha in TextoRust.linhas(texto)) {
            val cabecalho = dobrarAscii(EspacoUnicode.aparar(linha.trimStart('#')))
            val posicao = when (cabecalho) {
                "referencias", "referenciasbibliograficas" -> 0
                "fontesonline", "fontesconsultaveisonline", "fontesconsultadasonline" -> 1
                "leiturascomplementares" -> 2
                else -> null
            } ?: continue
            if (ultimaPosicao != null && posicao < ultimaPosicao) {
                bloqueios += bloqueio(
                    "bibliographic_apparatus_order_invalid",
                    "A ordem do aparato deve ser referencias ABNT, fontes consultaveis online e leituras " +
                        "complementares.",
                    "error", null, null, linha, false,
                )
                break
            }
            ultimaPosicao = posicao
        }
        return bloqueios
    }

    /** `raw_text_blockers`. */
    private fun bloqueiosDeTextoLivre(
        texto: String,
        citacoes: List<Citacao>,
        referencias: List<ReferenciaBruta>,
    ): List<BloqueioDeCitacao> {
        val bloqueios = bloqueiosDePolitica(texto, citacoes).toMutableList()
        if (citacoes.isNotEmpty() && referencias.isEmpty()) {
            bloqueios += bloqueio(
                "reference_section_missing",
                "O texto contem citacoes autor-data, mas nao possui secao final de referencias.",
                "error", null, null, null, true,
            )
        }
        val usadas = HashSet<Int>()
        for (citacao in citacoes) {
            if (citacao.tipo == TipoDeCitacao.CITACAO_DIRETA && !localizadorValido(citacao.localizador)) {
                bloqueios += bloqueio(
                    "direct_quote_locator_missing",
                    "Citacao direta requer localizador verificavel.",
                    "error", citacao.claimId, citacao.fonteId, citacao.textoOriginal, true,
                )
            }
            val primeiroToken = EspacoUnicode.dividirPorEspacos(citacao.chaveDoAutor).firstOrNull() ?: ""
            val casada = referencias.withIndex().firstOrNull { (_, referencia) ->
                (referencia.chave.contem(citacao.chaveDoAutor) ||
                    (comprimentoDobrado(primeiroToken) >= 4 && referencia.chave.contem(primeiroToken))) &&
                    referencia.ano == citacao.ano
            }
            if (casada != null) {
                usadas += casada.index
            } else if (referencias.isNotEmpty()) {
                bloqueios += bloqueio(
                    "citation_without_reference",
                    "Citacao no corpo nao possui referencia final inequivoca com autor e ano correspondentes.",
                    "error", citacao.claimId, citacao.fonteId, citacao.textoOriginal, true,
                )
            }
        }
        for ((indice, referencia) in referencias.withIndex()) {
            if (indice !in usadas) {
                bloqueios += bloqueio(
                    "reference_without_body_use",
                    "Referencia final nao possui citacao correspondente no corpo.",
                    "error", null, null, referencia.texto, false,
                )
            }
            if (referencia.ano == null || referencia.texto.count { it == '.' } < 2) {
                bloqueios += bloqueio(
                    "reference_required_fields_missing",
                    "Referencia em texto livre nao apresenta campos mecanicamente suficientes; forneca " +
                        "manifesto estruturado.",
                    "error", null, null, referencia.texto, true,
                )
            }
        }
        return bloqueios
    }

    // ── fontes estruturadas e formatação (linhas 642–885) ────────────────────

    private fun vazio(valor: String?): Boolean = EspacoUnicode.aparar(valor ?: "").isEmpty()

    /** O valor aparado, se não vazio — o `.map(str::trim).filter(|v| !v.is_empty())`. */
    private fun preenchido(valor: String?): String? =
        valor?.let { EspacoUnicode.aparar(it) }?.takeIf { it.isNotEmpty() }

    /** `required_source_fields`. */
    private fun camposObrigatoriosAusentes(fonte: Fonte): List<String> {
        val ausentes = mutableListOf<String>()
        if (fonte.autores.isEmpty()) ausentes += "authors"
        if (vazio(fonte.titulo)) ausentes += "title"
        if (!anoValido(EspacoUnicode.aparar(fonte.ano))) ausentes += "year"
        if (fonte.verificacao == StatusDeVerificacao.VERIFICADA &&
            fonte.sha256DaVerificacao?.let { sha256Valido(EspacoUnicode.aparar(it)) } != true
        ) {
            ausentes += "verification_sha256"
        }
        when (fonte.tipo) {
            TipoDeFonte.LIVRO -> {
                if (vazio(fonte.local)) ausentes += "place"
                if (vazio(fonte.editora)) ausentes += "publisher"
            }
            TipoDeFonte.CAPITULO -> {
                if (vazio(fonte.tituloDoConjunto)) ausentes += "container_title"
                if (vazio(fonte.paginas)) ausentes += "pages"
                if (vazio(fonte.local)) ausentes += "place"
                if (vazio(fonte.editora)) ausentes += "publisher"
            }
            TipoDeFonte.ARTIGO -> if (vazio(fonte.tituloDoConjunto)) ausentes += "container_title"
            TipoDeFonte.ONLINE -> {
                if (vazio(fonte.url)) ausentes += "url"
                if (vazio(fonte.acessadoEm)) ausentes += "accessed_at"
            }
            TipoDeFonte.OUTRO -> Unit
        }
        return ausentes
    }

    /** `author_text`. */
    private fun textoDosAutores(autores: List<AutorDaFonte>): String =
        autores.map { autor ->
            val chave = chaveCanonica(autor.chaveDoAutor)
            val virgula = autor.autorExibido.indexOf(',')
            val prenomes = if (virgula < 0) "" else EspacoUnicode.aparar(autor.autorExibido.substring(virgula + 1))
            if (prenomes.isEmpty()) chave else "$chave, ${Saneamento.texto(prenomes, 180)}"
        }.filter { it.isNotEmpty() }.joinToString("; ")

    /** `format_in_text_citation`. */
    private fun citacaoNoTexto(citacao: Citacao, fonte: Fonte): String {
        val autor = sobrenomeExibido(citacao)
        val localizador = preenchido(citacao.localizador)?.let { Saneamento.texto(it, 100) }
        val sufixo = localizador?.let { ", $it" } ?: ""
        return when (citacao.tipo) {
            TipoDeCitacao.APUD ->
                "($autor, ${EspacoUnicode.aparar(citacao.ano)}, apud ${sobrenomeDaFonte(fonte)}, " +
                    "${EspacoUnicode.aparar(fonte.ano)}$sufixo)"
            TipoDeCitacao.MENCAO_GENERICA -> "$autor (${EspacoUnicode.aparar(citacao.ano)})"
            TipoDeCitacao.CITACAO_DIRETA, TipoDeCitacao.CITACAO_INDIRETA, TipoDeCitacao.PARAFRASE ->
                "($autor, ${EspacoUnicode.aparar(citacao.ano)}$sufixo)"
        }
    }

    /** `format_footnote`. */
    private fun notaDeRodape(fonte: Fonte, localizador: String?): String {
        val nota = StringBuilder(referenciaFormatada(fonte))
        preenchido(localizador)?.let {
            nota.append(' ').append(Saneamento.texto(it, 100))
            if (!nota.endsWith('.')) nota.append('.')
        }
        return nota.toString()
    }

    /** `format_reference`. */
    internal fun referenciaFormatada(fonte: Fonte): String {
        val partes = mutableListOf<String>()
        val autores = textoDosAutores(fonte.autores)
        if (autores.isNotEmpty()) partes += "$autores."
        val titulo = StringBuilder(Saneamento.texto(EspacoUnicode.aparar(fonte.titulo), 500))
        preenchido(fonte.subtitulo)?.let { titulo.append(": ").append(Saneamento.texto(it, 300)) }
        if (titulo.isNotEmpty()) partes += "$titulo."
        preenchido(fonte.edicao)?.let { partes += "${Saneamento.texto(it, 80)}." }
        preenchido(fonte.tituloDoConjunto)?.let { partes += "In: ${Saneamento.texto(it, 400)}." }
        val local = EspacoUnicode.aparar(fonte.local ?: "")
        val editora = EspacoUnicode.aparar(fonte.editora ?: "")
        val ano = EspacoUnicode.aparar(fonte.ano)
        if (fonte.tipo == TipoDeFonte.ARTIGO) {
            val publicacao = mutableListOf<String>()
            if (local.isNotEmpty()) publicacao += Saneamento.texto(local, 160)
            preenchido(fonte.volume)?.let { publicacao += "v. ${Saneamento.texto(it, 80)}" }
            preenchido(fonte.numero)?.let { publicacao += "n. ${Saneamento.texto(it, 80)}" }
            preenchido(fonte.paginas)?.let { publicacao += "p. ${Saneamento.texto(it, 100)}" }
            if (anoValido(ano)) publicacao += ano
            if (publicacao.isNotEmpty()) partes += "${publicacao.joinToString(", ")}."
        } else {
            val publicacao = Saneamento.texto(local, 160) +
                (if (local.isNotEmpty() && editora.isNotEmpty()) ": " else "") +
                Saneamento.texto(editora, 240)
            when {
                publicacao.isNotEmpty() && anoValido(ano) -> partes += "$publicacao, $ano."
                publicacao.isNotEmpty() -> partes += "$publicacao."
                anoValido(ano) -> partes += "$ano."
            }
            preenchido(fonte.volume)?.let { partes += "v. ${Saneamento.texto(it, 80)}." }
            preenchido(fonte.numero)?.let { partes += "n. ${Saneamento.texto(it, 80)}." }
            preenchido(fonte.paginas)?.let { partes += "p. ${Saneamento.texto(it, 100)}." }
        }
        preenchido(fonte.doi)?.let { partes += "DOI: ${Saneamento.texto(it, 240)}." }
        preenchido(fonte.url)?.let { partes += "Disponivel em: ${Saneamento.texto(it, 1000)}." }
        preenchido(fonte.acessadoEm)?.let { partes += "Acesso em: ${Saneamento.texto(it, 120)}." }
        return partes.joinToString(" ")
    }

    /** `escape_html`. */
    internal fun escaparHtml(valor: String): String =
        valor.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    // ── manifesto (linhas 887–1297) ──────────────────────────────────────────

    /** `validate_manifest`. */
    private fun validarManifesto(
        texto: String,
        citacoesBrutas: List<Citacao>,
        referenciasBrutas: List<ReferenciaBruta>,
        hashDoPedido: String?,
        manifesto: ManifestoDeCitacoes,
        bloqueios: MutableList<BloqueioDeCitacao>,
    ): Pair<List<Citacao>, List<String>> {
        if (manifesto.versaoDoEsquema != ESQUEMA_DO_MANIFESTO) {
            bloqueios += bloqueio(
                "manifest_schema_invalid",
                "O manifesto de citacoes nao usa citation_manifest.v1.",
                "error", null, null, null, false,
            )
        }
        if (hashDoPedido != null && hashDoPedido != manifesto.hashDoProtocolo) {
            bloqueios += bloqueio(
                "protocol_hash_mismatch",
                "O manifesto nao esta vinculado ao hash do protocolo ativo.",
                "error", null, null, null, false,
            )
        }
        if (manifesto.citacoes.size > MAXIMO_DE_CITACOES || manifesto.fontes.size > MAXIMO_DE_FONTES) {
            bloqueios += bloqueio(
                "manifest_capacity_exceeded",
                "O manifesto excede o limite seguro de citacoes ou fontes.",
                "error", null, null, null, false,
            )
        }
        if (vazio(manifesto.hashDoProtocolo)) {
            bloqueios += bloqueio(
                "manifest_protocol_hash_missing",
                "O manifesto nao registra o hash do protocolo editorial ativo.",
                "error", null, null, null, false,
            )
        }
        val fontes = HashMap<String, Fonte>()
        for (fonte in manifesto.fontes.take(MAXIMO_DE_FONTES)) {
            if (vazio(fonte.fonteId)) {
                bloqueios += bloqueio(
                    "source_id_missing",
                    "Uma fonte estruturada nao possui source_id.",
                    "error", null, null, null, false,
                )
                continue
            }
            // `BTreeMap::insert` substitui: a fonte que vem depois fica.
            if (fontes.put(fonte.fonteId, fonte) != null) {
                bloqueios += bloqueio(
                    "source_id_duplicate",
                    "O manifesto contem source_id duplicado.",
                    "error", null, fonte.fonteId, null, false,
                )
            }
        }
        val fontesUsadas = HashSet<String>()
        val claimsVistos = HashSet<String>()
        val citacoes = mutableListOf<Citacao>()
        val textoDobrado = TextoDobrado(texto)
        for (citacao in manifesto.citacoes.take(MAXIMO_DE_CITACOES)) {
            val claimId = Saneamento.curto(citacao.claimId, 120)
            val fonteId = Saneamento.curto(citacao.fonteId, 120)
            if (vazio(claimId)) {
                bloqueios += bloqueio(
                    "claim_id_missing",
                    "Uma citacao estruturada nao possui claim_id.",
                    "error", null, fonteId, citacao.textoOriginal, false,
                )
            } else if (!claimsVistos.add(claimId)) {
                bloqueios += bloqueio(
                    "claim_id_duplicate",
                    "O manifesto contem claim_id duplicado.",
                    "error", claimId, fonteId, citacao.textoOriginal, false,
                )
            }
            if (citacao.versaoDoEsquema != ESQUEMA_DA_CITACAO) {
                bloqueios += bloqueio(
                    "citation_schema_invalid",
                    "A citacao nao usa citation.v1.",
                    "error", claimId, fonteId, citacao.textoOriginal, false,
                )
            }
            if (citacao.tipo == TipoDeCitacao.CITACAO_DIRETA && !localizadorValido(citacao.localizador)) {
                bloqueios += bloqueio(
                    "direct_quote_locator_missing",
                    "Citacao direta estruturada requer localizador verificavel.",
                    "error", claimId, fonteId, citacao.textoOriginal, true,
                )
            }
            if (vazio(citacao.autorExibido) || vazio(citacao.chaveDoAutor) ||
                !anoValido(EspacoUnicode.aparar(citacao.ano))
            ) {
                bloqueios += bloqueio(
                    "citation_required_fields_missing",
                    "A citacao requer author_display, author_key e ano valido.",
                    "error", claimId, fonteId, citacao.textoOriginal, true,
                )
            }
            if (!mesmoValorDobrado(sobrenomeExibido(citacao), EspacoUnicode.aparar(citacao.chaveDoAutor))) {
                bloqueios += bloqueio(
                    "citation_canonical_author_mismatch",
                    "author_display da citacao deve preservar integralmente a chave canonica author_key.",
                    "error", claimId, fonteId, citacao.autorExibido, false,
                )
            }
            val fonte = fontes[citacao.fonteId]
            if (fonte == null) {
                bloqueios += bloqueio(
                    "citation_source_missing",
                    "source_id da citacao nao existe no manifesto.",
                    "error", claimId, fonteId, citacao.textoOriginal, true,
                )
                citacoes += citacao
                continue
            }
            fontesUsadas += citacao.fonteId
            if (citacao.verificacao != StatusDeVerificacao.VERIFICADA ||
                fonte.verificacao != StatusDeVerificacao.VERIFICADA ||
                citacao.acesso == AcessoAFonte.HIPOTESE_NAO_VERIFICADA ||
                citacao.acesso == AcessoAFonte.INFERENCIA_CONTEXTUAL
            ) {
                bloqueios += bloqueio(
                    "source_not_verified",
                    "A citacao depende de fonte sem verificacao suficiente.",
                    "error", claimId, fonteId, citacao.textoOriginal, true,
                )
            }
            if (citacao.tipo == TipoDeCitacao.CITACAO_DIRETA &&
                citacao.acesso != AcessoAFonte.DOCUMENTO_INTEGRAL_ABERTO &&
                citacao.acesso != AcessoAFonte.EXCERTO_CONSULTADO
            ) {
                bloqueios += bloqueio(
                    "direct_quote_source_access_insufficient",
                    "Citacao direta exige documento integral aberto ou excerto efetivamente consultado.",
                    "error", claimId, fonteId, citacao.textoOriginal, true,
                )
            }
            if (fonte.proibida) {
                bloqueios += bloqueio(
                    "prohibited_source",
                    "A fonte foi marcada como proibida pelo protocolo ativo.",
                    "error", claimId, fonteId, null, false,
                )
            }
            if (fonte.verificacao == StatusDeVerificacao.EM_QUARENTENA || fonte.motivoDaQuarentena != null) {
                bloqueios += bloqueio(
                    "source_quarantined",
                    "A fonte permanece em quarentena bibliografica.",
                    "error", claimId, fonteId, fonte.motivoDaQuarentena, true,
                )
            }
            val chavesDaFonte = fonte.autores.map { chaveCanonica(it.chaveDoAutor) }.toSet()
            if (citacao.tipo != TipoDeCitacao.APUD && chaveCanonica(citacao.chaveDoAutor) !in chavesDaFonte) {
                bloqueios += bloqueio(
                    "canonical_author_mismatch",
                    "author_key da citacao nao corresponde a autoria canonica fornecida pela fonte.",
                    "error", claimId, fonteId, citacao.textoOriginal, false,
                )
            }
            val normalizada = citacaoNoTexto(citacao, fonte)
            val originalPresente = preenchido(citacao.textoOriginal)
                ?.let { textoDobrado.contem(it) } ?: false
            val normalizadaPresente = textoDobrado.contem(normalizada)
            if (!originalPresente && !normalizadaPresente) {
                bloqueios += bloqueio(
                    "manifest_citation_absent_from_text",
                    "A citacao do manifesto nao foi localizada no texto final.",
                    "error", claimId, fonteId, citacao.textoOriginal, false,
                )
            }
            citacoes += citacao.copy(
                textoNormalizado = normalizada,
                notaNormalizada = notaDeRodape(fonte, citacao.localizador),
            )
        }
        val referencias = mutableListOf<String>()
        for (fonte in manifesto.fontes.take(MAXIMO_DE_FONTES)) {
            val ausentes = camposObrigatoriosAusentes(fonte)
            if (ausentes.isNotEmpty()) {
                bloqueios += bloqueio(
                    "reference_required_fields_missing",
                    "Campos obrigatorios ausentes: ${ausentes.joinToString(", ")}.",
                    "error", null, fonte.fonteId, null, true,
                )
            }
            for (autor in fonte.autores) {
                if (EspacoUnicode.aparar(autor.chaveDoAutor) != chaveCanonica(autor.chaveDoAutor)) {
                    bloqueios += bloqueio(
                        "canonical_author_key_malformed",
                        "author_key deve preservar a chave canonica completa em maiusculas.",
                        "error", null, fonte.fonteId, autor.chaveDoAutor, false,
                    )
                }
                val chaveExibida = EspacoUnicode.aparar(antesDaVirgula(autor.autorExibido))
                if (chaveExibida.isEmpty() ||
                    !mesmoValorDobrado(chaveExibida, EspacoUnicode.aparar(autor.chaveDoAutor))
                ) {
                    bloqueios += bloqueio(
                        "canonical_author_display_mismatch",
                        "author_display deve iniciar pela mesma chave canonica completa de author_key.",
                        "error", null, fonte.fonteId, autor.autorExibido, false,
                    )
                }
            }
            if (fonte.fonteId !in fontesUsadas) {
                bloqueios += bloqueio(
                    "reference_without_body_use",
                    "Fonte estruturada nao e usada por nenhuma citacao do manifesto.",
                    "error", null, fonte.fonteId, null, false,
                )
            }
            val formatada = referenciaFormatada(fonte)
            if (formatada.isNotEmpty() && !textoDobrado.contem(formatada)) {
                bloqueios += bloqueio(
                    "reference_not_normalized",
                    "A referencia estruturada ainda nao aparece no texto com a forma normalizada gerada " +
                        "pelo motor.",
                    "error", null, fonte.fonteId, formatada, false,
                )
            }
            referencias += formatada
        }
        for (referencia in referenciasBrutas) {
            val chaveBruta = dobrarAscii(referencia.texto)
            if (referencias.none { dobrarAscii(it) == chaveBruta }) {
                bloqueios += bloqueio(
                    "reference_not_in_manifest",
                    "A secao final contem referencia que nao corresponde a uma fonte normalizada do manifesto.",
                    "error", null, null, referencia.texto, false,
                )
            }
        }
        // Divergência do canônico, por decisão do operador de 24/09/2026: cada
        // ocorrência no corpo consome uma entrada própria do manifesto. Lá uma
        // entrada cobria todas as ocorrências iguais, e a segunda afirmação com
        // o mesmo autor, ano e localizador saía sem verificação própria. A
        // ocorrência dentro da seção de referências (num título, por exemplo)
        // não consome entrada: só precisa estar representada, como no canônico.
        // A seção vai do cabeçalho à linha que começa o próximo, como em
        // [secaoDeReferencias]; um apêndice depois dela é corpo.
        val naSecaoDeReferencias = faixaDaSecaoDeReferencias(texto)?.let { faixa ->
            lerCitacoesBrutas(texto).filter { it.first in faixa }.mapTo(HashSet()) { it.second.claimId }
        }.orEmpty()
        val livres = citacoes.toMutableList()
        for (bruta in citacoesBrutas) {
            val casa = { estruturada: Citacao ->
                mesmoValorDobrado(estruturada.chaveDoAutor, bruta.chaveDoAutor) &&
                    EspacoUnicode.aparar(estruturada.ano) == EspacoUnicode.aparar(bruta.ano) &&
                    mesmoValorDobrado(estruturada.localizador ?: "", bruta.localizador ?: "")
            }
            val representada = if (bruta.claimId !in naSecaoDeReferencias) {
                val indice = livres.indexOfFirst(casa)
                if (indice >= 0) livres.removeAt(indice)
                indice >= 0
            } else {
                citacoes.any(casa)
            }
            if (!representada) {
                bloqueios += bloqueio(
                    "body_citation_not_in_manifest",
                    "O texto contem citacao autor-data sem entrada inequivoca no manifesto estruturado.",
                    "error", bruta.claimId, bruta.fonteId, bruta.textoOriginal, true,
                )
            }
        }
        for (sinal in sinaisDeCitacaoSemEstrutura(texto)) {
            val representado = citacoes.any { citacao ->
                listOfNotNull(citacao.textoOriginal, citacao.textoNormalizado)
                    .any { TextoDobrado(it).contem(sinal) }
            }
            if (!representado) {
                bloqueios += bloqueio(
                    "unstructured_citation_signal",
                    "Foi detectada citacao em nota ou HTML sem entrada inequivoca no manifesto.",
                    "error", null, null, sinal, true,
                )
            }
        }
        return citacoes to referencias
    }

    /** `semantic_diff`. */
    internal fun diffSemantico(atual: ManifestoDeCitacoes?, anterior: ManifestoDeCitacoes?): String {
        if (atual == null) return "Manifesto estruturado ausente; diff semantico indisponivel."
        if (anterior == null) return "Primeiro manifesto estruturado; nenhuma versao anterior para comparar."
        fun porClaim(manifesto: ManifestoDeCitacoes): TreeMap<String, String> {
            val mapa = TreeMap<String, String>(OrdemRust)
            for (citacao in manifesto.citacoes) {
                mapa[citacao.claimId] = TextoRust.sha256(JsonRust().also { citacao.escrever(it) }.bytes())
            }
            return mapa
        }
        fun porFonte(manifesto: ManifestoDeCitacoes): TreeMap<String, String> {
            val mapa = TreeMap<String, String>(OrdemRust)
            for (fonte in manifesto.fontes) {
                mapa[fonte.fonteId] = TextoRust.sha256(JsonRust().also { fonte.escrever(it) }.bytes())
            }
            return mapa
        }
        fun lista(chaves: List<String>) = if (chaves.isEmpty()) "nenhuma" else chaves.joinToString(", ")
        val claimsAtuais = porClaim(atual)
        val claimsAnteriores = porClaim(anterior)
        val fontesAtuais = porFonte(atual)
        val fontesAnteriores = porFonte(anterior)
        return "Citacoes adicionadas: ${lista(claimsAtuais.keys.filter { it !in claimsAnteriores })}\n" +
            "Citacoes removidas: ${lista(claimsAnteriores.keys.filter { it !in claimsAtuais })}\n" +
            "Citacoes alteradas: ${lista(claimsAtuais.filter { (chave, valor) ->
                claimsAnteriores[chave]?.let { it != valor } ?: false
            }.keys.toList())}\n" +
            "Fontes adicionadas: ${lista(fontesAtuais.keys.filter { it !in fontesAnteriores })}\n" +
            "Fontes removidas: ${lista(fontesAnteriores.keys.filter { it !in fontesAtuais })}\n" +
            "Fontes alteradas: ${lista(fontesAtuais.filter { (chave, valor) ->
                fontesAnteriores[chave]?.let { it != valor } ?: false
            }.keys.toList())}"
    }

    /** `audit_table`: os tipos saem no formato `{:?}` do Rust. */
    private fun tabelaDeAuditoria(citacoes: List<Citacao>, bloqueios: List<BloqueioDeCitacao>): String {
        val linhas = mutableListOf(
            "| Claim | Fonte | Tipo | Verificacao | Blockers |",
            "| --- | --- | --- | --- | ---: |",
        )
        for (citacao in citacoes) {
            val quantos = bloqueios.count { it.claimId == citacao.claimId }
            linhas += "| ${Saneamento.curto(citacao.claimId, 80)} | ${Saneamento.curto(citacao.fonteId, 80)} | " +
                "${citacao.tipo.depuracao} | ${citacao.verificacao.depuracao} | $quantos |"
        }
        if (citacoes.isEmpty()) linhas += "| — | — | — | — | 0 |"
        return linhas.joinToString("\n")
    }

    /** `contains_legacy_lacuna`. */
    internal fun temLacunaLegada(texto: String): Boolean {
        val dobrado = dobrarAscii(texto)
        return listOf("evidenciapendente", "edicaoconsultadanaoidentificada", "sineloco", "sinenomine", "sinedata")
            .any { dobrado.contains(it) }
    }
}

/**
 * A ordem de texto do `BTreeMap`/`BTreeSet` do Rust: bytes UTF-8, que é a
 * ordem dos pontos de código. A ordem natural do Kotlin compara unidades
 * UTF-16 e discorda dela quando um lado tem caractere suplementar e o outro um
 * caractere entre `U+E000` e `U+FFFF`.
 */
internal object OrdemRust : Comparator<String> {
    override fun compare(esquerda: String, direita: String): Int {
        var i = 0
        var j = 0
        while (i < esquerda.length && j < direita.length) {
            val a = esquerda.codePointAt(i)
            val b = direita.codePointAt(j)
            if (a != b) return a.compareTo(b)
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        return (esquerda.length - i).compareTo(direita.length - j)
    }
}
