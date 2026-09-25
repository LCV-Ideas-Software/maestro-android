package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import okhttp3.Dns

/**
 * `fetch_web_evidence_inner` de ponta a ponta: os dois ids, o bloqueio
 * antes de qualquer requisição, o robots, a classificação da resposta, os
 * campos do registro (`web_evidence.rs:1272-1315`), o corpo só no registro
 * pronto, e o armazém — reaproveitamento, revalidação por 304, `criadaEm`
 * preservado.
 */
class ColetorHttpTest {

    private val servidor = RedeDeTeste.servidorHttps()
    private val agora = RedeDeTeste.agora

    @AfterTest
    fun descer() = servidor.close()

    private class ArmazemEmMemoria : ColetorHttp.ArmazemDeEvidencias {
        val guardadas = LinkedHashMap<String, ColetorHttp.Coleta>()
        override fun existente(id: String) = guardadas[id]
        override fun guardar(coleta: ColetorHttp.Coleta) {
            guardadas[coleta.registro.id] = coleta
        }
    }

    private fun coletor(
        politica: UrlPublica.PoliticaDeRede = RedeDeTeste.politica(),
        armazem: ColetorHttp.ArmazemDeEvidencias? = null,
        relogio: () -> Instant = { agora },
    ) = ColetorHttp(RedeDeTeste.cliente(), Dns.SYSTEM, politica, RedeDeTeste.agente, armazem, relogio)

    private fun robots(codigo: Int = 404, corpo: String = "") {
        servidor.enqueue(RedeDeTeste.resposta(codigo, corpo, "Content-Type" to "text/plain"))
    }

    private fun pagina(codigo: Int = 200, corpo: String = "<html><title>Fonte</title></html>", tipo: String = "text/html") {
        servidor.enqueue(RedeDeTeste.resposta(codigo, corpo, "Content-Type" to tipo))
    }

    private fun url(caminho: String) = servidor.url(caminho).toString()

