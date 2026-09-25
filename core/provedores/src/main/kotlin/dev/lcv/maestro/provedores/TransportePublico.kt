package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.Redirecionamento
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.time.TimeSource
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `execute_public_request` e `safe_response_headers` (`web_evidence.rs:729-855`):
 * o único caminho pelo qual a auditoria de links toca a rede. O cliente é
 * montado do zero, nunca do cliente dos provedores de IA (revisão cruzada de
 * 25/09/2026): sem proxy (um proxy resolveria e conectaria do lado dele,
 * fora do resolvedor filtrado; canônico `:711-714`), sem cookies, sem
 * autenticador, sem interceptador, sem redirecionamento automático — cada
 * salto passa de novo pela validação — e só TLS moderno, para que texto
 * claro não saia nem se uma conferência de URL faltasse.
 *
 * O `User-Agent` base vai em todo salto, robots incluído. Os
 * [cabeçalhos da origem][executar] (validador condicional, variante polida do
 * agente) só vão à origem da URL inicial: um redirecionamento para outra
 * origem com esses cabeçalhos é recusado, não seguido sem eles.
 */
/**
 * A coleta foi cancelada por `cancelarTudo()`. Não é [IntegridadeDeLinks.Falha]:
 * uma falha vira registro `FALHOU` e a auditoria segue para o próximo link;
 * o cancelamento sobe até quem cancelou (o `:core:sessao`), e nada mais
 * começa neste transporte.
 */
public class ColetaCancelada : RuntimeException("collection canceled by cancelarTudo()")

