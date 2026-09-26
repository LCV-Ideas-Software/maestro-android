package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `persistSession`, `persistObservedCostFloor`, o cancelamento e a troca de conteúdo, sobre Room em arquivo. */
@RunWith(AndroidJUnit4::class)
class RepositorioDeSessoesTest {

    private val t = BancoDeTeste()

    @After
    fun fechar() = t.fechar()

    private fun criar(): SessaoEntidade = t.sessoes.criar(t.entrada())

    private fun eventos(id: String) = Jornal.lerEstrito(t.sessoes.carregar(id)!!.eventosJson)

    @Test
    fun criarGravaALinhaComOPrimeiroEventoNaFila() {
        val sessao = criar()
        assertTrue(sessao.id.startsWith("android-"))
        val lida = t.sessoes.carregar(sessao.id)!!
        assertEquals(Estados.NA_FILA, lida.status)
        assertEquals("claude", lida.liderDoCiclo)
        assertEquals("{}", lida.estadoCircularJson)
        assertEquals(BigDecimal.ZERO, lida.custoObservadoUsd)
        assertEquals(listOf("Maestro AI Android session queued."), eventos(sessao.id).map { it.mensagem })
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI), RepositorioDeSessoes.lerAgentes(lida.agentesAtivosJson))
    }

    @Test
    fun persistirEscreveSoOsCamposPresentesEAnexaOEvento() {
        val id = criar().id
        val antes = t.sessoes.carregar(id)!!
        val aplicado = t.sessoes.persistir(
            id,
            Remendo(status = Campo.Presente(Estados.RODANDO), textoFinal = Campo.Presente(null), erro = Campo.Presente("e")),
            evento = t.evento(EventoDaSessao.RODANDO, "vai"),
        )
        assertTrue(aplicado)
        val depois = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, depois.status)
        assertEquals("e", depois.erro)
        assertNull(depois.textoFinal)
        assertEquals(antes.titulo, depois.titulo)
        assertTrue(depois.atualizadaEm > antes.atualizadaEm)
        assertEquals(listOf("Maestro AI Android session queued.", "vai"), eventos(id).map { it.mensagem })
        assertFalse(t.sessoes.persistir(id, Remendo()))
    }

    @Test
    fun persistirComPortaoQueNaoCasaNaoEscreveNada() {
        val id = criar().id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.CANCELADA)))
        val antes = t.sessoes.carregar(id)!!
        val aplicado = t.sessoes.persistir(
            id,
            Remendo(status = Campo.Presente(Estados.RODANDO), textoAtual = Campo.Presente("novo")),
            seSituacaoEm = setOf(Estados.RODANDO),
            evento = t.evento(EventoDaSessao.RODANDO, "atrasado"),
        )
        assertFalse(aplicado)
        assertEquals(antes, t.sessoes.carregar(id))
    }

    @Test
    fun persistirComPortaoQueCasaEscreve() {
        val id = criar().id
        assertTrue(t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.RODANDO)), seSituacaoEm = Estados.ATIVOS))
        assertEquals(Estados.RODANDO, t.sessoes.carregar(id)!!.status)
    }

    @Test
    fun eventoAtrasadoDoRunnerNaoSobrescreveOCancelamento() {
        val id = criar().id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.RODANDO)))
        val cancelamento = t.sessoes.cancelar(id)
        assertTrue(cancelamento is Resultado.Ok)
        assertFalse(t.sessoes.persistir(id, Remendo(), seSituacaoEm = Estados.ATIVOS, evento = t.evento(EventoDaSessao.PRONTO, "turno atrasado")))
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.CANCELADA, linha.status)
        assertEquals("Sessao cancelada pelo operador.", linha.erro)
        assertEquals("Sessao cancelada pelo operador.", eventos(id).last().mensagem)
        assertEquals(EventoDaSessao.BLOQUEADO, eventos(id).last().status)
    }

    @Test
    fun cancelarRecusaSessaoJaTerminal() {
        val id = criar().id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("finished")))
        val resultado = t.sessoes.cancelar(id) as Resultado.Recusado
        assertEquals("Sessao ja finalizada; nada a cancelar.", resultado.mensagem)
        assertEquals("Sessao Maestro AI nao encontrada.", (t.sessoes.cancelar("android-nao-existe") as Resultado.Recusado).mensagem)
    }

    @Test
    fun pisoDeCustoNuncaBaixa() {
        val id = criar().id
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("0.50"))
        assertEquals(BigDecimal("0.50"), t.sessoes.carregar(id)!!.custoObservadoUsd)
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("0.25"))
        assertEquals(BigDecimal("0.50"), t.sessoes.carregar(id)!!.custoObservadoUsd)
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("-3"))
        assertEquals(BigDecimal("0.50"), t.sessoes.carregar(id)!!.custoObservadoUsd)
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("1.5"))
        assertEquals(BigDecimal("1.5"), t.sessoes.carregar(id)!!.custoObservadoUsd)
        // Comparado como número, não como texto: "10" > "9".
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("9"))
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("10"))
        assertEquals(BigDecimal("10"), t.sessoes.carregar(id)!!.custoObservadoUsd)
    }

    @Test
    fun substituirConteudoRecusaSessaoAtivaERetomavelSemTextoFinal() {
        val id = criar().id
        val ativa = t.sessoes.substituirConteudo(id, null, "x") as Resultado.Recusado
        assertEquals("Sessao em execucao; aguarde terminar ou cancele antes de editar o conteudo.", ativa.mensagem)
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("paused_cost_limit"), textoAtual = Campo.Presente("texto")))
        val retomavel = t.sessoes.substituirConteudo(id, "Novo título", "outro") as Resultado.Recusado
        assertTrue(retomavel.mensagem.startsWith("Conteudo de uma sessao retomavel nao pode ser substituido fora da cadeia de custodia."))
        // Sem `conteudo`, o título muda e o texto fica.
        val soTitulo = t.sessoes.substituirConteudo(id, "Novo título", null) as Resultado.Ok
        assertEquals("Novo título", soTitulo.valor.titulo)
        assertEquals("texto", soTitulo.valor.textoAtual)
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("finished"), textoFinal = Campo.Presente("texto")))
        val trocado = t.sessoes.substituirConteudo(id, null, "  editado  ") as Resultado.Ok
        assertEquals("editado", trocado.valor.textoAtual)
        assertEquals("Novo título", trocado.valor.titulo)
    }

    @Test
    fun marcarInterrompidaDeixaASessaoRetomavel() {
        val id = criar().id
        assertTrue(t.sessoes.marcarInterrompida(id, t.evento(EventoDaSessao.ERRO, "processo morreu")))
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.ERRO, linha.status)
        assertEquals(RepositorioDeSessoes.MENSAGEM_INTERROMPIDA, linha.erro)
        assertTrue(linha.status in Estados.RETOMAVEIS)
        assertFalse(t.sessoes.marcarInterrompida(id))
        assertEquals(0, t.sessoes.emExecucao().size)
    }

    @Test
    fun observarEmiteACadaPersistencia() = runBlocking {
        val id = criar().id
        val emissoes = async(Dispatchers.IO) { withTimeout(10_000) { t.sessoes.observar(id).take(3).toList() } }
        delay(500)
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.RODANDO)))
        delay(500)
        t.sessoes.persistirPisoDeCusto(id, BigDecimal("0.1"))
        val lidas = emissoes.await()
        assertEquals(listOf(Estados.NA_FILA, Estados.RODANDO, Estados.RODANDO), lidas.map { it!!.status })
        assertEquals(BigDecimal("0.1"), lidas.last()!!.custoObservadoUsd)
        assertNotNull(ProjecaoDaSessao.de(lidas.last()!!).eventos.firstOrNull())
    }
}
