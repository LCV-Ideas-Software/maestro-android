package dev.lcv.maestro.provedores

import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns

/**
 * As duas faces do resolvedor (revisão cruzada de 25/09/2026): `resolver`
 * devolve tudo, para a regra pública recusar com o motivo próprio; `lookup`
 * falha fechado antes do socket. E o DoH de produção: nada sem sufixo
 * público, nenhum redirecionamento seguido.
 */
class ResolvedorPublicoTest {

    private fun tabela(vararg entradas: Pair<String, List<String>>) = RedeDeTeste.TabelaDns(mapOf(*entradas))

    private fun bytes(vararg ips: String) = ips.map { InetAddress.getByName(it).address }

    @Test
    fun `resolver devolve todos os enderecos e lookup recusa a lista inteira se um e privado`() {
        val publico = ResolvedorPublico(tabela("example.com" to listOf(RedeDeTeste.PUBLICO)))
        assertEquals(bytes(RedeDeTeste.PUBLICO).map { it.toList() }, publico.resolver("example.com")!!.map { it.toList() })
        assertEquals(listOf(RedeDeTeste.PUBLICO), publico.lookup("example.com").map { it.hostAddress })

        val privado = ResolvedorPublico(tabela("example.com" to listOf("10.0.0.1")))
        assertEquals(bytes("10.0.0.1").map { it.toList() }, privado.resolver("example.com")!!.map { it.toList() })
        val erro = assertFailsWith<UnknownHostException> { privado.lookup("example.com") }
        assertContains(erro.message!!, "resolve")

        val misto = ResolvedorPublico(tabela("example.com" to listOf(RedeDeTeste.PUBLICO, "192.168.0.7")))
        assertEquals(2, misto.resolver("example.com")!!.size)
        assertContains(assertFailsWith<UnknownHostException> { misto.lookup("example.com") }.message!!, "private/reserved")
    }

    @Test
    fun `falha de resolucao e resposta vazia sao nulo numa face e excecao na outra`() {
        val falha = ResolvedorPublico(tabela())
        assertNull(falha.resolver("example.com"))
        assertContains(assertFailsWith<UnknownHostException> { falha.lookup("example.com") }.message!!, "could not be resolved")

        val vazio = ResolvedorPublico(tabela("example.com" to emptyList()))
        assertNull(vazio.resolver("example.com"))
        assertContains(assertFailsWith<UnknownHostException> { vazio.lookup("example.com") }.message!!, "did not resolve")
    }

    @Test
    fun `host literal nunca vai ao delegado`() {
        val delegado = tabela()
        val resolvedor = ResolvedorPublico(delegado)
        assertEquals(bytes("127.0.0.1").map { it.toList() }, resolvedor.resolver("127.1")!!.map { it.toList() })
        assertEquals(listOf("93.184.216.34"), resolvedor.lookup("93.184.216.34").map { it.hostAddress })
        assertEquals(16, resolvedor.resolver("::1")!!.single().size)
        assertTrue(delegado.consultas.isEmpty(), delegado.consultas.toString())
        // A sequência inválida é nome, e vai ao delegado.
        assertNull(resolvedor.resolver("999.999.999.999"))
        assertEquals(listOf("999.999.999.999"), delegado.consultas)
    }

    @Test
    fun `rebinding entre a conferencia previa e a conexao e recusado`() {
        val respostas = ArrayDeque(listOf(listOf(RedeDeTeste.PUBLICO), listOf("10.0.0.1")))
        val delegado = Dns { respostas.removeFirst().map { InetAddress.getByName(it) } }
        val resolvedor = ResolvedorPublico(delegado)
        assertEquals(1, resolvedor.resolver("example.com")!!.size)
        assertContains(assertFailsWith<UnknownHostException> { resolvedor.lookup("example.com") }.message!!, "resolve")
    }

    @Test
    fun `cancelar interrompe a consulta DoH em curso`() {
        val servidor = RedeDeTeste.servidorHttps()
        try {
            repeat(2) { servidor.enqueue(MockResponse.Builder().code(200).headersDelay(20, TimeUnit.SECONDS).build()) }
            val confiante = ResolvedorPublico.clienteDeArranque(null).newBuilder()
                .sslSocketFactory(RedeDeTeste.cliente().sslSocketFactory, RedeDeTeste.cliente().x509TrustManager!!)
                .build()
            val doh = ResolvedorPublico.sobreHttps(
                servidor.url("/dns-query"), listOf(InetAddress.getByName("127.0.0.1")), confiante,
            )
            var erro: Throwable? = null
            val inicio = System.nanoTime()
            val trabalho = thread {
                try {
                    doh.lookup("example.com")
                } catch (e: Throwable) {
                    erro = e
                }
            }
            Thread.sleep(500)
            doh.cancelar()
            trabalho.join(10_000)
            assertTrue((System.nanoTime() - inicio) < 10_000_000_000L)
            assertTrue(erro is UnknownHostException, erro.toString())
            // O resolvedor não fecha: a consulta seguinte parte normalmente (e falha só por falta de resposta).
            repeat(2) { servidor.enqueue(MockResponse.Builder().code(500).build()) }
            assertNull(doh.resolver("example.org"))
        } finally {
            servidor.close()
        }
    }

    @Test
    fun `o DoH de producao recusa host sem sufixo publico antes de qualquer requisicao`() {
        val resolvedor = ResolvedorPublico.dnsDoGoogle()
        assertNull(resolvedor.resolver("intranet"))
        assertContains(assertFailsWith<UnknownHostException> { resolvedor.lookup("intranet") }.message!!, "resolve")
    }

    @Test
    fun `o cliente de arranque e limpo e nao segue redirecionamento`() {
        val arranque = ResolvedorPublico.clienteDeArranque(null)
        assertEquals(Proxy.NO_PROXY, arranque.proxy)
        assertEquals(CookieJar.NO_COOKIES, arranque.cookieJar)
        assertEquals(false, arranque.followRedirects)
        assertEquals(false, arranque.followSslRedirects)
        assertEquals(false, arranque.retryOnConnectionFailure)
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), arranque.connectionSpecs)
        assertTrue(arranque.interceptors.isEmpty() && arranque.networkInterceptors.isEmpty())

        val servidor = RedeDeTeste.servidorHttps()
        try {
            repeat(2) {
                servidor.enqueue(MockResponse.Builder().code(302).setHeader("Location", servidor.url("/elsewhere")).build())
            }
            val confiante = arranque.newBuilder()
                .sslSocketFactory(RedeDeTeste.cliente().sslSocketFactory, RedeDeTeste.cliente().x509TrustManager!!)
                .build()
            val doh = ResolvedorPublico.sobreHttps(
                servidor.url("/dns-query"), listOf(InetAddress.getByName("127.0.0.1")), confiante,
            )
            assertNull(doh.resolver("example.com"))
            assertContains(assertFailsWith<UnknownHostException> { doh.lookup("example.com") }.message!!, "resolve")
            // Uma consulta A e uma AAAA, nenhuma seguindo o redirecionamento.
            val alvos = (0 until servidor.requestCount).map { servidor.takeRequest().target }
            assertEquals(2, alvos.size, alvos.toString())
            assertTrue(alvos.all { it.startsWith("/dns-query?dns=") }, alvos.toString())
        } finally {
            servidor.close()
        }
    }
}
