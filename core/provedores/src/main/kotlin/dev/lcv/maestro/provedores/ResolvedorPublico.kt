package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.RedePublica
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps

/**
 * A resolução de nomes da auditoria de links, com as duas faces que a
 * revisão cruzada de 25/09/2026 separou:
 *
 * - [resolver], a face da [RedePublica]: devolve **todos** os endereços que
 *   o nome resolve, os privados inclusive, para que a regra pública recuse a
 *   URL com o motivo próprio ("dominio resolve para IP privado") antes de
 *   qualquer conexão, e o coletor grave o registro `BLOQUEADA` do canônico
 *   (`web_evidence.rs:1115-1131`). `null` só quando a resolução falha, que o
 *   canônico conta como não bloqueada.
 * - [lookup], a face do OkHttp: é a que conecta, e falha fechada — lança
 *   antes do socket se **qualquer** endereço resolvido for bloqueado
 *   (`link_audit.rs:251-276`: uma resposta que mistura público e privado é
 *   exatamente o caso de rebinding). Cada mensagem traz "resolve", para a
 *   classificação `ERRO_DE_DNS` do `:core:protocolo`.
 *
 * O host literal não vai a resolvedor nenhum: o IP é lido do próprio texto
 * ([AnalisadorDeUrlOkHttp.ipLiteral]). O nome vai ao [delegado] — em produção
 * o DNS sobre HTTPS do Google, [dnsDoGoogle], por decisão do operador de
 * 25/09/2026; sem recaída para o DNS do sistema.
 */
public class ResolvedorPublico internal constructor(
    private val delegado: Dns,
    private val cancelador: () -> Unit,
) : Dns, RedePublica.ResolvedorDeNomes {

    public constructor(delegado: Dns) : this(delegado, {})

    /**
     * Cancela as consultas DoH em curso. Não marca o resolvedor: ele é do
     * aplicativo e serve a mais de uma auditoria; quem não volta é o
     * transporte cancelado, que recusa qualquer consulta nova.
     */
    public fun cancelar() {
        cancelador()
    }

    override fun resolver(host: String): List<ByteArray>? = try {
        todos(host).map { it.address }
    } catch (erro: UnknownHostException) {
        null
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val enderecos = todos(hostname)
        if (enderecos.any { RedePublica.ipBloqueado(it.address) }) {
            throw UnknownHostException("host '$hostname' resolves to a private/reserved address")
        }
        return enderecos
    }

    private fun todos(host: String): List<InetAddress> {
        AnalisadorDeUrlOkHttp.ipLiteral(host)?.let { return listOf(InetAddress.getByAddress(host, it)) }
        // O `Dns` do OkHttp promete `UnknownHostException`, mas o DoH deixa
        // passar outra `IOException` (resposta HTTP que não é 200, por exemplo).
        val enderecos = try {
            delegado.lookup(host)
        } catch (erro: IOException) {
            throw UnknownHostException("host '$host' could not be resolved: ${erro.message}")
        }
        if (enderecos.isEmpty()) throw UnknownHostException("host '$host' did not resolve to any address")
        return enderecos
    }

    public companion object {
        /**
         * O resolvedor de produção: DNS sobre HTTPS do Google, com os
         * endereços de arranque fixos, sem DNS do sistema em ponto nenhum. O
         * cliente de arranque é novo e limpo — sem cookies, sem
         * autenticador, sem redirecionamento, sem proxy, só TLS moderno —
         * e usa o [cache] que o aplicativo lhe der, como o OkHttp recomenda
         * para o DoH, para a resposta servir à conferência prévia e à
         * conexão.
         */
        public fun dnsDoGoogle(cache: Cache? = null): ResolvedorPublico = sobreHttps(
            "https://dns.google/dns-query".toHttpUrl(),
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4")),
            clienteDeArranque(cache),
        )

        /** O cliente de arranque de produção, limpo (revisão cruzada de 25/09/2026). */
        internal fun clienteDeArranque(cache: Cache?): OkHttpClient = OkHttpClient.Builder()
            .cache(cache)
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .callTimeout(PRAZO_DO_DOH_SEGUNDOS, TimeUnit.SECONDS)
            .build()

        /** O DoH sobre [cliente], com [arranque] como os únicos endereços do host de [url]. */
        internal fun sobreHttps(url: HttpUrl, arranque: List<InetAddress>, cliente: OkHttpClient): ResolvedorPublico {
            val doh = DnsOverHttps.Builder()
                .client(cliente)
                .url(url)
                .bootstrapDnsHosts(arranque)
                .includeIPv6(true)
                .build()
            return ResolvedorPublico(doh) { cliente.dispatcher.cancelAll() }
        }

        private const val PRAZO_DO_DOH_SEGUNDOS = 10L
    }
}
