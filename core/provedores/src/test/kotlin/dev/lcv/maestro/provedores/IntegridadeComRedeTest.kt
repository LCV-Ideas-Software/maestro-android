package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.Dns

/**
 * O motor do `:core:protocolo` com o parser e a coleta reais: o que os
 * testes de lá deixaram para cá (`IntegridadeDeLinksTest.kt`,
 * `AuditoriaFinalTest.kt`) — `url_parser_normalization`, o tipo divergente
 * com um `.pdf` de verdade, `mailto:` não coletado, o bloqueio do canônico
 * (`link_audit_reports_blocked_links_with_invalidity`, `lib.rs:1376`) e a
 * recusa de esquemas e credenciais com o parser real.
 */
class IntegridadeComRedeTest {

    private val servidor = RedeDeTeste.servidorHttps()

    @AfterTest
    fun descer() = servidor.close()

    private val registro = object : IntegridadeDeLinks.RegistroDeLinks {
        val linhas = LinkedHashMap<String, LinhaDeLink>()
        override fun <T> emTransacao(bloco: () -> T): T = synchronized(this) { bloco() }
        override fun carregar(linkId: String) = linhas[linkId]
        override fun salvar(linha: LinhaDeLink) {
            linhas[linha.linkId] = linha
        }
        override fun anotar(tipo: String, linha: LinhaDeLink) = Unit
        override fun todos() = linhas.values.toList()
    }

    private fun auditar(texto: String, politica: UrlPublica.PoliticaDeRede = RedeDeTeste.politica()) =
        IntegridadeDeLinks.auditar(
            texto,
            AnalisadorDeUrlOkHttp,
            ColetorHttp(RedeDeTeste.cliente(), Dns.SYSTEM, politica, RedeDeTeste.agente, null, { RedeDeTeste.agora }),
            registro,
        ) { RedeDeTeste.agora }

    private fun robotsEPagina(tipo: String = "text/html", corpo: String = "<html><title>Fonte</title></html>") {
        servidor.enqueue(RedeDeTeste.resposta(404, "", "Content-Type" to "text/plain"))
        servidor.enqueue(RedeDeTeste.resposta(200, corpo, "Content-Type" to tipo))
    }

    @Test
    fun `a normalizacao do parser real e registrada e o link coletado passa`() {
        robotsEPagina()
        val gritada = "HTTPS://LOCALHOST:${servidor.port}/A"
        val linha = auditar("Ver $gritada agora.").linhas.single()
        assertEquals(gritada, linha.urlOriginal)
        assertEquals("https://localhost:${servidor.port}/A", linha.urlNormalizada)
        assertTrue("url_parser_normalization" in linha.mudancasDaNormalizacao, linha.mudancasDaNormalizacao.toString())
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, linha.classificacao)
        assertEquals("warn", linha.tom)
        assertEquals("HTTP 200", linha.status)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `um caminho pdf servido como HTML e tipo divergente`() {
        robotsEPagina()
        val linha = auditar("Ver ${servidor.url("/relatorio.pdf")} agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE, linha.classificacao)
        assertEquals("error", linha.tom)
        // Controle: o mesmo caminho servido como PDF passa.
        robotsEPagina("application/pdf", "%PDF-1.7\n")
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, auditar("Ver ${servidor.url("/outro.pdf")} agora.").linhas.single().classificacao)
    }

    @Test
    fun `links bloqueados pela regra publica e mailto nao geram requisicao`() {
        val resultado = auditar("Veja http://localhost:8787/x e http://127.0.0.1/test.", RedeDeTeste.politicaReal())
        assertEquals(2, resultado.urlsEncontradas)
        // `checked` conta as linhas http (`link_integrity.rs:671`); o `checked == 0` do teste
        // legado era do `run_link_audit` antigo, que barrava antes de contar.
        assertEquals(2, resultado.verificadas)
        assertEquals(2, resultado.falhas)
        assertEquals(2, resultado.bloqueadas)
        val local = resultado.linhas.single { it.url == "http://localhost:8787/x" }
        assertEquals("blocked", local.tom)
        assertEquals(ClassificacaoDoLink.EM_QUARENTENA, local.classificacao)
        assertEquals("endereco local bloqueado por seguranca", local.invalidade)
        assertTrue(resultado.linhas.all { it.invalidade.isNotBlank() })

        val correio = auditar("Escreva para mailto:editor@example.com hoje.").linhas.single()
        assertEquals("warn", correio.tom)
        val alvos = (0 until servidor.requestCount).map { servidor.takeRequest().target }
        assertTrue(alvos.isEmpty(), alvos.toString())
    }

    @Test
    fun `esquemas nao suportados e credenciais sao malformados com o parser real`() {
        val resultado = auditar(
            "Ver javascript:alert(1), ftp://files.example.com/a.zip, tel:+5511999999999, " +
                "https://user:secret@example.com/ e https://:@example.com/ agora.",
        )
        assertEquals(5, resultado.linhas.size)
        for (linha in resultado.linhas) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("blocked", linha.tom, linha.url)
        }
        assertEquals(0, servidor.requestCount)
    }
}
