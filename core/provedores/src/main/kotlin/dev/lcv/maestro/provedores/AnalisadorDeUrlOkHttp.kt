package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * O parser de URL da auditoria de links: o `HttpUrl` do OkHttp, por decisão
 * do operador de 25/09/2026. O canônico usa a crate `url` (WHATWG); o
 * `HttpUrl` lê `http` e `https` como um navegador lê — mais tolerante que a
 * RFC 3986, com punycode para nome internacionalizado — e devolve `null` no
 * que não parseia.
 *
 * Só `http` e `https` passam pelo `HttpUrl`. Outro esquema (`mailto:`, que a
 * auditoria reconhece sem coletar; `javascript:`, `data:`, que ela recusa)
 * é lido pelo `java.net.URI` só para dizer qual é o esquema; sem esquema, ou
 * mal formado, é `null`, e a auditoria recusa (`IntegridadeDeLinks.kt`,
 * `normalizar`).
 *
 * [IntegridadeDeLinks.UrlAnalisada.ipDoHost] vem só de host literal, nunca de
 * DNS (revisão cruzada de 25/09/2026): IPv6 é o que o `HttpUrl` já validou
 * entre colchetes; IPv4 é a forma numérica com ponto que o `inet_aton` e o
 * JDK aceitam, com 1 a 4 partes dentro dos limites (`127.1` e `2130706433`
 * são `127.0.0.1`). Uma sequência numérica fora dos limites, como
 * `999.999.999.999`, não é literal e não vai ao `InetAddress`, que a mandaria
 * ao DNS; fica sem endereço e, sem resposta do resolvedor, falha fechada.
 */
public object AnalisadorDeUrlOkHttp : IntegridadeDeLinks.AnalisadorDeUrl {

    override fun analisar(url: String): IntegridadeDeLinks.UrlAnalisada? {
        val http = url.toHttpUrlOrNull()
        if (http != null) {
            return IntegridadeDeLinks.UrlAnalisada(
                esquema = http.scheme,
                host = http.host,
                usuario = http.username,
                senha = if (temSenha(url)) http.password else null,
                caminho = http.encodedPath,
                serializada = http.toString(),
                ipDoHost = ipLiteral(http.host),
            )
        }
        // `http://` ou `https://` que o HttpUrl recusou é URL malformada, e não
        // um esquema desconhecido.
        if (COMECA_COM_HTTP.containsMatchIn(url)) return null
        return try {
            val uri = URI(url)
            val esquema = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            val info = uri.rawUserInfo
            IntegridadeDeLinks.UrlAnalisada(
                esquema = esquema,
                host = uri.host,
                usuario = info?.substringBefore(':') ?: "",
                senha = info?.takeIf { ':' in it }?.substringAfter(':'),
                caminho = uri.rawPath ?: uri.rawSchemeSpecificPart ?: "",
                serializada = url,
            )
        } catch (erro: URISyntaxException) {
            null
        }
    }

    /**
     * Se a autoridade da URL traz senha, mesmo vazia: `https://:@host` tem
     * senha `""` para a crate `url` (`Some("")`), e o `HttpUrl` devolve `""`
     * tanto para a senha ausente quanto para a vazia. A auditoria recusa
     * qualquer credencial embutida, então a presença é o que importa.
     */
    internal fun temSenha(url: String): Boolean {
        val depoisDoEsquema = url.indexOf("://").takeIf { it >= 0 }?.let { url.substring(it + 3) } ?: return false
        val fim = depoisDoEsquema.indexOfAny(charArrayOf('/', '?', '#')).takeIf { it >= 0 } ?: depoisDoEsquema.length
        val autoridade = depoisDoEsquema.substring(0, fim)
        val arroba = autoridade.lastIndexOf('@')
        return arroba >= 0 && ':' in autoridade.substring(0, arroba)
    }

    /** Os bytes do host quando ele é um IP literal; `null` quando é um nome. */
    internal fun ipLiteral(host: String): ByteArray? {
        if (':' in host) return InetAddress.getByName(host).address
        if (!SO_DIGITOS_E_PONTOS.matches(host)) return null
        val partes = host.split('.')
        if (partes.size > 4 || partes.any { it.isEmpty() }) return null
        val valores = partes.map { it.toLongOrNull() ?: return null }
        // Limites do `inet_aton`, que o JDK segue: as partes iniciais cabem num
        // byte; a última preenche os bytes que faltam.
        if (valores.dropLast(1).any { it > 255 }) return null
        val bytesDaUltima = 5 - valores.size
        if (valores.last() >= (1L shl (8 * bytesDaUltima))) return null
        return InetAddress.getByName(host).address
    }

    private val COMECA_COM_HTTP = Regex("^https?:", RegexOption.IGNORE_CASE)
    private val SO_DIGITOS_E_PONTOS = Regex("^[0-9.]+$")
}
