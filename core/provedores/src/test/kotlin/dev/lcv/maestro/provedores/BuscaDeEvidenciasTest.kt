package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.ModoDeAcesso
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import okhttp3.Dns

/**
 * `search_web_evidence_inner` contra o servidor falso: a requisição a cada
 * conector — o e-mail do usuário só no Crossref —, os registros por item, o
 * que é pulado, e as mensagens de erro do canônico.
 */
class BuscaDeEvidenciasTest {

    private val servidor = RedeDeTeste.servidorHttps()

    @AfterTest
    fun descer() = servidor.close()

    private fun busca(agente: AgenteDeColeta = RedeDeTeste.agentePolido) = BuscaDeEvidencias(
        RedeDeTeste.cliente(),
        Dns.SYSTEM,
        RedeDeTeste.politica(RedeDeTeste.resolvedor("10.0.0.1.example.com" to listOf("10.0.0.1"))),
        agente,
        { RedeDeTeste.agora },
    ) { servidor.url("/${it.id}/works").toString() }

    private val crossref = """
        {"message":{"items":[
          {"title":["Primeiro título"," outro"],"URL":"https://example.com/w1","publisher":"Editora X"},
          {"title":["Privado"],"URL":"https://10.0.0.1.example.com/w2"},
          {"title":"Texto claro","URL":"http://example.com/w3"},
          {"title":123,"URL":"https://example.com/w4#frag"},
          {"title":["Sem URL"]},
          {"title":["Quinto"],"URL":"https://example.com/w5"}
        ]}}
    """.trimIndent()

    @Test
    fun `Crossref recebe consulta, limite, mailto e o agente polido, e cada item vira um registro`() {
        servidor.enqueue(RedeDeTeste.resposta(200, crossref, "Content-Type" to "application/json"))
        val registros = busca().buscar("  Título da obra  ", "crossref", 3)
        val pedido = servidor.takeRequest()
        assertEquals("Título da obra", pedido.url.queryParameter("query.bibliographic"))
        assertEquals("3", pedido.url.queryParameter("rows"))
        assertEquals("leitor@example.com", pedido.url.queryParameter("mailto"))
        assertEquals(RedeDeTeste.agentePolido.userAgentPolido, pedido.headers["User-Agent"])

        // Dos três primeiros itens só o primeiro passa: privado e texto claro são pulados.
        assertEquals(1, registros.size)
        val primeiro = registros.single()
        assertEquals(RedeDeTeste.sha("official_api|crossref|Título da obra|https://example.com/w1"), primeiro.id)
        assertEquals("https://example.com/w1", primeiro.url)
        assertEquals("https://example.com/w1", primeiro.urlFinal)
        assertEquals("Primeiro título", primeiro.titulo)
        assertEquals(EstadoDaEvidencia.PRONTA, primeiro.estado)
        assertEquals(ModoDeAcesso.API_OFICIAL, primeiro.modoDeAcesso)
        assertEquals(200, primeiro.status)
        assertEquals("application/json", primeiro.tipoDeConteudo)
        assertEquals(EstadoDoCache.FRESCO, primeiro.estadoDoCache)
        assertEquals("crossref", primeiro.provedor)
        assertEquals("Título da obra", primeiro.consulta)
        assertEquals("2026-09-25T12:00:00+00:00", primeiro.coletadaEm)
        assertEquals("2026-10-25T12:00:00+00:00", primeiro.expiraEm)
        assertEquals(
            listOf("Metadata returned by the Crossref official/configured API; target page was not fetched", "Editora X"),
            primeiro.notas,
        )
        val item = Json.LEITOR.writeValueAsBytes(Json.LEITOR.readTree(crossref).at("/message/items/0"))
        assertEquals(RedeDeTeste.sha(String(item)), primeiro.sha256)
        assertEquals(item.size.toLong(), primeiro.bytes)
        assertNull(primeiro.comandoCurl)
    }

    @Test
    fun `limite acima de 20 vira 20, titulo numerico vira texto, fragmento cai e item sem URL e pulado`() {
        servidor.enqueue(RedeDeTeste.resposta(200, crossref, "Content-Type" to "application/json"))
        val registros = busca().buscar("q", "crossref", 50)
        assertEquals("20", servidor.takeRequest().url.queryParameter("rows"))
        assertEquals(listOf("https://example.com/w1", "https://example.com/w4", "https://example.com/w5"), registros.map { it.url })
        assertEquals("123", registros[1].titulo)
        assertEquals(listOf("Metadata returned by the Crossref official/configured API; target page was not fetched"), registros[1].notas)
        servidor.enqueue(RedeDeTeste.resposta(200, crossref, "Content-Type" to "application/json"))
        busca().buscar("q", "crossref", 0)
        assertEquals("1", servidor.takeRequest().url.queryParameter("rows"))
    }