internal class TransportePublico(
    base: OkHttpClient,
    dns: Dns,
    private val politica: UrlPublica.PoliticaDeRede,
    private val agente: AgenteDeColeta,
) {
    internal val cliente: OkHttpClient = base.newBuilder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(PRAZO_SEGUNDOS, TimeUnit.SECONDS)
        .build()

    /**
     * Depois de [cancelarTudo], nada mais começa: nem um salto, nem a
     * página depois do `robots.txt`, nem uma validação que consulte o DNS.
     */
    @Volatile
    var foiCancelado: Boolean = false
        private set

    /** [ColetaCancelada] se [cancelarTudo] já foi chamado. */
    fun conferirCancelamento() {
        if (foiCancelado) throw ColetaCancelada()
    }

    /** `RawHttpResponse`. */
    class RespostaBruta(
        val status: Int,
        val urlFinal: String,
        val cabecalhos: Map<String, String>,
        val corpo: ByteArray,
        val duracaoMs: Long,
        val redirecionamentos: List<Redirecionamento>,
    )

    /**
     * Uma requisição com até [MAX_REDIRECIONAMENTOS] saltos, ou
     * [IntegridadeDeLinks.Falha] com a mensagem do canônico.
     *
     * Um 304 não é redirecionamento aqui. O canônico testa
     * `status.is_redirection()` (300–399) antes de testar o 304, e como um
     * 304 não traz `Location`, ele cai em "HTTP 304 redirect omitted
     * Location" e o ramo do 304 (`:812`) nunca é alcançado — furo do
     * canônico, registrado na PR 2; aqui o 304 chega ao chamador com corpo
     * vazio, como o ramo pretende.
     */
    fun executar(
        metodo: MetodoHttp,
        urlInicial: HttpUrl,
        cabecalhosDaOrigem: Map<String, String>,
        tetoDoCorpoBytes: Int,
    ): RespostaBruta {
        val inicio = TimeSource.Monotonic.markNow()
        var atual = urlInicial
        val vistas = mutableSetOf<String>()
        val redirecionamentos = mutableListOf<Redirecionamento>()
        while (true) {
            conferirCancelamento()
            atual = UrlPublica.validar(atual.toString(), politica)
            if (!vistas.add(atual.toString())) throw IntegridadeDeLinks.Falha("redirect loop detected")

            val requisicao = Request.Builder()
                .url(atual)
                .method(metodo.name, null)
                .header("User-Agent", agente.userAgent)
            if (UrlPublica.mesmaOrigem(urlInicial, atual)) {
                cabecalhosDaOrigem.forEach { (nome, valor) -> requisicao.header(nome, valor) }
            }
            val resposta = try {
                cliente.newCall(requisicao.build()).execute()
            } catch (erro: IOException) {
                conferirCancelamento()
                throw IntegridadeDeLinks.Falha(descrever(erro))
            }
            resposta.use { r ->
                val status = r.code
                if (status in 300..399 && status != 304) {
                    val location = r.header("Location")
                        ?: throw IntegridadeDeLinks.Falha("HTTP $status redirect omitted Location")
                    if (redirecionamentos.size >= MAX_REDIRECIONAMENTOS) {
                        throw IntegridadeDeLinks.Falha("redirect chain exceeded $MAX_REDIRECIONAMENTOS hops")
                    }
                    val proxima = atual.resolve(location)
                        ?: throw IntegridadeDeLinks.Falha("redirect Location was invalid")
                    val validada = UrlPublica.validar(proxima.toString(), politica)
                    if (!UrlPublica.mesmaOrigem(urlInicial, validada) && cabecalhosDaOrigem.isNotEmpty()) {
                        throw IntegridadeDeLinks.Falha(
                            "cross-origin redirect blocked because the request uses origin-bound headers",
                        )
                    }
                    redirecionamentos += Redirecionamento(Erros.sanear(validada.toString(), 2_048), status)
                    atual = validada
                    return@use
                }

                val cabecalhos = cabecalhosSeguros(r)
                val urlFinal = Erros.sanear(atual.toString(), 2_048)
                if (metodo == MetodoHttp.HEAD || status == 304) {
                    return RespostaBruta(
                        status, urlFinal, cabecalhos, ByteArray(0), inicio.elapsedNow().inWholeMilliseconds,
                        redirecionamentos,
                    )
                }
                val anunciado = r.header("Content-Length")?.toLongOrNull()
                if (anunciado != null && anunciado > tetoDoCorpoBytes) {
                    throw IntegridadeDeLinks.Falha("HTTP body exceeds the $tetoDoCorpoBytes byte evidence limit")
                }
                val corpo = try {
                    val fonte = r.body.source()
                    // `take(max + 1)` do canônico: lê no máximo um byte além do
                    // teto, o bastante para saber que ele foi ultrapassado.
                    if (fonte.request(tetoDoCorpoBytes + 1L)) {
                        throw IntegridadeDeLinks.Falha("HTTP body exceeds the $tetoDoCorpoBytes byte evidence limit")
                    }
                    fonte.readByteArray()
                } catch (erro: IOException) {
                    conferirCancelamento()
                    throw IntegridadeDeLinks.Falha("failed to read HTTP response body: ${descrever(erro)}")
                }
                return RespostaBruta(
                    status, urlFinal, cabecalhos, corpo, inicio.elapsedNow().inWholeMilliseconds, redirecionamentos,
                )
            }
        }
    }

    /**
     * Cancela toda chamada em curso e marca o transporte: a coleta é
     * bloqueante e não vê o cancelamento da corrotina. Um transporte
     * cancelado não volta; o `:core:sessao` cria um coletor por auditoria.
     */
    fun cancelarTudo() {
        foiCancelado = true
        cliente.dispatcher.cancelAll()
    }

    /** A [politica] com a conferência de cancelamento antes de qualquer consulta de nome. */
    fun politicaGuardada(): UrlPublica.PoliticaDeRede = UrlPublica.PoliticaDeRede { url ->
        conferirCancelamento()
        politica.motivoDeRecusa(url)
    }

    private fun cabecalhosSeguros(resposta: okhttp3.Response): Map<String, String> {
        val resultado = linkedMapOf<String, String>()
        for (nome in CABECALHOS_SEGUROS) {
            resposta.header(nome)?.let { resultado[nome] = Erros.sanear(it, 1_024) }
        }
        return resultado
    }

    /**
     * "HTTP request failed: …", com a palavra que o `:core:protocolo` procura
     * para classificar o link ("timed out", "resolve"/"DNS", "TLS"). O
     * e-mail de contato do usuário, se houver, nunca sai na nota.
     */
    internal fun descrever(erro: IOException): String {
        val mensagem = erro.message ?: erro.javaClass.simpleName
        val texto = when (erro) {
            is UnknownHostException -> "HTTP request failed: DNS resolution failed: $mensagem"
            is SSLException -> "HTTP request failed: TLS handshake failed ($mensagem)"
            is InterruptedIOException -> "HTTP request failed: timed out after $PRAZO_SEGUNDOS s ($mensagem)"
            else -> "HTTP request failed: ${erro.javaClass.simpleName}: $mensagem"
        }
        return Erros.sanear(texto, 500, agente.emailDeContato)
    }

    companion object {
        /** `HTTP_TIMEOUT_SECS`. */
        const val PRAZO_SEGUNDOS = 30L

        /** `MAX_REDIRECTS`. */
        const val MAX_REDIRECIONAMENTOS = 5

        private val CABECALHOS_SEGUROS = listOf(
            "cache-control", "content-disposition", "content-length", "content-type", "etag", "last-modified",
            "location",
        )

        /**
         * O cliente de produção: novo, limpo e só TLS moderno. Os testes
         * passam um cliente próprio, que confia no certificado do servidor
         * falso e, nos casos de texto claro, admite `CLEARTEXT` de propósito.
         */
        fun clienteLimpo(): OkHttpClient =
            OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
    }
}
