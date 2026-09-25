package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okio.Buffer
import okio.GzipSink
import okio.buffer

/**
 * `execute_public_request` contra o servidor falso: a cadeia de
 * redirecionamentos, os cabeçalhos presos à origem, os tetos de corpo e a
 * tradução das exceções de rede em notas que o `:core:protocolo` classifica.
 */
class TransportePublicoTest {

    private val servidor = RedeDeTeste.servidorHttps()
    private val outro = RedeDeTeste.servidorHttps()

    @AfterTest
    fun descer() {
        servidor.close()
        outro.close()
    }

    private fun transporte(
        politica: UrlPublica.PoliticaDeRede = RedeDeTeste.politica(),
        agente: AgenteDeColeta = RedeDeTeste.agente,
        prazoDeLeituraMs: Long = 10_000,
    ) = TransportePublico(RedeDeTeste.cliente(prazoDeLeituraMs), Dns.SYSTEM, politica, agente)

    private fun redirecionar(para: String, codigo: Int = 302) =
        MockResponse.Builder().code(codigo).setHeader("Location", para).build()

    private fun executar(
        url: String,
        cabecalhos: Map<String, String> = emptyMap(),
        teto: Int = ColetorHttp.TETO_DO_CORPO_BYTES,
        metodo: MetodoHttp = MetodoHttp.GET,
        transporte: TransportePublico = transporte(),
    ) = transporte.executar(metodo, UrlPublica.validar(url, RedeDeTeste.politica()), cabecalhos, teto)

    private fun falha(bloco: () -> Unit): String = assertFailsWith<IntegridadeDeLinks.Falha>(block = bloco).message!!

    @Test
    fun `cinco saltos sao seguidos e registrados em ordem, com o agente em todos`() {
        val codigos = listOf(301, 302, 303, 307, 308)
        for (i in 1..5) servidor.enqueue(redirecionar(servidor.url("/salto$i").toString(), codigos[i - 1]))
        servidor.enqueue(RedeDeTeste.resposta(200, "fim", "Content-Type" to "text/plain"))
        val resposta = executar(servidor.url("/inicio").toString())
        assertEquals(200, resposta.status)
        assertEquals(servidor.url("/salto5").toString(), resposta.urlFinal)
        assertEquals((1..5).map { servidor.url("/salto$it").toString() }, resposta.redirecionamentos.map { it.url })
        assertEquals(codigos, resposta.redirecionamentos.map { it.status })
        assertEquals("fim", String(resposta.corpo))
        assertEquals(6, servidor.requestCount)
        repeat(6) { assertEquals(RedeDeTeste.agente.userAgent, servidor.takeRequest().headers["User-Agent"]) }
    }

    @Test
    fun `o sexto salto estoura a cadeia`() {
        for (i in 1..6) servidor.enqueue(redirecionar(servidor.url("/salto$i").toString()))
        servidor.enqueue(RedeDeTeste.resposta(200, "nunca lido"))
        assertEquals("redirect chain exceeded 5 hops", falha { executar(servidor.url("/inicio").toString()) })
        assertEquals(6, servidor.requestCount)
    }

    @Test
    fun `laco, Location ausente e Location invalida sao as mensagens do canonico`() {
        servidor.enqueue(redirecionar(servidor.url("/b").toString()))
        servidor.enqueue(redirecionar(servidor.url("/a").toString()))
        assertEquals("redirect loop detected", falha { executar(servidor.url("/a").toString()) })

        servidor.enqueue(MockResponse.Builder().code(302).build())
        assertEquals("HTTP 302 redirect omitted Location", falha { executar(servidor.url("/x").toString()) })

        servidor.enqueue(redirecionar("https://exa mple.com/"))
        assertEquals("redirect Location was invalid", falha { executar(servidor.url("/x").toString()) })
    }

    @Test
    fun `um 304 nao e redirecionamento e chega sem corpo, como HEAD`() {
        servidor.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        val naoModificado = executar(servidor.url("/x").toString())
        assertEquals(304, naoModificado.status)
        assertEquals(0, naoModificado.corpo.size)
        assertEquals("\"v1\"", naoModificado.cabecalhos["etag"])

        servidor.enqueue(RedeDeTeste.resposta(200, "corpo que HEAD nao le", "Content-Type" to "text/plain"))
        val head = executar(servidor.url("/x").toString(), metodo = MetodoHttp.HEAD)
        assertEquals(200, head.status)
        assertEquals(0, head.corpo.size)
        servidor.takeRequest()
        assertEquals("HEAD", servidor.takeRequest().method)
    }

