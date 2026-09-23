package dev.lcv.maestro.provedores

import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient

/**
 * A mecânica de rede, portada de `provider_retry.rs`: duas tentativas no
 * máximo, uma nova tentativa depois de erro de rede, espera de `Retry-After`
 * só no 429, nada de nova tentativa em outro status, e cancelamento que não
 * vira nova tentativa.
 *
 * A espera é injetada e registrada: nenhum teste espera 30 s de verdade, e cada
 * um confere quanto o cliente **teria** esperado. Quantas requisições chegaram
 * ao servidor é conferido em todos, porque cada tentativa é um POST pago.
 */
class ClienteDeProvedoresTest {

    private val servidor = MockWebServer()
    private val esperas = mutableListOf<Long>()
    private var chave: String? = "chave-de-teste"
    private val agora = Instant.parse("2026-09-23T12:00:00Z")

    @BeforeTest fun subir() = servidor.start()

    @AfterTest fun descer() = servidor.close()

    private fun cliente() = ClienteDeProvedores(
        OkHttpClient(),
        { chave },
        { servidor.url("/${it.agente}").toString() },
        { esperas += it },
        { agora },
    )

    private val pedido = Pedido(sistema = "Papel.", prompt = "Turno.")

    private val concluida =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Ok."}]}],"usage":{"input_tokens":1,"output_tokens":1}}"""

    private fun ok() = MockResponse.Builder().body(concluida).build()

    private fun status(codigo: Int, vararg cabecalhos: Pair<String, String>, corpo: String = "") =
        MockResponse.Builder().code(codigo).apply { cabecalhos.forEach { (k, v) -> addHeader(k, v) } }.body(corpo).build()

    private fun chamar(prazo: kotlin.time.Duration = ClienteDeProvedores.PRAZO_POR_CHAMADA) =
        runBlocking { cliente().chamar(Provedor.CODEX, pedido, prazo) }

    // -- 429 -----------------------------------------------------------------------

    @Test
    fun `429 com Retry-After em segundos espera e tenta de novo uma vez`() {
        servidor.enqueue(status(429, "Retry-After" to "7"))
        servidor.enqueue(ok())

        assertIs<Resultado.Concluida>(chamar())
        assertEquals(listOf(7_000L), esperas)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `429 sem Retry-After espera 30 segundos`() {
        servidor.enqueue(status(429))
        servidor.enqueue(ok())

        assertIs<Resultado.Concluida>(chamar())
        assertEquals(listOf(30_000L), esperas)
    }

    @Test
    fun `Retry-After longo e limitado a 120 segundos`() {
        servidor.enqueue(status(429, "Retry-After" to "500"))
        servidor.enqueue(ok())

        chamar()
        assertEquals(listOf(120_000L), esperas)
    }

    @Test
    fun `Retry-After em data HTTP conta a partir de agora`() {
        servidor.enqueue(status(429, "Retry-After" to "Wed, 23 Sep 2026 12:00:45 GMT"))
        servidor.enqueue(ok())

        chamar()
        assertEquals(listOf(45_000L), esperas)
    }

    @Test
    fun `Retry-After ja vencido nao espera negativo`() {
        servidor.enqueue(status(429, "Retry-After" to "Wed, 23 Sep 2026 11:00:00 GMT"))
        servidor.enqueue(ok())

        chamar()
        assertEquals(listOf(0L), esperas)
    }

    @Test
    fun `segundo 429 volta como falha, sem terceira tentativa`() {
        servidor.enqueue(status(429, "Retry-After" to "1"))
        servidor.enqueue(status(429, "Retry-After" to "1", corpo = """{"error":{"message":"slow down"}}"""))

        val resultado = assertIs<Resultado.FalhaHttp>(chamar())
        assertEquals(429, resultado.status)
        assertEquals("PROVIDER_ERROR_HTTP_429_RATE_LIMIT: slow down", resultado.mensagem)
        assertEquals(2, servidor.requestCount)
        assertEquals(listOf(1_000L), esperas)
    }

    // -- Outros status -------------------------------------------------------------

    @Test
    fun `erro de servidor nao tenta de novo`() {
        servidor.enqueue(status(503, corpo = """{"error":{"message":"overloaded"}}"""))

        val resultado = assertIs<Resultado.FalhaHttp>(chamar())
        assertEquals("PROVIDER_ERROR_HTTP_503_SERVER: overloaded", resultado.mensagem)
        assertEquals(1, servidor.requestCount)
        assertTrue(esperas.isEmpty())
    }

    @Test
    fun `classe de cada status, como no canonico`() {
        for ((codigo, classe) in listOf(
            400 to "BAD_REQUEST", 401 to "AUTH", 403 to "PERMISSION", 404 to "NOT_FOUND",
            408 to "TIMEOUT", 409 to "CONFLICT", 529 to "SERVER", 418 to "OTHER",
        )) {
            servidor.enqueue(status(codigo))
            val resultado = assertIs<Resultado.FalhaHttp>(chamar())
            assertEquals("PROVIDER_ERROR_HTTP_${codigo}_$classe: sem detalhe na resposta", resultado.mensagem)
        }
    }

    @Test
    fun `mensagem do provedor e saneada antes de ir ao jornal`() {
        servidor.enqueue(
            status(
                401,
                corpo = """{"error":{"message":"Incorrect API key provided: sk-ant-abcdefghijklmnop\nlinha forjada"}}""",
            ),
        )

        val mensagem = assertIs<Resultado.FalhaHttp>(chamar()).mensagem
        assertContains(mensagem, "<redacted>")
        assertFalse(mensagem.contains("sk-ant-abc"))
        assertFalse(mensagem.contains('\n'), "quebra de linha forjaria uma linha no registro")
    }

    @Test
    fun `mensagem tirada dos campos que o canonico procura`() {
        servidor.enqueue(status(400, corpo = """{"error":{"code":"invalid_model"}}"""))
        assertEquals("PROVIDER_ERROR_HTTP_400_BAD_REQUEST: invalid_model", assertIs<Resultado.FalhaHttp>(chamar()).mensagem)

        servidor.enqueue(status(400, corpo = """{"message":"bad input"}"""))
        assertEquals("PROVIDER_ERROR_HTTP_400_BAD_REQUEST: bad input", assertIs<Resultado.FalhaHttp>(chamar()).mensagem)

        servidor.enqueue(status(400, corpo = "texto puro"))
        assertEquals("PROVIDER_ERROR_HTTP_400_BAD_REQUEST: texto puro", assertIs<Resultado.FalhaHttp>(chamar()).mensagem)
    }

    @Test
    fun `resposta 2xx que nao e JSON e resposta invalida`() {
        servidor.enqueue(MockResponse.Builder().body("<html>proxy</html>").build())

        assertIs<Resultado.RespostaInvalida>(chamar())
    }

    // -- Rede --------------------------------------------------------------------

    private fun conexaoFechada() = MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()

    @Test
    fun `erro de rede tenta de novo uma vez, depois de um segundo e meio`() {
        servidor.enqueue(conexaoFechada())
        servidor.enqueue(ok())

        assertIs<Resultado.Concluida>(chamar())
        assertEquals(listOf(1_500L), esperas)
    }

    @Test
    fun `erro de rede duas vezes volta como falha, sem terceira tentativa`() {
        repeat(4) { servidor.enqueue(conexaoFechada()) }

        assertIs<Resultado.FalhaDeRede>(chamar())
        assertEquals(2, servidor.requestCount)
        assertEquals(listOf(1_500L), esperas)
    }

    @Test
    fun `o OkHttp nao repete o 408 escondido`() {
        // Com `retryOnConnectionFailure` ligado, que é o padrão, o OkHttp
        // repete sozinho o HTTP 408: um POST pago fora das duas tentativas do
        // canônico, que só repete o 429.
        servidor.enqueue(status(408))
        servidor.enqueue(ok())

        assertEquals(408, assertIs<Resultado.FalhaHttp>(chamar()).status)
        assertEquals(1, servidor.requestCount)
    }

    @Test
    fun `prazo estourado e erro de rede, com uma nova tentativa`() {
        repeat(2) { servidor.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body(concluida).build()) }

        val resultado = assertIs<Resultado.FalhaDeRede>(chamar(prazo = 300.milliseconds))
        assertContains(resultado.mensagem, "PROVIDER_NETWORK_ERROR")
        assertEquals(2, servidor.requestCount)
        assertEquals(listOf(1_500L), esperas)
    }

