package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `resolveStartRequest` e `configuredAgents` com o terceiro estado do cofre; a gravação é instrumentada. */
class ConfiguracoesTest {

    private val configuracoes = Configuracoes(
        protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
        tetoDeCustoUsd = BigDecimal("5"),
        tetoDeMinutos = 30,
        maxCiclos = 2,
        taxas = Taxas.PADRAO,
        atualizadaEm = Fixtures.ISO_AGORA,
    )
    private val todasAsChaves = Provedor.entries.associateWith { true as Boolean? }

    private fun resolver(
        pedido: PedidoDeInicio = PedidoDeInicio(pedido = "Escreva sobre X."),
        configuracoes: Configuracoes = this.configuracoes,
        chaves: Map<Provedor, Boolean?> = todasAsChaves,
    ) = RepositorioDeConfiguracoes.resolverInicio(pedido, configuracoes, chaves)

    private fun recusa(resultado: Resultado<*>): String = assertIs<Resultado.Recusado>(resultado).mensagem

    @Test
    fun `elegibilidade distingue sem chave, sem taxas e nao verificavel`() {
        val chaves = mapOf(Provedor.CLAUDE to true, Provedor.CODEX to false, Provedor.GEMINI to null) + mapOf(Provedor.GROK to true)
        val taxas = Taxas.PADRAO + (Provedor.GROK to Custo.Taxas(BigDecimal.ZERO, BigDecimal.ONE))
        val elegibilidade = RepositorioDeConfiguracoes.elegibilidade(taxas, chaves)
        assertEquals(Elegibilidade.ELEGIVEL, elegibilidade[Provedor.CLAUDE])
        assertEquals(Elegibilidade.SEM_CHAVE, elegibilidade[Provedor.CODEX])
        assertEquals(Elegibilidade.NAO_VERIFICAVEL, elegibilidade[Provedor.GEMINI])
        assertEquals(Elegibilidade.SEM_TAXAS, elegibilidade[Provedor.GROK])
        assertEquals(Elegibilidade.NAO_VERIFICAVEL, elegibilidade[Provedor.DEEPSEEK])
    }

    @Test
    fun `sessao valida sai com os agentes elegiveis e o inicial na frente`() {
        val entrada = assertIs<Resultado.Ok<EntradaResolvida>>(resolver(PedidoDeInicio(pedido = " Escreva. ", agenteInicial = "xai", titulo = ""))).valor
        assertEquals("Sessao Maestro AI", entrada.titulo)
        assertEquals("Escreva.", entrada.pedido)
        assertEquals(Provedor.GROK, entrada.agenteInicial)
        assertEquals(Provedor.entries.toList(), entrada.agentesAtivos)
        assertEquals(BigDecimal("5"), entrada.tetoDeCustoUsd)
        assertEquals(30, entrada.tetoDeMinutos)
        assertEquals("", entrada.conteudoInicial)
    }

    @Test
    fun `inicial fora do painel vira o primeiro do painel`() {
        val entrada = assertIs<Resultado.Ok<EntradaResolvida>>(resolver(PedidoDeInicio(pedido = "x", agenteInicial = "grok", agentesAtivos = listOf("codex", "gemini")))).valor
        assertEquals(listOf(Provedor.GROK, Provedor.CODEX, Provedor.GEMINI), entrada.agentesAtivos)
        assertEquals(Provedor.GROK, entrada.agenteInicial)
        val soDois = assertIs<Resultado.Ok<EntradaResolvida>>(
            resolver(PedidoDeInicio(pedido = "x", agenteInicial = "grok", agentesAtivos = listOf("codex", "gemini")), chaves = todasAsChaves + (Provedor.GROK to false)),
        ).valor
        assertEquals(listOf(Provedor.CODEX, Provedor.GEMINI), soDois.agentesAtivos)
        assertEquals(Provedor.CODEX, soDois.agenteInicial)
    }

    @Test
    fun `mensagens de recusa do web, na ordem do web`() {
        assertEquals("Prompt editorial obrigatorio.", recusa(resolver(PedidoDeInicio(pedido = " \u0000 "))))
        assertEquals("Configure e salve o protocolo editorial integral antes de iniciar.", recusa(resolver(configuracoes = configuracoes.copy(protocolo = "curto"))))
        assertEquals(
            "Configure pelo menos dois agentes com chave e tarifas antes de iniciar.",
            recusa(resolver(chaves = Provedor.entries.associateWith { (it == Provedor.CLAUDE) as Boolean? })),
        )
        assertEquals("Teto financeiro em USD e obrigatorio nas configuracoes ou na sessao.", recusa(resolver(configuracoes = configuracoes.copy(tetoDeCustoUsd = BigDecimal.ZERO))))
        val comTeto = assertIs<Resultado.Ok<EntradaResolvida>>(resolver(PedidoDeInicio(pedido = "x", tetoDeCustoUsd = BigDecimal("0.5")), configuracoes.copy(tetoDeCustoUsd = BigDecimal.ZERO))).valor
        assertEquals(BigDecimal("0.5"), comTeto.tetoDeCustoUsd)
        assertEquals("Ciclos maximos devem estar entre 1 e 5 nas configuracoes.", recusa(resolver(configuracoes = configuracoes.copy(maxCiclos = 6))))
    }

    @Test
    fun `teto de minutos nao positivo vira sem limite`() {
        val entrada = assertIs<Resultado.Ok<EntradaResolvida>>(resolver(configuracoes = configuracoes.copy(tetoDeMinutos = 0))).valor
        assertEquals(null, entrada.tetoDeMinutos)
    }
}
