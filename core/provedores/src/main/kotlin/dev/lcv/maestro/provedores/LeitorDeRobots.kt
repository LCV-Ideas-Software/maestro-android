package dev.lcv.maestro.provedores

import crawlercommons.robots.SimpleRobotRulesParser
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import okhttp3.HttpUrl

/**
 * `robots_state_for` (`web_evidence.rs:865-890`): a permissão do
 * `robots.txt` da origem para o caminho pedido. A busca e a leitura dos
 * códigos de status são as do canônico; a interpretação do arquivo é a do
 * parser de referência da RFC 9309, o crawler-commons, por decisão do
 * operador de 25/09/2026, no lugar do `robots_disallows_path` escrito à mão.
 *
 * O que muda com a RFC, e fica travado pelos testes:
 *
 * - O nome do robô casa como no canônico: `maestroeditorialai` exato, sem
 *   distinguir caixa, ou seguido de `/versão` (o "product token" da linha,
 *   `SimpleRobotRulesParser.userAgentProductTokenPartialMatch`).
 * - Grupo específico para este robô **substitui** o grupo `*`
 *   (RFC 9309, seção 2.2.1); o canônico somava os dois.
 * - `Crawl-delay` é ignorado, como no canônico: o teto do parser, que
 *   proibiria tudo quando excedido, fica em `Long.MAX_VALUE`.
 * - Só o caminho é comparado, como no canônico (`:885`): a query da URL é
 *   removida antes da consulta.
 */
internal class LeitorDeRobots(
    private val transporte: TransportePublico,
    private val politica: UrlPublica.PoliticaDeRede,
    private val nomeDoRobo: String,
) {
    private val parser = SimpleRobotRulesParser(Long.MAX_VALUE, 0)

    fun estado(url: HttpUrl): EstadoDoRobots {
        val urlDoRobots = try {
            UrlPublica.robotsDe(url, politica)
        } catch (erro: IntegridadeDeLinks.Falha) {
            return EstadoDoRobots.INDISPONIVEL
        }
        val resposta = try {
            transporte.executar(MetodoHttp.GET, urlDoRobots, emptyMap(), TETO_DO_ROBOTS_BYTES)
        } catch (erro: IntegridadeDeLinks.Falha) {
            return EstadoDoRobots.INDISPONIVEL
        }
        when (resposta.status) {
            401, 403 -> return EstadoDoRobots.PROIBIDO
            404, 410 -> return EstadoDoRobots.PERMITIDO
            in 200..299 -> Unit
            else -> return EstadoDoRobots.INDISPONIVEL
        }
        val regras = parser.parseContent(
            urlDoRobots.toString(),
            resposta.corpo,
            resposta.cabecalhos["content-type"],
            listOf(nomeDoRobo),
        )
        val soCaminho = url.newBuilder().query(null).fragment(null).build().toString()
        return if (regras.isAllowed(soCaminho)) EstadoDoRobots.PERMITIDO else EstadoDoRobots.PROIBIDO
    }

    companion object {
        /** `MAX_ROBOTS_BYTES`. */
        const val TETO_DO_ROBOTS_BYTES = 512 * 1024
    }
}