    // -- Chave e cancelamento ------------------------------------------------------

    @Test
    fun `sem chave nada e enviado`() {
        chave = null
        assertEquals(Resultado.SemChave, chamar())
        chave = "   "
        assertEquals(Resultado.SemChave, chamar())
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `chave colada com caractere de controle nao vaza nem derruba a chamada`() {
        // Chave colada costuma trazer quebra de linha no fim. O OkHttp recusa
        // esse caractere no cabeçalho, e a mensagem dele pode citar o valor.
        for (provedor in listOf(Provedor.CLAUDE, Provedor.GEMINI, Provedor.CODEX)) {
            chave = "chave\u0007de-teste"
            val resultado = runBlocking { cliente().chamar(provedor, pedido) }
            assertEquals(Resultado.ChaveInvalida, resultado, "$provedor")
            assertFalse(resultado.toString().contains("chave\u0007de-teste"))
        }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `espaco nas pontas da chave colada e descartado`() {
        chave = "  chave-de-teste\n"
        servidor.enqueue(ok())

        assertIs<Resultado.Concluida>(chamar())
        assertEquals("Bearer chave-de-teste", servidor.takeRequest().headers["Authorization"])
    }

    @Test
    fun `falha no meio do corpo da resposta nao e repetida`() {
        // O provedor já respondeu 200 e provavelmente já cobrou: repetir
        // pagaria duas vezes.
        servidor.enqueue(
            MockResponse.Builder().body(concluida).onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        servidor.enqueue(ok())

        assertIs<Resultado.FalhaDeRede>(chamar())
        assertEquals(1, servidor.requestCount)
        assertTrue(esperas.isEmpty())
    }

    @Test
    fun `prazo estourado no meio do corpo tambem e falha sem repeticao`() {
        servidor.enqueue(MockResponse.Builder().bodyDelay(2, TimeUnit.SECONDS).body(concluida).build())
        servidor.enqueue(ok())

        assertIs<Resultado.FalhaDeRede>(chamar(prazo = 300.milliseconds))
        assertEquals(1, servidor.requestCount)
        assertTrue(esperas.isEmpty())
    }

    @Test
    fun `cancelamento durante a chamada nao vira nova tentativa`() {
        servidor.enqueue(MockResponse.Builder().headersDelay(10, TimeUnit.SECONDS).body(concluida).build())
        servidor.enqueue(ok())

        runBlocking {
            val chamada = async(Dispatchers.IO) { cliente().chamar(Provedor.CODEX, pedido) }
            servidor.takeRequest(5, TimeUnit.SECONDS)
            chamada.cancel()
            assertFailsWith<CancellationException> { chamada.await() }
        }
        Thread.sleep(300)

        assertEquals(1, servidor.requestCount)
        assertTrue(esperas.isEmpty())
    }
}