    @Test
    fun `redirecionamento para outra origem e seguido sem cabecalhos da origem e recusado com eles`() {
        servidor.enqueue(redirecionar(outro.url("/fora").toString()))
        outro.enqueue(RedeDeTeste.resposta(200, "outra origem"))
        val resposta = executar(servidor.url("/x").toString())
        assertEquals("outra origem", String(resposta.corpo))
        assertEquals(1, outro.requestCount)

        servidor.enqueue(redirecionar(outro.url("/fora").toString()))
        assertEquals(
            "cross-origin redirect blocked because the request uses origin-bound headers",
            falha { executar(servidor.url("/x").toString(), mapOf("If-None-Match" to "\"v1\"")) },
        )
        assertEquals(1, outro.requestCount)
    }

    @Test
    fun `cabecalhos da origem seguem no salto da mesma origem e a variante polida substitui o agente`() {
        servidor.enqueue(redirecionar(servidor.url("/b").toString()))
        servidor.enqueue(RedeDeTeste.resposta(200, "ok"))
        executar(
            servidor.url("/a").toString(),
            mapOf("If-None-Match" to "\"v1\"", "User-Agent" to RedeDeTeste.agentePolido.userAgentPolido),
            transporte = transporte(agente = RedeDeTeste.agentePolido),
        )
        repeat(2) {
            val pedido = servidor.takeRequest()
            assertEquals("\"v1\"", pedido.headers["If-None-Match"])
            assertEquals(RedeDeTeste.agentePolido.userAgentPolido, pedido.headers["User-Agent"])
        }
    }

    @Test
    fun `Location privada, que resolve para rede privada ou em texto claro nao abre socket`() {
        val tabela = RedeDeTeste.TabelaDns(
            mapOf("example.com" to listOf(RedeDeTeste.PUBLICO), "10.0.0.1.example.com" to listOf("10.0.0.1")),
        )
        val transporte = transporte(RedeDeTeste.politica(ResolvedorPublico(tabela)))

        servidor.enqueue(redirecionar("https://10.0.0.1/"))
        assertEquals(
            "IP privado, reservado ou local bloqueado por seguranca",
            falha { executar(servidor.url("/x").toString(), transporte = transporte) },
        )
        servidor.enqueue(redirecionar("https://10.0.0.1.example.com/"))
        assertEquals(
            "dominio resolve para IP privado/reservado bloqueado por seguranca",
            falha { executar(servidor.url("/x").toString(), transporte = transporte) },
        )
        // Só a conferência prévia consultou o nome; a conexão, que consultaria de novo, não aconteceu.
        assertEquals(listOf("10.0.0.1.example.com"), tabela.consultas)

        servidor.enqueue(redirecionar("http://example.com/"))
        assertEquals(
            "cleartext http:// links are not collected; only https:// is",
            falha { executar(servidor.url("/x").toString(), transporte = transporte) },
        )
        assertEquals(3, servidor.requestCount)
    }

    @Test
    fun `Content-Length acima do teto, Content-Length mentiroso, sem Content-Length e gzip acima do teto sao recusados`() {
        val limite = "HTTP body exceeds the 16 byte evidence limit"
        servidor.enqueue(RedeDeTeste.resposta(200, "curto", "Content-Length" to "9000000"))
        assertEquals(limite, falha { executar(servidor.url("/x").toString(), teto = 16) })

        // Content-Length menor que o corpo: o OkHttp recusa o excesso como erro de protocolo, e nada passa.
        servidor.enqueue(RedeDeTeste.resposta(200, "a".repeat(40), "Content-Length" to "5"))
        assertTrue(falha { executar(servidor.url("/x").toString(), teto = 16) }.startsWith("failed to read HTTP response body: "))

        // Sem Content-Length (o servidor falso fala HTTP/2; `chunkedBody` só faz sentido em HTTP/1.1).
        servidor.enqueue(MockResponse.Builder().body("a".repeat(40)).removeHeader("Content-Length").build())
        assertEquals(limite, falha { executar(servidor.url("/x").toString(), teto = 16) })

        val comprimido = Buffer().also { GzipSink(it).buffer().use { s -> s.writeUtf8("a".repeat(400)) } }
        servidor.enqueue(MockResponse.Builder().setHeader("Content-Encoding", "gzip").body(comprimido).build())
        assertEquals(limite, falha { executar(servidor.url("/x").toString(), teto = 16) })

        // Sem Content-Length, um byte além do teto já é recusado na leitura; no teto exato, o corpo passa inteiro.
        servidor.enqueue(MockResponse.Builder().body("a".repeat(17)).removeHeader("Content-Length").build())
        assertEquals(limite, falha { executar(servidor.url("/x").toString(), teto = 16) })
        servidor.enqueue(MockResponse.Builder().body("a".repeat(16)).removeHeader("Content-Length").build())
        val noTeto = executar(servidor.url("/x").toString(), teto = 16)
        assertEquals(16, noTeto.corpo.size)
        assertNull(noTeto.cabecalhos["content-length"])
    }

