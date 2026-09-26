package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.CofreDeChaves
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `loadSettings` e `handleMaestroAiSettingsPut`, sobre Room; o cofre é o do processo. */
@RunWith(AndroidJUnit4::class)
class ConfiguracoesGravacaoTest {

    private val t = BancoDeTeste()
    private val repositorio = RepositorioDeConfiguracoes(t.banco, CofreDeChaves.criar(t.contexto, 7.seconds), t.relogio)

    @After
    fun fechar() = t.fechar()

    private fun recusa(pedido: PedidoDeConfiguracoes): String = (repositorio.salvar(pedido) as Resultado.Recusado).mensagem

    private fun ok(pedido: PedidoDeConfiguracoes): Configuracoes = (repositorio.salvar(pedido) as Resultado.Ok).valor

    @Test
    fun semLinhaVoltaOsPadroes() {
        val configuracoes = repositorio.carregar()
        assertEquals(RepositorioDeConfiguracoes.PROTOCOLO_PADRAO, configuracoes.protocolo)
        assertEquals(BigDecimal.ZERO, configuracoes.tetoDeCustoUsd)
        assertNull(configuracoes.tetoDeMinutos)
        assertEquals(2, configuracoes.maxCiclos)
        assertEquals(Taxas.PADRAO, configuracoes.taxas)
    }

    @Test
    fun regrasDeRecusaDoWeb() {
        assertEquals("Protocolo editorial integral deve ter pelo menos 100 caracteres.", recusa(PedidoDeConfiguracoes(protocolo = "curto", tetoDeCustoUsd = BigDecimal.ONE)))
        assertEquals("Teto financeiro em USD deve ser positivo.", recusa(PedidoDeConfiguracoes()))
        assertEquals("Ciclos maximos devem ser um inteiro entre 1 e 5.", recusa(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal.ONE, maxCiclos = 6)))
        assertEquals("Limite de tempo opcional deve ficar entre 1 e 300 minutos.", recusa(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal.ONE, tetoDeMinutos = Campo.Presente(301))))
        assertEquals("Limite de tempo opcional deve ficar entre 1 e 300 minutos.", recusa(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal.ONE, tetoDeMinutos = Campo.Presente(-1))))
        assertNull(t.banco.configuracoes().carregar())
    }

    @Test
    fun tetoDeMinutosNuloLimpaEAusenteMantem() {
        assertEquals(300, ok(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal("2"), tetoDeMinutos = Campo.Presente(300))).tetoDeMinutos)
        assertEquals(300, ok(PedidoDeConfiguracoes(maxCiclos = 3)).tetoDeMinutos)
        assertEquals(3, repositorio.carregar().maxCiclos)
        assertNull(ok(PedidoDeConfiguracoes(tetoDeMinutos = Campo.Presente(null))).tetoDeMinutos)
        assertNull(ok(PedidoDeConfiguracoes(tetoDeMinutos = Campo.Presente(0))).tetoDeMinutos)
        // Um negativo não limpa: o limite que estava lá fica.
        assertEquals(30, ok(PedidoDeConfiguracoes(tetoDeMinutos = Campo.Presente(30))).tetoDeMinutos)
        assertTrue(repositorio.salvar(PedidoDeConfiguracoes(tetoDeMinutos = Campo.Presente(-7))) is Resultado.Recusado)
        assertEquals(30, repositorio.carregar().tetoDeMinutos)
        assertEquals(0, BigDecimal("2").compareTo(repositorio.carregar().tetoDeCustoUsd))
    }

    @Test
    fun taxasSaoSaneadasAoGravar() {
        val salvas = ok(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal.ONE, taxas = mapOf(Provedor.CLAUDE to Custo.Taxas(BigDecimal("1"), BigDecimal("99")))))
        assertEquals(BigDecimal("10"), salvas.taxas.getValue(Provedor.CLAUDE).entradaPorMilhao)
        assertEquals(BigDecimal("99"), salvas.taxas.getValue(Provedor.CLAUDE).saidaPorMilhao)
        assertEquals(Taxas.PADRAO.getValue(Provedor.GROK), salvas.taxas.getValue(Provedor.GROK))
        assertEquals(salvas.taxas, repositorio.carregar().taxas)
    }
}
