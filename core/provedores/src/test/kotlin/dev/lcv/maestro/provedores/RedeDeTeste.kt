package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.RedePublica
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * O que os testes de rede compartilham: o servidor falso em HTTPS, com um
 * certificado de mentira do `okhttp-tls`; o cliente que confia nele; a regra
 * pública real com uma única exceção, `localhost`, onde o servidor falso
 * mora; e um DNS de tabela. Nenhum teste toca a rede de verdade.
 */
internal object RedeDeTeste {

    val certificado: HeldCertificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("::1")
        .addSubjectAlternativeName("fonte.example")
        .build()

    private val doServidor = HandshakeCertificates.Builder().heldCertificate(certificado).build()
    private val doCliente = HandshakeCertificates.Builder().addTrustedCertificate(certificado.certificate).build()

    fun servidorHttps(): MockWebServer = MockWebServer().apply {
        useHttps(doServidor.sslSocketFactory())
        start()
    }

    /** Cliente que confia no certificado do servidor falso. */
    fun cliente(prazoDeLeituraMs: Long = 10_000): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(doCliente.sslSocketFactory(), doCliente.trustManager)
        .readTimeout(prazoDeLeituraMs, TimeUnit.MILLISECONDS)
        .build()

    val agente = AgenteDeColeta("1.2.3", null)
    val agentePolido = AgenteDeColeta("1.2.3", "leitor@example.com")
    val agora: Instant = Instant.parse("2026-09-25T12:00:00Z")

    const val PUBLICO = "93.184.216.34"

    /** DNS de tabela: host fora dela é falha de resolução. Registra cada consulta. */
    class TabelaDns(private val tabela: Map<String, List<String>>) : Dns {
        val consultas = mutableListOf<String>()
        override fun lookup(hostname: String): List<InetAddress> {
            consultas += hostname
            val ips = tabela[hostname] ?: throw UnknownHostException("sem entrada para $hostname")
            return ips.map { InetAddress.getByName(it) }
        }
    }

    fun resolvedor(vararg entradas: Pair<String, List<String>>): ResolvedorPublico =
        ResolvedorPublico(TabelaDns(mapOf("example.com" to listOf(PUBLICO), *entradas)))

    /** A regra pública real, exceto para o servidor falso. */
    fun politica(resolvedor: ResolvedorPublico = resolvedor()): UrlPublica.PoliticaDeRede =
        UrlPublica.PoliticaDeRede { url ->
            val host = url.toHttpUrlOrNull()?.host
            if (host == "localhost" || host == "127.0.0.1") {
                null
            } else {
                RedePublica.motivoDeRecusa(url, AnalisadorDeUrlOkHttp, resolvedor)
            }
        }

    /** A regra pública real, sem exceção: nada local passa. */
    fun politicaReal(resolvedor: ResolvedorPublico = resolvedor()): UrlPublica.PoliticaDeRede =
        UrlPublica.PoliticaDeRede { RedePublica.motivoDeRecusa(it, AnalisadorDeUrlOkHttp, resolvedor) }

    fun resposta(codigo: Int = 200, corpo: String = "", vararg cabecalhos: Pair<String, String>): MockResponse =
        MockResponse.Builder().code(codigo).body(corpo).apply { cabecalhos.forEach { (k, v) -> setHeader(k, v) } }.build()

    fun sha(valor: String): String = FormatoDoRegistro.sha256(valor)
}