    @Test
    fun `transporte cancelado nao abre requisicao nenhuma, nem consulta a politica`() {
        var consultas = 0
        val transporte = transporte(UrlPublica.PoliticaDeRede { consultas++; null })
        servidor.enqueue(RedeDeTeste.resposta(200, "nunca"))
        transporte.cancelarTudo()
        assertTrue(transporte.foiCancelado)
        assertFailsWith<ColetaCancelada> { executar(servidor.url("/x").toString(), transporte = transporte) }
        assertEquals(0, servidor.requestCount)
        assertEquals(0, consultas)
    }

    @Test
    fun `cancelamento durante a validacao nao abre a chamada`() {
        // A política espera uma consulta de nome que não é cancelada; o cancelamento chega no meio dela.
        lateinit var transporte: TransportePublico
        transporte = transporte(UrlPublica.PoliticaDeRede { transporte.cancelarTudo(); null })
        servidor.enqueue(RedeDeTeste.resposta(200, "nunca"))
        assertFailsWith<ColetaCancelada> { executar(servidor.url("/x").toString(), transporte = transporte) }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `o cliente e guardado e resolve nomes pelo DNS injetado`() {
        val cliente = transporte().cliente
        assertEquals(java.net.Proxy.NO_PROXY, cliente.proxy)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, cliente.cookieJar)
        assertFalse(cliente.followRedirects)
        assertFalse(cliente.followSslRedirects)
        assertFalse(cliente.retryOnConnectionFailure)
        assertEquals(30_000, cliente.callTimeoutMillis)
        assertTrue(cliente.interceptors.isEmpty() && cliente.networkInterceptors.isEmpty())

        val tabela = RedeDeTeste.TabelaDns(mapOf("fonte.example" to listOf("127.0.0.1")))
        val injetado = TransportePublico(RedeDeTeste.cliente(), tabela, UrlPublica.PoliticaDeRede { null }, RedeDeTeste.agente)
        servidor.enqueue(RedeDeTeste.resposta(200, "resolvido"))
        val url = servidor.url("/x").newBuilder().host("fonte.example").build()
        val resposta = injetado.executar(MetodoHttp.GET, url, emptyMap(), 1024)
        assertEquals("resolvido", String(resposta.corpo))
        assertEquals(listOf("fonte.example"), tabela.consultas)
    }

    @Test
    fun `so os sete cabecalhos seguros sao guardados, saneados e em caixa baixa`() {
        servidor.enqueue(
            RedeDeTeste.resposta(
                200, "x", "Content-Type" to "text/html; charset=utf-8", "ETag" to "\"a\tb\"", "Set-Cookie" to "s=1",
                "X-Secret" to "no", "Last-Modified" to "Wed, 23 Sep 2026 12:00:00 GMT",
            ),
        )
        val cabecalhos = executar(servidor.url("/x").toString()).cabecalhos
        assertEquals(setOf("content-type", "content-length", "etag", "last-modified"), cabecalhos.keys)
        assertEquals("\"a b\"", cabecalhos["etag"])
        assertEquals("text/html; charset=utf-8", cabecalhos["content-type"])
    }

    @Test
    fun `prazo estourado, TLS recusado e as demais excecoes viram as notas que o protocolo classifica`() {
        servidor.enqueue(MockResponse.Builder().body("lento").bodyDelay(3, TimeUnit.SECONDS).build())
        val nota = falha { executar(servidor.url("/x").toString(), transporte = transporte(prazoDeLeituraMs = 250)) }
        assertContains(nota, "timed out")

        // O cliente de produção não confia no certificado de mentira: falha no aperto de mão.
        servidor.enqueue(RedeDeTeste.resposta(200, "nunca"))
        val limpo = TransportePublico(TransportePublico.clienteLimpo(), Dns.SYSTEM, RedeDeTeste.politica(), RedeDeTeste.agente)
        val porIp = servidor.url("/x").newBuilder().host("127.0.0.1").build().toString()
        assertContains(falha { executar(porIp, transporte = limpo) }, "TLS handshake failed")
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), TransportePublico.clienteLimpo().connectionSpecs)
        assertTrue(TransportePublico.clienteLimpo().interceptors.isEmpty())

        val polido = transporte(agente = RedeDeTeste.agentePolido)
        assertEquals(
            "HTTP request failed: DNS resolution failed: nope",
            polido.descrever(UnknownHostException("nope")),
        )
        assertEquals("HTTP request failed: TLS handshake failed (bad cert)", polido.descrever(SSLHandshakeException("bad cert")))
        assertEquals("HTTP request failed: timed out after 30 s (timeout)", polido.descrever(SocketTimeoutException("timeout")))
        assertEquals("HTTP request failed: ConnectException: refused", polido.descrever(ConnectException("refused")))
        assertFalse("leitor@example.com" in polido.descrever(ConnectException("mailto:leitor@example.com refused")))
    }
}