    @Test
    fun `OpenAlex recebe search e per-page, o agente base e nenhum e-mail`() {
        val corpo = """{"results":[{"display_name":"Obra","id":"https://openalex.org/W1","doi":"https://doi.org/10.1/x"}]}"""
        servidor.enqueue(RedeDeTeste.resposta(200, corpo, "Content-Type" to "application/json"))
        val registros = busca().buscar("obra", "openalex", 5)
        val pedido = servidor.takeRequest()
        assertEquals("obra", pedido.url.queryParameter("search"))
        assertEquals("5", pedido.url.queryParameter("per-page"))
        assertNull(pedido.url.queryParameter("mailto"))
        assertEquals(RedeDeTeste.agente.userAgent, pedido.headers["User-Agent"])
        val registro = registros.single()
        assertEquals("https://openalex.org/W1", registro.url)
        assertEquals("Obra", registro.titulo)
        assertEquals("openalex", registro.provedor)
        assertEquals(
            listOf("Metadata returned by the OpenAlex official/configured API; target page was not fetched", "https://doi.org/10.1/x"),
            registro.notas,
        )
        // Sem e-mail, o Crossref também recebe o agente base e nenhum mailto.
        servidor.enqueue(RedeDeTeste.resposta(200, crossref, "Content-Type" to "application/json"))
        busca(RedeDeTeste.agente).buscar("q", "crossref", 1)
        val semEmail = servidor.takeRequest()
        assertNull(semEmail.url.queryParameter("mailto"))
        assertEquals(RedeDeTeste.agente.userAgent, semEmail.headers["User-Agent"])
    }

    @Test
    fun `cancelarTudo fecha a busca antes de qualquer consulta de nome`() {
        val busca = busca()
        busca.cancelarTudo()
        assertFailsWith<ColetaCancelada> { busca.buscar("q", "crossref", 1) }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `erros de entrada e de resposta com as mensagens do canonico`() {
        fun falha(bloco: () -> Unit) = assertFailsWith<IntegridadeDeLinks.Falha>(block = bloco).message
        assertEquals("web evidence search query cannot be empty", falha { busca().buscar("  \t ", "crossref", 1) })
        assertEquals("invalid web evidence search provider", falha { busca().buscar("q", "", 1) })
        assertEquals("invalid web evidence search provider", falha { busca().buscar("q", "a.b", 1) })
        assertEquals(
            "unknown web evidence search provider 'bing'; available built-ins are crossref and openalex",
            falha { busca().buscar("q", "bing", 1) },
        )
        assertEquals(0, servidor.requestCount)

        servidor.enqueue(RedeDeTeste.resposta(503, "{}", "Content-Type" to "application/json"))
        assertEquals("search provider 'crossref' returned HTTP 503", falha { busca().buscar("q", "crossref", 1) })
        servidor.enqueue(RedeDeTeste.resposta(200, "", "Content-Type" to "application/json"))
        assertEquals(
            "search provider 'crossref' returned invalid JSON: empty response body",
            falha { busca().buscar("q", "crossref", 1) },
        )
        servidor.enqueue(RedeDeTeste.resposta(200, "nao e json", "Content-Type" to "application/json"))
        val invalido = falha { busca().buscar("q", "crossref", 1) }!!
        assertEquals(true, invalido.startsWith("search provider 'crossref' returned invalid JSON: "))
        servidor.enqueue(RedeDeTeste.resposta(200, """{"message":{"items":{}}}""", "Content-Type" to "application/json"))
        assertEquals(
            "search provider 'crossref' response did not contain array 'message.items'",
            falha { busca().buscar("q", "crossref", 1) },
        )
        servidor.enqueue(RedeDeTeste.resposta(200, """{"resultados":[]}""", "Content-Type" to "application/json"))
        assertEquals(
            "search provider 'openalex' response did not contain array 'results'",
            falha { busca().buscar("q", "openalex", 1) },
        )
    }
}