    @Test
    fun `URL recusada pela regra fica bloqueada com o id preliminar e sem requisicao`() {
        val coletor = coletor(RedeDeTeste.politicaReal())
        for (bruta in listOf("http://localhost:${servidor.port}/x", "http://127.0.0.1/test")) {
            val coleta = coletor.coletarComConteudo(bruta)
            val registro = coleta.registro
            assertEquals(EstadoDaEvidencia.BLOQUEADA, registro.estado)
            assertEquals(RedeDeTeste.sha("http_fetch|GET|$bruta"), registro.id)
            assertEquals(bruta, registro.url)
            val esperado = if ("localhost" in bruta) {
                "endereco local bloqueado por seguranca"
            } else {
                "IP privado, reservado ou local bloqueado por seguranca"
            }
            assertEquals(listOf(esperado), registro.notas)
            assertEquals(ColetorHttp.VERSAO_DO_ESQUEMA, registro.versaoDoEsquema)
            assertEquals(ModoDeAcesso.COLETA_HTTP, registro.modoDeAcesso)
            assertEquals(MetodoHttp.GET, registro.metodo)
            assertEquals(EstadoDoCache.AUSENTE, registro.estadoDoCache)
            assertEquals(EstadoDoRobots.NAO_SE_APLICA, registro.estadoDoRobots)
            assertNull(registro.status)
            assertNull(coleta.corpo)
            assertEquals("2026-09-25T12:00:00+00:00", registro.criadaEm)
            assertEquals("2026-09-25T12:00:00+00:00", registro.atualizadaEm)
        }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `texto claro, credenciais, chave na query e URL longa demais sao bloqueados antes da rede`() {
        val coletor = coletor()
        fun nota(url: String) = coletor.coletar(url).also { assertEquals(EstadoDaEvidencia.BLOQUEADA, it.estado) }.notas.single()
        assertEquals("cleartext http:// links are not collected; only https:// is", nota("http://example.com/a"))
        assertEquals("URLs with embedded credentials are blocked", nota("https://user:secret@example.com/"))
        assertEquals("URLs with embedded credentials are blocked", nota("https://:@example.com/"))
        assertEquals(
            "URLs with credential-like query parameters are blocked; use an environment-backed connector or operator capture",
            nota("https://example.com/?access_token=secret"),
        )
        assertEquals(
            "URLs with credential-like query parameters are blocked; use an environment-backed connector or operator capture",
            nota("https://example.com/?X-Amz-Signature=abc"),
        )
        assertEquals(
            "URLs with credential-like query parameters are blocked; use an environment-backed connector or operator capture",
            nota("https://example.com/?session_token=abc"),
        )
        // 4096 é em bytes UTF-8: 4096 caracteres com um "é" são 4097 bytes.
        val base = "https://example.com/"
        assertEquals("URL exceeds the 4096-character evidence limit", nota(base + "a".repeat(4096 - base.length - 1) + "é"))
        assertEquals(0, servidor.requestCount)
        // Controle: no teto exato em bytes, a URL passa e é coletada.
        robots()
        pagina()
        assertEquals(EstadoDaEvidencia.PRONTA, coletor.coletar(url("/") + "a".repeat(4096 - url("/").length)).estado)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `dominio que resolve para rede privada e bloqueado sem tentativa de conexao`() {
        val tabela = RedeDeTeste.TabelaDns(mapOf("10.0.0.1.example.com" to listOf("10.0.0.1")))
        val coletor = coletor(RedeDeTeste.politica(ResolvedorPublico(tabela)))
        val registro = coletor.coletar("https://10.0.0.1.example.com/source")
        assertEquals(EstadoDaEvidencia.BLOQUEADA, registro.estado)
        assertEquals(listOf("dominio resolve para IP privado/reservado bloqueado por seguranca"), registro.notas)
        assertEquals(listOf("10.0.0.1.example.com"), tabela.consultas)
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `pagina pronta traz todos os campos do canonico e o corpo`() {
        robots()
        pagina(corpo = "<html><head><title> A <b>fonte</b>\n oficial </title></head></html>")
        val coleta = coletor().coletarComConteudo(url("/a") + "#topo")
        val registro = coleta.registro
        val canonica = url("/a")
        assertEquals(EstadoDaEvidencia.PRONTA, registro.estado)
        assertEquals(RedeDeTeste.sha("http_fetch|GET|$canonica"), registro.id)
        assertEquals(canonica, registro.url)
        assertEquals(canonica, registro.urlFinal)
        assertEquals(200, registro.status)
        // Como no canônico: marcação vira espaço, controle vira espaço, e os espaços ficam.
        assertEquals("A  fonte   oficial", registro.titulo)
        assertEquals("text/html", registro.tipoDeConteudo)
        assertEquals(RedeDeTeste.sha("<html><head><title> A <b>fonte</b>\n oficial </title></head></html>"), registro.sha256)
        assertEquals("2026-09-25T12:00:00+00:00", registro.coletadaEm)
        assertEquals("2026-10-25T12:00:00+00:00", registro.expiraEm)
        assertEquals("P30D", registro.validadeDoCache)
        assertEquals(EstadoDoCache.FRESCO, registro.estadoDoCache)
        assertEquals(EstadoDoRobots.PERMITIDO, registro.estadoDoRobots)
        assertEquals(EstadoDeInteracao.NENHUMA, registro.estadoDeInteracao)
        assertEquals(coleta.corpo!!.size.toLong(), registro.bytes)
        assertNotNull(registro.duracaoMs)
        assertTrue(registro.cadeiaDeRedirecionamento.isEmpty())
        assertTrue(registro.notas.isEmpty())
        assertNull(registro.comandoCurl)
        assertNull(registro.provedor)
        assertContentEquals("<html><head><title> A <b>fonte</b>\n oficial </title></head></html>".toByteArray(), coleta.corpo)
        assertEquals("text/html", coleta.cabecalhos["content-type"])
        assertEquals(2, servidor.requestCount)
        assertEquals("/robots.txt", servidor.takeRequest().target)
        assertEquals("/a", servidor.takeRequest().target)
    }

    @Test
    fun `robots proibe antes da pagina, e robots indisponivel deixa a coleta seguir`() {
        robots(403)
        val proibida = coletor().coletarComConteudo(url("/a"))
        assertEquals(EstadoDaEvidencia.BLOQUEADA, proibida.registro.estado)
        assertEquals(EstadoDoRobots.PROIBIDO, proibida.registro.estadoDoRobots)
        assertEquals(listOf("robots.txt disallows automatic collection for this path"), proibida.registro.notas)
        assertNull(proibida.corpo)
        assertEquals(1, servidor.requestCount)

        robots(200, "User-agent: maestroeditorialai\nDisallow: /a\n")
        assertEquals(EstadoDaEvidencia.BLOQUEADA, coletor().coletar(url("/a?q=1")).estado)

        robots(500)
        pagina()
        val seguiu = coletor().coletar(url("/a"))
        assertEquals(EstadoDaEvidencia.PRONTA, seguiu.estado)
        assertEquals(EstadoDoRobots.INDISPONIVEL, seguiu.estadoDoRobots)
    }

    @Test
    fun `interacao pendente exige o operador e nao guarda corpo`() {
        fun colher(codigo: Int, corpo: String, tipo: String = "text/html"): ColetorHttp.Coleta {
            robots()
            pagina(codigo, corpo, tipo)
            return coletor().coletarComConteudo(url("/a"))
        }
        val nota = "Automatic collection reached an interaction boundary; use isolated rendering or operator capture"
        val login = colher(401, "")
        assertEquals(EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR, login.registro.estado)
        assertEquals(EstadoDeInteracao.EXIGE_LOGIN, login.registro.estadoDeInteracao)
        assertEquals(listOf(nota), login.registro.notas)
        assertEquals(EstadoDoCache.AUSENTE, login.registro.estadoDoCache)
        assertNull(login.corpo)
        assertEquals(EstadoDeInteracao.EXIGE_LOGIN, colher(403, "").registro.estadoDeInteracao)
        assertEquals(EstadoDeInteracao.EXIGE_CAPTCHA, colher(200, "<p>Solve the CAPTCHA</p>").registro.estadoDeInteracao)
        assertEquals(EstadoDeInteracao.EXIGE_LOGIN, colher(200, "<p>Please sign in</p>").registro.estadoDeInteracao)
        assertEquals(EstadoDeInteracao.EXIGE_CONSENTIMENTO, colher(200, "<p>Manage cookies</p>").registro.estadoDeInteracao)
        assertEquals(EstadoDeInteracao.PAYWALL, colher(200, "<p>Subscribe to continue</p>").registro.estadoDeInteracao)
        assertEquals(EstadoDeInteracao.CONFIRMAR_DOWNLOAD, colher(200, "<p>Confirm download</p>").registro.estadoDeInteracao)
        // Só corpo textual ou HTML é lido; um JSON com a palavra não é interação.
        val json = colher(200, """{"note":"captcha"}""", "application/json")
        assertEquals(EstadoDeInteracao.NENHUMA, json.registro.estadoDeInteracao)
        assertEquals(EstadoDaEvidencia.PRONTA, json.registro.estado)
        assertNotNull(json.corpo)
        // A amostra é de 128 KiB: a palavra depois dela não conta.
        val longe = colher(200, "a".repeat(128 * 1024) + "captcha")
        assertEquals(EstadoDeInteracao.NENHUMA, longe.registro.estadoDeInteracao)
        val perto = colher(200, "a".repeat(128 * 1024 - 7) + "captcha")
        assertEquals(EstadoDeInteracao.EXIGE_CAPTCHA, perto.registro.estadoDeInteracao)
    }

    @Test
    fun `resposta ruim sem interacao falhou, com hash mas sem corpo`() {
        robots()
        pagina(500, "<html>erro</html>")
        val coleta = coletor().coletarComConteudo(url("/a"))
        assertEquals(EstadoDaEvidencia.FALHOU, coleta.registro.estado)
        assertEquals(500, coleta.registro.status)
        assertEquals(RedeDeTeste.sha("<html>erro</html>"), coleta.registro.sha256)
        assertEquals(EstadoDoCache.AUSENTE, coleta.registro.estadoDoCache)
        assertNull(coleta.corpo)
        // Corpo vazio não tem hash.
        robots()
        pagina(204, "")
        assertNull(coletor().coletar(url("/a")).sha256)
    }

    @Test
    fun `PDF por cabecalho ou por assinatura ganha a nota, e o tipo e o do canonico`() {
        val nota = "PDF detected and hashed; text extraction was not attempted because no trusted extractor is configured"
        robots()
        pagina(200, "nao e pdf", "application/pdf")
        assertEquals(listOf(nota), coletor().coletar(url("/a")).notas)
        robots()
        pagina(200, "%PDF-1.7\n", "application/octet-stream")
        assertEquals(listOf(nota), coletor().coletar(url("/a")).notas)
        robots()
        pagina(200, "texto", "text/plain")
        assertTrue(coletor().coletar(url("/a")).notas.isEmpty())

        val vazio = ByteArray(0)
        assertEquals("pdf", ColetorHttp.Conteudo.tipo("application/pdf; charset=binary", vazio))
        assertEquals("pdf", ColetorHttp.Conteudo.tipo(null, "%PDF-".toByteArray()))
        assertEquals("html", ColetorHttp.Conteudo.tipo("text/html", vazio))
        assertEquals("html", ColetorHttp.Conteudo.tipo("application/xhtml+xml", vazio))
        assertEquals("md", ColetorHttp.Conteudo.tipo("text/markdown", vazio))
        assertEquals("json", ColetorHttp.Conteudo.tipo("application/ld+json", vazio))
        assertEquals("png", ColetorHttp.Conteudo.tipo("image/png", vazio))
        assertEquals("jpg", ColetorHttp.Conteudo.tipo("image/jpeg", vazio))
        assertEquals("webp", ColetorHttp.Conteudo.tipo("image/webp", vazio))
        assertEquals("txt", ColetorHttp.Conteudo.tipo("text/csv", vazio))
        assertEquals("bin", ColetorHttp.Conteudo.tipo("application/octet-stream", vazio))
        assertEquals("bin", ColetorHttp.Conteudo.tipo(null, vazio))
    }

    @Test
    fun `o titulo vem so de HTML, sem marcacao, nos primeiros 256 KiB e com 240 pontos de codigo`() {
        assertNull(ColetorHttp.Conteudo.titulo("<title>x</title>".toByteArray(), "text/plain"))
        assertNull(ColetorHttp.Conteudo.titulo("<title>  </title>".toByteArray(), "text/html"))
        assertEquals("a b", ColetorHttp.Conteudo.titulo("<TITLE lang=\"pt\">a<br/>b</TITLE>".toByteArray(), "text/html"))
        assertEquals("é".repeat(240), ColetorHttp.Conteudo.titulo("<title>${"é".repeat(300)}</title>".toByteArray(), "text/html"))
        val longe = ("a".repeat(256 * 1024) + "<title>tarde</title>").toByteArray()
        assertNull(ColetorHttp.Conteudo.titulo(longe, "text/html"))
    }

    @Test
    fun `falha de transporte e registrada com a nota e o robots ja lido`() {
        robots()
        servidor.enqueue(MockResponse.Builder().code(302).setHeader("Location", url("/b")).build())
        servidor.enqueue(MockResponse.Builder().code(302).setHeader("Location", url("/a")).build())
        val registro = coletor().coletar(url("/a"))
        assertEquals(EstadoDaEvidencia.FALHOU, registro.estado)
        assertEquals(listOf("redirect loop detected"), registro.notas)
        assertEquals(EstadoDoRobots.PERMITIDO, registro.estadoDoRobots)
        assertNull(registro.status)
    }

    @Test
    fun `304 sem registro guardado e erro`() {
        robots()
        servidor.enqueue(MockResponse.Builder().code(304).build())
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { coletor().coletar(url("/a")) }
        assertEquals("received HTTP 304 without a cached evidence record", erro.message)
    }

    @Test
    fun `armazem reaproveita o registro fresco, recoleta o vencido e preserva criadaEm`() {
        val armazem = ArmazemEmMemoria()
        robots()
        pagina()
        val primeira = coletor(armazem = armazem).coletarComConteudo(url("/a"))
        assertEquals(1, armazem.guardadas.size)
        assertEquals(2, servidor.requestCount)

        // Um dia depois: fresco, nada é pedido.
        val umDia = coletor(armazem = armazem) { agora.plusSeconds(86_400) }.coletarComConteudo(url("/a"))
        assertEquals(EstadoDaEvidencia.PRONTA, umDia.registro.estado)
        assertEquals(EstadoDoCache.FRESCO, umDia.registro.estadoDoCache)
        assertContentEquals(primeira.corpo, umDia.corpo)
        assertEquals(2, servidor.requestCount)

        // Trinta dias depois: vencido, recoleta, e criadaEm é o da primeira.
        robots()
        pagina(corpo = "<html><title>Nova</title></html>")
        val trintaDias = coletor(armazem = armazem) { agora.plusSeconds(30 * 86_400) }.coletarComConteudo(url("/a"))
        assertEquals(EstadoDaEvidencia.PRONTA, trintaDias.registro.estado)
        assertEquals("Nova", trintaDias.registro.titulo)
        assertEquals(primeira.registro.criadaEm, trintaDias.registro.criadaEm)
        assertEquals("2026-10-25T12:00:00+00:00", trintaDias.registro.coletadaEm)
        assertEquals(4, servidor.requestCount)

        // Registro que falhou não é reaproveitado.
        robots()
        pagina(500, "erro")
        coletor(armazem = armazem).coletar(url("/b"))
        robots()
        pagina()
        assertEquals(EstadoDaEvidencia.PRONTA, coletor(armazem = armazem).coletar(url("/b")).estado)
        assertEquals(8, servidor.requestCount)
    }

    @Test
    fun `revalidar envia os validadores guardados e o 304 renova o registro sem nova transferencia`() {
        val armazem = ArmazemEmMemoria()
        robots()
        servidor.enqueue(
            RedeDeTeste.resposta(
                200, "<html>v1</html>", "Content-Type" to "text/html", "ETag" to "\"v1\"",
                "Last-Modified" to "Wed, 23 Sep 2026 12:00:00 GMT",
            ),
        )
        val primeira = coletor(armazem = armazem).coletarComConteudo(url("/a"))
        servidor.takeRequest()
        assertNull(servidor.takeRequest().headers["If-None-Match"])

        robots()
        servidor.enqueue(MockResponse.Builder().code(304).setHeader("ETag", "\"v1\"").build())
        val depois = agora.plusSeconds(3_600)
        val renovada = coletor(armazem = armazem) { depois }.coletarComConteudo(url("/a"), revalidar = true)
        servidor.takeRequest()
        val condicional = servidor.takeRequest()
        assertEquals("\"v1\"", condicional.headers["If-None-Match"])
        assertEquals("Wed, 23 Sep 2026 12:00:00 GMT", condicional.headers["If-Modified-Since"])
        assertEquals(EstadoDaEvidencia.PRONTA, renovada.registro.estado)
        assertEquals("2026-09-25T13:00:00+00:00", renovada.registro.coletadaEm)
        assertEquals("2026-10-25T13:00:00+00:00", renovada.registro.expiraEm)
        assertEquals(primeira.registro.sha256, renovada.registro.sha256)
        assertEquals(primeira.registro.criadaEm, renovada.registro.criadaEm)
        assertEquals(listOf("Cache revalidated by HTTP 304; content hash preserved"), renovada.registro.notas)
        assertContentEquals(primeira.corpo, renovada.corpo)
        assertEquals(renovada.registro, armazem.guardadas.values.single().registro)
    }

    @Test
    fun `validadores so viajam, e o 304 so renova, sobre conteudo pronto guardado`() {
        val armazem = ArmazemEmMemoria()
        // Uma coleta que parou no login guarda os cabeçalhos, mas não é conteúdo.
        robots()
        servidor.enqueue(RedeDeTeste.resposta(401, "<html>Sign in</html>", "Content-Type" to "text/html", "ETag" to "\"v1\""))
        val parada = coletor(armazem = armazem).coletarComConteudo(url("/a"))
        assertEquals(EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR, parada.registro.estado)
        assertEquals("\"v1\"", parada.cabecalhos["etag"])
        assertNull(parada.corpo)
        // Revalidar não envia o validador dela, e a resposta cheia vira o registro pronto.
        robots()
        pagina()
        val cheia = coletor(armazem = armazem).coletarComConteudo(url("/a"), revalidar = true)
        repeat(3) { servidor.takeRequest() }
        assertNull(servidor.takeRequest().headers["If-None-Match"])
        assertEquals(EstadoDaEvidencia.PRONTA, cheia.registro.estado)
        assertNotNull(cheia.corpo)
        // Um 304 sobre registro guardado que não é conteúdo pronto é o erro do canônico.
        armazem.guardadas[parada.registro.id] = parada
        robots()
        servidor.enqueue(MockResponse.Builder().code(304).build())
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> {
            coletor(armazem = armazem).coletarComConteudo(url("/a"), revalidar = true)
        }
        assertEquals("received HTTP 304 without a cached evidence record", erro.message)
    }

    @Test
    fun `304 depois de redirecionamento novo renova o registro com o destino novo`() {
        val armazem = ArmazemEmMemoria()
        robots()
        servidor.enqueue(RedeDeTeste.resposta(200, "<html>v1</html>", "Content-Type" to "text/html", "ETag" to "\"v1\""))
        val primeira = coletor(armazem = armazem).coletarComConteudo(url("/a"))
        assertEquals(url("/a"), primeira.registro.urlFinal)
        robots()
        servidor.enqueue(MockResponse.Builder().code(302).setHeader("Location", url("/b")).build())
        servidor.enqueue(MockResponse.Builder().code(304).build())
        val renovada = coletor(armazem = armazem).coletarComConteudo(url("/a"), revalidar = true)
        assertEquals(EstadoDaEvidencia.PRONTA, renovada.registro.estado)
        assertEquals(url("/b"), renovada.registro.urlFinal)
        assertEquals(listOf(url("/b")), renovada.registro.cadeiaDeRedirecionamento.map { it.url })
        assertEquals(primeira.registro.sha256, renovada.registro.sha256)
    }

    @Test
    fun `cancelarTudo durante o robots aborta a coleta sem pedir a pagina, e nada mais comeca`() {
        servidor.enqueue(MockResponse.Builder().code(404).headersDelay(20, TimeUnit.SECONDS).build())
        pagina()
        val tabela = RedeDeTeste.TabelaDns(mapOf("example.com" to listOf(RedeDeTeste.PUBLICO)))
        val coletor = coletor(RedeDeTeste.politica(ResolvedorPublico(tabela)))
        var erro: Throwable? = null
        val inicio = System.nanoTime()
        val trabalho = thread {
            try {
                coletor.coletar(url("/a"))
            } catch (e: Throwable) {
                erro = e
            }
        }
        Thread.sleep(500)
        coletor.cancelarTudo()
        trabalho.join(10_000)
        assertTrue((System.nanoTime() - inicio) < 10_000_000_000L)
        assertTrue(erro is ColetaCancelada, erro.toString())
        assertEquals(1, servidor.requestCount)
        // Depois do cancelamento, nem a validação (que consulta o DNS) começa.
        assertFailsWith<ColetaCancelada> { coletor.coletar(url("/c")) }
        assertFailsWith<ColetaCancelada> { coletor.coletar("https://example.com/") }
        assertEquals(1, servidor.requestCount)
        assertTrue(tabela.consultas.isEmpty(), tabela.consultas.toString())
    }

    @Test
    fun `recoleta que falhou ou parou nao apaga o ultimo corpo pronto do armazem`() {
        val armazem = ArmazemEmMemoria()
        robots()
        pagina(corpo = "<html>pronto</html>")
        val pronta = coletor(armazem = armazem).coletarComConteudo(url("/a"))
        // Trinta dias depois, a recoleta cai num 500 e depois num login.
        for ((codigo, corpo) in listOf(500 to "<html>erro</html>", 401 to "<html>Sign in</html>")) {
            robots()
            pagina(codigo, corpo)
            val falhou = coletor(armazem = armazem) { agora.plusSeconds(30 * 86_400) }.coletarComConteudo(url("/a"))
            assertNull(falhou.corpo)
            val guardada = armazem.guardadas.getValue(pronta.registro.id)
            assertEquals(falhou.registro, guardada.registro)
            assertContentEquals("<html>pronto</html>".toByteArray(), guardada.corpo)
        }
        // O registro guardado não é conteúdo pronto: não é reaproveitado nem revalidado.
        robots()
        pagina(corpo = "<html>novo</html>")
        val nova = coletor(armazem = armazem) { agora.plusSeconds(31 * 86_400) }.coletarComConteudo(url("/a"), revalidar = true)
        repeat(5) { servidor.takeRequest() }
        assertNull(servidor.takeRequest().headers["If-None-Match"])
        assertContentEquals("<html>novo</html>".toByteArray(), armazem.guardadas.getValue(pronta.registro.id).corpo)
        assertEquals(EstadoDaEvidencia.PRONTA, nova.registro.estado)
    }

    @Test
    fun `a URL gravada no registro bloqueado nao leva credencial nem valor sensivel`() {
        val coletor = coletor()
        assertEquals("https://example.com/", coletor.coletar("https://user:password@example.com/").url)
        assertEquals("https://example.com/?x=1&access_token=%3Credacted%3E", coletor.coletar("https://example.com/?x=1&access_token=topsecret").url)
        assertEquals(
            "https://example.com/?Sig=%3Credacted%3E&session_token=%3Credacted%3E&x=2#f",
            coletor.coletar("https://example.com/?Sig=abc&session_token=def&x=2#f").url,
        )
        // O que o parser não lê é tratado no texto, com a mesma regra.
        assertEquals("http://exa mple.com/?token=<redacted>&ok=1", coletor.coletar("http://user:pw@exa mple.com/?token=abc&ok=1").url)
        assertEquals(0, servidor.requestCount)
        // O id preliminar continua sendo o da URL como o texto a citou.
        assertEquals(RedeDeTeste.sha("http_fetch|GET|https://user:password@example.com/"), coletor.coletar("https://user:password@example.com/").id)
    }

    @Test
    fun `projecao do cache como no canonico`() {
        robots()
        pagina()
        val pronta = coletor().coletar(url("/a"))
        assertEquals(EstadoDoCache.FRESCO, ColetorHttp.estadoDoCache(pronta, agora))
        assertEquals(EstadoDoCache.VENCIDO, ColetorHttp.estadoDoCache(pronta, agora.plusSeconds(30 * 86_400)))
        assertEquals(EstadoDaEvidencia.VENCIDA, ColetorHttp.projetar(pronta, agora.plusSeconds(30 * 86_400)).estado)
        assertEquals(EstadoDoCache.VENCIDO, ColetorHttp.estadoDoCache(pronta.copy(expiraEm = null), agora))
        assertEquals(EstadoDoCache.VENCIDO, ColetorHttp.estadoDoCache(pronta.copy(expiraEm = "ontem"), agora))
        assertEquals(EstadoDoCache.AUSENTE, ColetorHttp.estadoDoCache(pronta.copy(estado = EstadoDaEvidencia.FALHOU), agora))
        assertEquals(EstadoDoCache.FRESCO, ColetorHttp.estadoDoCache(pronta.copy(estado = EstadoDaEvidencia.VENCIDA), agora))
    }

    @Test
    fun `cancelarTudo interrompe a coleta em curso`() {
        robots()
        servidor.enqueue(MockResponse.Builder().body("lento").headersDelay(20, TimeUnit.SECONDS).build())
        val coletor = coletor()
        var registro: dev.lcv.maestro.protocolo.RegistroDeEvidencia? = null
        var erro: Throwable? = null
        val inicio = System.nanoTime()
        val trabalho = thread {
            try {
                registro = coletor.coletar(url("/a"))
            } catch (e: Throwable) {
                erro = e
            }
        }
        Thread.sleep(500)
        coletor.cancelarTudo()
        trabalho.join(10_000)
        assertTrue((System.nanoTime() - inicio) < 10_000_000_000L)
        assertNull(registro)
        assertTrue(erro is ColetaCancelada, erro.toString())
    }

    @Test
    fun `constantes do canonico`() {
        assertEquals(8 * 1024 * 1024, ColetorHttp.TETO_DO_CORPO_BYTES)
        assertEquals(512 * 1024, LeitorDeRobots.TETO_DO_ROBOTS_BYTES)
        assertEquals(30L, TransportePublico.PRAZO_SEGUNDOS)
        assertEquals(5, TransportePublico.MAX_REDIRECIONAMENTOS)
        assertEquals(30L, ColetorHttp.DIAS_DE_CACHE)
        assertEquals(20, BuscaDeEvidencias.MAX_RESULTADOS)
        assertEquals(4_096, UrlPublica.LIMITE_EM_BYTES)
        assertEquals(64, RedeDeTeste.sha("stable source key").length)
        assertTrue(RedeDeTeste.sha("stable source key").all { it in '0'..'9' || it in 'a'..'f' })
    }
}
