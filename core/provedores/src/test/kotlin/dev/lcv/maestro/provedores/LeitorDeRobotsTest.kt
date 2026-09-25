package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.EstadoDoRobots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.Dns

/**
 * `robots_state_for` com o parser do crawler-commons: os códigos de status
 * do canônico, `robots_parser_uses_longest_matching_rule`
 * (`web_evidence.rs:3621`), o nome do robô com e sem versão, e as
 * divergências da RFC 9309 que a decisão do operador trouxe, travadas.
 */
class LeitorDeRobotsTest {

    private val servidor = RedeDeTeste.servidorHttps()

    @AfterTest
    fun descer() = servidor.close()

    private fun leitor(politica: UrlPublica.PoliticaDeRede = RedeDeTeste.politica()) = LeitorDeRobots(
        TransportePublico(RedeDeTeste.cliente(), Dns.SYSTEM, politica, RedeDeTeste.agente),
        politica,
        RedeDeTeste.agente.nomeDoRobo,
    )

    private fun estado(caminho: String, robots: String, codigo: Int = 200, tipo: String = "text/plain"): EstadoDoRobots {
        servidor.enqueue(RedeDeTeste.resposta(codigo, robots, "Content-Type" to tipo))
        val estado = leitor().estado(servidor.url(caminho))
        val pedido = servidor.takeRequest()
        assertEquals("/robots.txt", pedido.target)
        assertEquals(RedeDeTeste.agente.userAgent, pedido.headers["User-Agent"])
        return estado
    }

    @Test
    fun `codigos de status como no canonico`() {
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", "", 401))
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", "", 403))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "", 404))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "", 410))
        assertEquals(EstadoDoRobots.INDISPONIVEL, estado("/x", "", 500))
        assertEquals(EstadoDoRobots.INDISPONIVEL, estado("/x", "", 429))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "", 200))
    }

    @Test
    fun `a regra mais longa vence`() {
        val robots = "User-agent: *\nDisallow: /private\nAllow: /private/public\n"
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/private/file", robots))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/private/public/file", robots))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/open", robots))
    }

    @Test
    fun `o nome do robo casa exato, sem caixa, ou com versao, e nao como prefixo de outro`() {
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", "User-agent: maestroeditorialai\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", "User-agent: MaestroEditorialAI\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", "User-agent: MaestroEditorialAI/2.0\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/y", "User-agent: maestroeditorialai\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "User-agent: maestroeditorialaix\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "User-agent: maestro\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "User-agent: outrobot\nDisallow: /\n"))
    }

    @Test
    fun `grupo especifico substitui o grupo geral, Crawl-delay e ignorado e HTML sem regras libera`() {
        // RFC 9309, 2.2.1: divergência do canônico, que somava os dois grupos.
        val doisGrupos = "User-agent: *\nDisallow: /private\n\nUser-agent: maestroeditorialai\nDisallow: /other\n"
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/private/a", doisGrupos))
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/other/a", doisGrupos))
        // Sem grupo específico, o geral vale.
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/private/a", "User-agent: *\nDisallow: /private\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/y", "User-agent: *\nCrawl-delay: 100000\nDisallow: /x\n"))
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/x", "<html><body>Not found</body></html>", tipo = "text/html"))
    }

    @Test
    fun `so o caminho e comparado, sem a query`() {
        assertEquals(EstadoDoRobots.PERMITIDO, estado("/a?x=1", "User-agent: *\nDisallow: /a?\n"))
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/a?x=1", "User-agent: *\nDisallow: /a\n"))
    }

    @Test
    fun `o teto de 512 KiB e exato`() {
        val regra = "User-agent: *\nDisallow: /x\n"
        val noTeto = regra + "#" + "a".repeat(LeitorDeRobots.TETO_DO_ROBOTS_BYTES - regra.length - 2) + "\n"
        assertEquals(LeitorDeRobots.TETO_DO_ROBOTS_BYTES, noTeto.length)
        assertEquals(EstadoDoRobots.PROIBIDO, estado("/x", noTeto))
        assertEquals(EstadoDoRobots.INDISPONIVEL, estado("/x", noTeto + "a"))
    }

    @Test
    fun `robots cuja URL a regra recusa e indisponivel sem requisicao`() {
        val recusaRobots = UrlPublica.PoliticaDeRede { if (it.endsWith("/robots.txt")) "recusado" else null }
        assertEquals(EstadoDoRobots.INDISPONIVEL, leitor(recusaRobots).estado(servidor.url("/x")))
        assertEquals(0, servidor.requestCount)
    }
}
