package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.RedePublica
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Locale
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * `fetch_web_evidence_inner` (`web_evidence.rs:1103-1340`) com `GET`, o
 * [IntegridadeDeLinks.ColetorDeEvidencia] da auditoria de links.
 *
 * O canônico grava cada registro em disco e reaproveita o que já está
 * pronto e fresco; aqui a gravação é o [ArmazemDeEvidencias] que o
 * `:core:sessao` implementa, opcional: sem ele nada é reaproveitado e um 304
 * é erro, como no canônico sem registro guardado. Só o registro `PRONTA`
 * carrega corpo (`:1263-1271`: uma recoleta que falhou ou esbarrou numa
 * interação nunca sobrescreve os bytes do último registro pronto).
 *
 * O construtor público monta a rede de produção: cliente limpo
 * ([TransportePublico.clienteLimpo]), o resolvedor filtrado e a regra da
 * [RedePublica]. O interno recebe as peças, para os testes correrem num
 * servidor local que a regra pública recusaria.
 */
public class ColetorHttp internal constructor(
    clienteBase: OkHttpClient,
    dns: Dns,
    politica: UrlPublica.PoliticaDeRede,
    private val agente: AgenteDeColeta,
    private val armazem: ArmazemDeEvidencias?,
    private val relogio: () -> Instant,
) : IntegridadeDeLinks.ColetorDeEvidencia {

    public constructor(
        resolvedor: ResolvedorPublico,
        agente: AgenteDeColeta,
        armazem: ArmazemDeEvidencias? = null,
    ) : this(
        TransportePublico.clienteLimpo(),
        resolvedor,
        { RedePublica.motivoDeRecusa(it, AnalisadorDeUrlOkHttp, resolvedor) },
        agente,
        armazem,
        Instant::now,
    )

    private val transporte = TransportePublico(clienteBase, dns, politica, agente)
    private val politica = transporte.politicaGuardada()
    private val robots = LeitorDeRobots(transporte, this.politica, agente.nomeDoRobo)

    /**
     * O que fica guardado de uma coleta: o `StoredWebEvidence` do canônico.
     * O que a coleta devolve e o que o armazém recebe diferem num ponto: no
     * armazém, [corpo] é o **último corpo pronto** deste id — uma recoleta
     * que falhou ou esbarrou numa interação leva `corpo` nulo ao chamador, e
     * o armazém guarda o registro novo com o corpo que já tinha (o canônico
     * grava o registro sem `content_path` e deixa os bytes no disco).
     */
    public class Coleta(
        public val registro: RegistroDeEvidencia,
        /** Os cabeçalhos seguros da resposta (`safe_response_headers`). */
        public val cabecalhos: Map<String, String>,
        /** O corpo, só quando o registro está `PRONTA`; no armazém, o último corpo pronto. */
        public val corpo: ByteArray?,
    )

    /** A gravação dos registros de evidência, do `:core:sessao`. */
    public interface ArmazemDeEvidencias {
        /** A coleta guardada com este id, em qualquer estado, ou `null`. */
        public fun existente(id: String): Coleta?

        public fun guardar(coleta: Coleta)
    }

    override fun coletar(url: String): RegistroDeEvidencia = coletarComConteudo(url).registro

    /**
     * A coleta inteira. [revalidar] é o `force_revalidate` do canônico: não
     * reaproveita o registro fresco e envia os validadores guardados
     * (`If-None-Match`, `If-Modified-Since`), para que um 304 renove o
     * registro sem nova transferência. A auditoria chama sem ele.
     */
    public fun coletarComConteudo(url: String, revalidar: Boolean = false): Coleta {
        val agora = relogio()
        val idPreliminar = FormatoDoRegistro.sha256("http_fetch|GET|$url")
        val validada = try {
            UrlPublica.validar(url, politica)
        } catch (erro: IntegridadeDeLinks.Falha) {
            return guardar(falha(null, idPreliminar, url, EstadoDaEvidencia.BLOQUEADA, erro.message.orEmpty(), agora), null)
        }
        val urlCanonica = validada.toString()
        val id = FormatoDoRegistro.sha256("http_fetch|GET|$urlCanonica")
        val existente = armazem?.existente(id)
        if (!revalidar && existente != null) {
            val projetado = projetar(existente.registro, agora)
            if (projetado.estado == EstadoDaEvidencia.PRONTA && projetado.estadoDoCache == EstadoDoCache.FRESCO) {
                return Coleta(projetado, existente.cabecalhos, existente.corpo)
            }
        }

        val estadoDoRobots = robots.estado(validada)
        if (estadoDoRobots == EstadoDoRobots.PROIBIDO) {
            val registro = falha(
                existente, id, urlCanonica, EstadoDaEvidencia.BLOQUEADA,
                "robots.txt disallows automatic collection for this path", agora,
            ).copy(estadoDoRobots = estadoDoRobots)
            return guardar(registro, existente)
        }

        val condicionais = if (revalidar && conteudoPronto(existente)) cabecalhosCondicionais(existente) else emptyMap()
        val bruta = try {
            transporte.executar(MetodoHttp.GET, validada, condicionais, TETO_DO_CORPO_BYTES)
        } catch (erro: IntegridadeDeLinks.Falha) {
            val registro = falha(existente, id, urlCanonica, EstadoDaEvidencia.FALHOU, erro.message.orEmpty(), agora)
                .copy(estadoDoRobots = estadoDoRobots)
            return guardar(registro, existente)
        }

        if (bruta.status == 304) {
            // Só conteúdo pronto guardado pode ser renovado: um 304 sobre um
            // registro que falhou ou parou numa interação não tem o que
            // preservar, e viraria "pronto e fresco" sem corpo por 30 dias.
            if (existente == null || !conteudoPronto(existente)) {
                throw IntegridadeDeLinks.Falha("received HTTP 304 without a cached evidence record")
            }
            val coletadaEm = relogio()
            val renovado = existente.registro.copy(
                estado = EstadoDaEvidencia.PRONTA,
                estadoDoCache = EstadoDoCache.FRESCO,
                coletadaEm = FormatoDoRegistro.rfc3339(coletadaEm),
                expiraEm = FormatoDoRegistro.rfc3339(coletadaEm.plus(VALIDADE)),
                atualizadaEm = FormatoDoRegistro.rfc3339(coletadaEm),
                duracaoMs = bruta.duracaoMs,
                estadoDoRobots = estadoDoRobots,
                // O registro descreve a resposta que o renovou: se ela veio por
                // um redirecionamento novo, o destino final é o novo.
                urlFinal = bruta.urlFinal,
                cadeiaDeRedirecionamento = bruta.redirecionamentos,
                notas = existente.registro.notas + "Cache revalidated by HTTP 304; content hash preserved",
            )
            return guardar(Coleta(renovado, existente.cabecalhos + bruta.cabecalhos, existente.corpo), existente)
        }

        val coletadaEm = relogio()
        val tipoDeConteudo = bruta.cabecalhos["content-type"]
        val interacao = Conteudo.interacao(bruta.status, tipoDeConteudo, bruta.corpo)
        val estado = when {
            exigePessoa(interacao) -> EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR
            bruta.status in 200..299 -> EstadoDaEvidencia.PRONTA
            else -> EstadoDaEvidencia.FALHOU
        }
        val notas = mutableListOf<String>()
        if (exigePessoa(interacao)) {
            notas += "Automatic collection reached an interaction boundary; use isolated rendering or operator capture"
        }
        if (Conteudo.tipo(tipoDeConteudo, bruta.corpo) == "pdf") {
            notas += "PDF detected and hashed; text extraction was not attempted because no trusted extractor is configured"
        }
        val registro = base(id, urlCanonica, agora).copy(
            criadaEm = existente?.registro?.criadaEm ?: FormatoDoRegistro.rfc3339(agora),
            estado = estado,
            status = bruta.status,
            urlFinal = bruta.urlFinal,
            titulo = Conteudo.titulo(bruta.corpo, tipoDeConteudo),
            tipoDeConteudo = tipoDeConteudo,
            sha256 = if (bruta.corpo.isEmpty()) null else FormatoDoRegistro.sha256(bruta.corpo),
            coletadaEm = FormatoDoRegistro.rfc3339(coletadaEm),
            expiraEm = FormatoDoRegistro.rfc3339(coletadaEm.plus(VALIDADE)),
            estadoDoCache = if (estado == EstadoDaEvidencia.PRONTA) EstadoDoCache.FRESCO else EstadoDoCache.AUSENTE,
            estadoDoRobots = estadoDoRobots,
            estadoDeInteracao = interacao,
            bytes = bruta.corpo.size.toLong(),
            duracaoMs = bruta.duracaoMs,
            cadeiaDeRedirecionamento = bruta.redirecionamentos,
            atualizadaEm = FormatoDoRegistro.rfc3339(coletadaEm),
            notas = notas,
        )
        val corpo = if (estado == EstadoDaEvidencia.PRONTA) bruta.corpo else null
        return guardar(Coleta(registro, bruta.cabecalhos, corpo), existente)
    }

    /**
     * Cancela toda coleta em curso e fecha este coletor: depois disto,
     * qualquer coleta lança [ColetaCancelada] antes de tocar a rede — nem
     * uma consulta de nome nova sai. A coleta é bloqueante e não vê o
     * cancelamento da corrotina; o `:core:sessao` cria um coletor por
     * auditoria. O resolvedor DoH, que é do aplicativo, não é cancelado
     * (decisão do operador de 25/09/2026): uma consulta já em voo termina
     * pelo prazo dele.
     */
    public fun cancelarTudo() {
        transporte.cancelarTudo()
    }

    /** Se a coleta guardada é conteúdo pronto: só ela pode ser revalidada e renovada por 304. */
    private fun conteudoPronto(existente: Coleta?): Boolean =
        existente != null && existente.registro.estado == EstadoDaEvidencia.PRONTA && existente.corpo != null

    /**
     * Grava e devolve a coleta. No armazém, uma coleta sem corpo mantém o
     * último corpo pronto guardado (`:1263-1271` do canônico: uma recoleta
     * que falhou ou esbarrou numa interação nunca sobrescreve os bytes do
     * último registro pronto); o chamador recebe a coleta como ela é.
     */
    private fun guardar(coleta: Coleta, existente: Coleta?): Coleta {
        armazem?.guardar(Coleta(coleta.registro, coleta.cabecalhos, coleta.corpo ?: existente?.corpo))
        return coleta
    }

    private fun guardar(registro: RegistroDeEvidencia, existente: Coleta?): Coleta =
        guardar(Coleta(registro, emptyMap(), null), existente)

    /** `failed_fetch_record`. */
    private fun falha(
        existente: Coleta?,
        id: String,
        url: String,
        estado: EstadoDaEvidencia,
        nota: String,
        agora: Instant,
    ): RegistroDeEvidencia = base(id, url, agora).copy(
        criadaEm = existente?.registro?.criadaEm ?: FormatoDoRegistro.rfc3339(agora),
        estado = estado,
        notas = listOf(Erros.sanear(nota, 500)),
    )

    /** `base_record` com `HttpFetch` e `GET`. */
    private fun base(id: String, url: String, agora: Instant): RegistroDeEvidencia {
        val agoraTexto = FormatoDoRegistro.rfc3339(agora)
        return RegistroDeEvidencia(
            id = id,
            versaoDoEsquema = VERSAO_DO_ESQUEMA,
            estado = EstadoDaEvidencia.COLETANDO,
            // A URL gravada nunca leva credencial nem valor de chave sensível:
            // a URL bloqueada pela validação chega aqui como o texto citou.
            url = Erros.sanear(UrlPublica.paraRegistro(url), 2_048),
            metodo = MetodoHttp.GET,
            modoDeAcesso = ModoDeAcesso.COLETA_HTTP,
            status = null,
            urlFinal = null,
            titulo = null,
            tipoDeConteudo = null,
            sha256 = null,
            coletadaEm = null,
            expiraEm = null,
            validadeDoCache = VALIDADE_DO_CACHE,
            estadoDoCache = EstadoDoCache.AUSENTE,
            estadoDoRobots = EstadoDoRobots.NAO_SE_APLICA,
            estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
            estadoDeInteracao = EstadoDeInteracao.NENHUMA,
            resolvidaPorPessoa = false,
            bytes = null,
            duracaoMs = null,
            cadeiaDeRedirecionamento = emptyList(),
            comandoCurl = null,
            provedor = null,
            consulta = null,
            nomeDoArtefato = null,
            notas = emptyList(),
            criadaEm = agoraTexto,
            atualizadaEm = agoraTexto,
        )
    }

    /** `conditional_headers`: os validadores guardados viram cabeçalhos da origem. */
    private fun cabecalhosCondicionais(existente: Coleta?): Map<String, String> {
        if (existente == null) return emptyMap()
        val resultado = linkedMapOf<String, String>()
        existente.cabecalhos["etag"]?.let { resultado["If-None-Match"] = it }
        existente.cabecalhos["last-modified"]?.let { resultado["If-Modified-Since"] = it }
        return resultado
    }

    private fun exigePessoa(interacao: EstadoDeInteracao): Boolean =
        interacao != EstadoDeInteracao.NENHUMA && interacao != EstadoDeInteracao.RESOLVIDA_POR_PESSOA

    /** As regras de tipo e de interação, puras, sobre cabeçalho e corpo. */
    internal object Conteudo {

        /** `content_extension`: a extensão do arquivo de conteúdo. */
        fun tipo(tipoDeConteudo: String?, corpo: ByteArray): String {
            val tipo = tipoDeConteudo.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            return when {
                tipo == "application/pdf" || comecaCom(corpo, "%PDF-") -> "pdf"
                "html" in tipo -> "html"
                "markdown" in tipo -> "md"
                "json" in tipo -> "json"
                tipo == "image/png" -> "png"
                tipo == "image/jpeg" -> "jpg"
                tipo == "image/webp" -> "webp"
                tipo.startsWith("text/") -> "txt"
                else -> "bin"
            }
        }

        /** `extract_html_title`: o `<title>` nos primeiros 256 KiB de um corpo HTML. */
        fun titulo(corpo: ByteArray, tipoDeConteudo: String?): String? {
            if ("html" !in tipoDeConteudo.orEmpty().lowercase(Locale.ROOT)) return null
            val amostra = String(corpo, 0, minOf(corpo.size, 256 * 1024), Charsets.UTF_8)
            val cru = TITULO.find(amostra)?.groupValues?.get(1) ?: return null
            val titulo = Erros.sanear(MARCACAO.replace(cru, " ").trim(), 240)
            return titulo.ifEmpty { null }
        }

        /** `classify_interaction`. */
        fun interacao(status: Int, tipoDeConteudo: String?, corpo: ByteArray): EstadoDeInteracao {
            if (status == 401 || status == 403) return EstadoDeInteracao.EXIGE_LOGIN
            val tipo = tipoDeConteudo.orEmpty().lowercase(Locale.ROOT)
            if (!tipo.startsWith("text/") && "html" !in tipo) return EstadoDeInteracao.NENHUMA
            val amostra = String(corpo, 0, minOf(corpo.size, 128 * 1024), Charsets.UTF_8).lowercase(Locale.ROOT)
            return when {
                "captcha" in amostra || "cf-chl-" in amostra || "hcaptcha" in amostra -> EstadoDeInteracao.EXIGE_CAPTCHA
                "sign in" in amostra || "log in" in amostra || "login required" in amostra ->
                    EstadoDeInteracao.EXIGE_LOGIN
                "cookie consent" in amostra || "consent required" in amostra || "manage cookies" in amostra ->
                    EstadoDeInteracao.EXIGE_CONSENTIMENTO
                "paywall" in amostra || "subscribe to continue" in amostra || "subscriber-only" in amostra ->
                    EstadoDeInteracao.PAYWALL
                "confirm download" in amostra || "download confirmation" in amostra ->
                    EstadoDeInteracao.CONFIRMAR_DOWNLOAD
                else -> EstadoDeInteracao.NENHUMA
            }
        }

        private fun comecaCom(corpo: ByteArray, prefixo: String): Boolean {
            val bytes = prefixo.toByteArray(Charsets.US_ASCII)
            return corpo.size >= bytes.size && bytes.indices.all { corpo[it] == bytes[it] }
        }

        private val TITULO = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val MARCACAO = Regex("<[^>]+>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    public companion object {
        /** `SCHEMA_VERSION`. */
        public const val VERSAO_DO_ESQUEMA: String = "web_evidence.v1"

        /** `DEFAULT_CACHE_TTL`. */
        public const val VALIDADE_DO_CACHE: String = "P30D"

        /** `DEFAULT_CACHE_TTL_DAYS`. */
        public const val DIAS_DE_CACHE: Long = 30

        /** `MAX_HTTP_BODY_BYTES`. */
        public const val TETO_DO_CORPO_BYTES: Int = 8 * 1024 * 1024

        private val VALIDADE: Duration = Duration.ofDays(DIAS_DE_CACHE)

        /** `cache_state`. */
        internal fun estadoDoCache(registro: RegistroDeEvidencia, agora: Instant): EstadoDoCache {
            if (registro.estado != EstadoDaEvidencia.PRONTA && registro.estado != EstadoDaEvidencia.VENCIDA) {
                return EstadoDoCache.AUSENTE
            }
            val expiraEm = registro.expiraEm ?: return EstadoDoCache.VENCIDO
            val instante = try {
                OffsetDateTime.parse(expiraEm).toInstant()
            } catch (erro: DateTimeParseException) {
                return EstadoDoCache.VENCIDO
            }
            return if (instante > agora) EstadoDoCache.FRESCO else EstadoDoCache.VENCIDO
        }

        /** `project_record`. */
        internal fun projetar(registro: RegistroDeEvidencia, agora: Instant): RegistroDeEvidencia {
            val estadoDoCache = estadoDoCache(registro, agora)
            val estado = if (registro.estado == EstadoDaEvidencia.PRONTA && estadoDoCache == EstadoDoCache.VENCIDO) {
                EstadoDaEvidencia.VENCIDA
            } else {
                registro.estado
            }
            return registro.copy(estado = estado, estadoDoCache = estadoDoCache)
        }
    }
}
