package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** As transições da sessão como `UPDATE` condicionais, sobre Room em arquivo: cada portão tem o caso em que recusa. */
@RunWith(AndroidJUnit4::class)
class RepositorioDeSessoesTest {

    private val t = BancoDeTeste()

    @After
    fun fechar() = t.fechar()

    private fun criar(): SessaoEntidade = t.sessoes.criar(t.entrada())

    @Test
    fun criarGravaALinhaEOPrimeiroEventoJuntos() {
        val sessao = criar()
        assertTrue(sessao.id.startsWith("android-"))
        val lida = t.sessoes.carregar(sessao.id)!!
        assertEquals(Estados.NA_FILA, lida.status)
        assertEquals("claude", lida.liderDoCiclo)
        assertNull(lida.custodiaArtefatoId)
        assertNull(lida.execucaoAtual)
        assertEquals(0L, lida.custoObservadoE8)
        assertEquals(500_000_000L, lida.tetoDeCustoE8)
        assertEquals(listOf("Maestro AI Android session queued."), t.mensagens(sessao.id))
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI), RepositorioDeSessoes.lerAgentes(lida.agentesAtivosJson))
    }

    @Test
    fun anotarGravaSoSobOPortaoEACerca() {
        val id = criar().id
        val antes = t.sessoes.carregar(id)!!
        assertTrue(t.sessoes.anotar(id, t.evento(EventoDaSessao.RODANDO, "vai")))
        val depois = t.sessoes.carregar(id)!!
        assertTrue(depois.atualizadaEm > antes.atualizadaEm)
        assertEquals(listOf("Maestro AI Android session queued.", "vai"), t.mensagens(id))
        // A cerca: sem execução reivindicada, uma escrita fenceada não passa.
        assertFalse(t.sessoes.anotar(id, t.evento(EventoDaSessao.RODANDO, "de outra execucao"), execucao = 99))
        // O portão: fora de queued/running, nada.
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertFalse(t.sessoes.anotar(id, t.evento(EventoDaSessao.PRONTO, "turno atrasado")))
        assertEquals(listOf("Maestro AI Android session queued.", "vai", "Sessao cancelada pelo operador."), t.mensagens(id))
    }

    @Test
    fun transicionarRecusaQuandoOStatusNaoEstaNoPortao() {
        val id = criar().id
        assertTrue(t.sessoes.transicionar(id, "paused_cost_limit", "teto", t.evento(EventoDaSessao.BLOQUEADO, "teto")))
        val antes = t.sessoes.carregar(id)!!
        assertFalse(t.sessoes.transicionar(id, Estados.RODANDO, null, t.evento(EventoDaSessao.RODANDO, "tarde")))
        assertEquals(antes, t.sessoes.carregar(id))
        assertEquals(2, t.sessoes.eventos(id).size)
        assertTrue(t.sessoes.transicionar(id, Estados.RODANDO, null, null, seSituacaoEm = setOf("paused_cost_limit")))
        assertEquals(Estados.RODANDO, t.sessoes.carregar(id)!!.status)
        assertNull(t.sessoes.carregar(id)!!.erro)
    }

    @Test
    fun eventoAtrasadoDoRunnerNaoSobrescreveOCancelamento() {
        val id = criar().id
        t.sessoes.transicionar(id, Estados.RODANDO, null, null)
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertFalse(t.sessoes.transicionar(id, "paused_cost_limit", "teto", t.evento(EventoDaSessao.BLOQUEADO, "teto")))
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.CANCELADA, linha.status)
        assertEquals("Sessao cancelada pelo operador.", linha.erro)
        assertEquals("Sessao cancelada pelo operador.", t.mensagens(id).last())
    }

    @Test
    fun cancelarRecusaSessaoJaTerminal() {
        val id = criar().id
        t.sessoes.transicionar(id, "finished", null, null)
        assertEquals("Sessao ja finalizada; nada a cancelar.", (t.sessoes.cancelar(id) as Resultado.Recusado).mensagem)
        assertEquals("Sessao Maestro AI nao encontrada.", (t.sessoes.cancelar("android-nao-existe") as Resultado.Recusado).mensagem)
    }

    @Test
    fun custoObservadoSomaCadaChamadaSemPortaoESatura() {
        val id = criar().id
        assertEquals(BigDecimal("0.50000000"), t.sessoes.somarCusto(id, BigDecimal("0.50")))
        // Cada chamada paga soma (achado do Codex na #70: um `MAX` sobre totais locais perdia a chamada de uma execução cruzada).
        assertEquals(BigDecimal("0.75000000"), t.sessoes.somarCusto(id, BigDecimal("0.25")))
        // Um custo negativo conta zero.
        assertEquals(BigDecimal("0.75000000"), t.sessoes.somarCusto(id, BigDecimal("-3")))
        assertEquals(BigDecimal("0.75000000"), t.sessoes.custoObservado(id))
        // Sem portão: registra gasto mesmo com a sessão cancelada.
        t.sessoes.cancelar(id)
        assertEquals(BigDecimal("11.75000000"), t.sessoes.somarCusto(id, BigDecimal("11")))
        // Acima da coluna satura no extremo, sem lançar: o gasto fica registrado e pausa pelo teto.
        t.sessoes.somarCusto(id, BigDecimal("1e30"))
        assertEquals(Long.MAX_VALUE, t.sessoes.carregar(id)!!.custoObservadoE8)
        t.sessoes.somarCusto(id, BigDecimal("1"))
        assertEquals(Long.MAX_VALUE, t.sessoes.carregar(id)!!.custoObservadoE8)
    }

    @Test
    fun substituirConteudoPreservaAsColunasOmitidasESoEscreveNoStatusLido() {
        val id = criar().id
        val ativa = t.sessoes.substituirConteudo(id, null, "x") as Resultado.Recusado
        assertEquals("Sessao em execucao; aguarde terminar ou cancele antes de editar o conteudo.", ativa.mensagem)
        t.sessoes.transicionar(id, "paused_cost_limit", "teto", null)
        t.banco.sessoes().substituirConteudo(id, "paused_cost_limit", null, "texto", FormatoDeInstante.iso(t.relogio()))
        val retomavel = t.sessoes.substituirConteudo(id, "Novo título", "outro") as Resultado.Recusado
        assertTrue(retomavel.mensagem.startsWith("Conteudo de uma sessao retomavel nao pode ser substituido fora da cadeia de custodia."))
        // Só o título: o texto fica como está, dentro do SQL.
        val soTitulo = t.sessoes.substituirConteudo(id, "Novo título", null) as Resultado.Ok
        assertEquals("Novo título", soTitulo.valor.titulo)
        assertEquals("texto", soTitulo.valor.textoAtual)
        t.sessoes.transicionar(id, "finished", null, null, seSituacaoEm = setOf("paused_cost_limit"))
        t.banco.sessoes().concluir(id, listOf("finished"), 0, "fim", "finished", FormatoDeInstante.iso(t.relogio()))
        val trocado = t.sessoes.substituirConteudo(id, null, "  editado  ") as Resultado.Ok
        assertEquals("editado", trocado.valor.textoAtual)
        assertEquals("Novo título", trocado.valor.titulo)
        // O status mudou entre a leitura e a escrita: a escrita condicional não toca a linha.
        assertEquals(0, t.banco.sessoes().substituirConteudo(id, "paused_cost_limit", "Outro", "trocado", FormatoDeInstante.iso(t.relogio())))
        assertEquals("editado", t.sessoes.carregar(id)!!.textoAtual)
        assertEquals("Sessao mudou de estado durante a edicao; recarregue antes de editar.", RepositorioDeSessoes.MENSAGEM_MUDOU_NA_EDICAO)
    }

    @Test
    fun marcarInterrompidaDeixaASessaoRetomavel() {
        val id = criar().id
        assertTrue(t.sessoes.marcarInterrompida(id, null, t.evento(EventoDaSessao.ERRO, "processo morreu")))
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.ERRO, linha.status)
        assertEquals(RepositorioDeSessoes.MENSAGEM_INTERROMPIDA, linha.erro)
        assertTrue(linha.status in Estados.RETOMAVEIS)
        assertFalse(t.sessoes.marcarInterrompida(id, null))
        assertEquals(0, t.sessoes.emExecucao().size)
        assertEquals("processo morreu", t.mensagens(id).last())
    }

    @Test
    fun execucoesQueTocamAJanelaContam() {
        val id = criar().id
        val dao = t.banco.execucoes()
        dao.inserir(ExecucaoEntidade(sessaoId = id, inicio = "2026-09-25T00:00:00.000Z", fim = "2026-09-25T01:00:00.000Z"))
        val atravessa = dao.inserir(ExecucaoEntidade(sessaoId = id, inicio = "2026-09-25T09:00:00.000Z", fim = "2026-09-25T13:00:00.000Z"))
        val aberta = dao.inserir(ExecucaoEntidade(sessaoId = id, inicio = "2026-09-25T08:00:00.000Z", fim = null))
        val dentro = dao.inserir(ExecucaoEntidade(sessaoId = id, inicio = "2026-09-25T15:00:00.000Z", fim = "2026-09-25T16:00:00.000Z"))
        assertEquals(listOf(aberta, atravessa, dentro), dao.naJanela("2026-09-25T12:00:00.000Z").map { it.seq })
    }

    @Test
    fun observarEmiteACadaEscritaEOsEventosTambem() = runBlocking {
        val id = criar().id
        val linhas = async(Dispatchers.IO) { withTimeout(10_000) { t.sessoes.observar(id).take(3).toList() } }
        val eventos = async(Dispatchers.IO) { withTimeout(10_000) { t.sessoes.observarEventos(id).take(2).toList() } }
        delay(500)
        t.sessoes.transicionar(id, Estados.RODANDO, null, t.evento(EventoDaSessao.RODANDO, "vai"))
        delay(500)
        t.sessoes.somarCusto(id, BigDecimal("0.1"))
        val lidas = linhas.await()
        assertEquals(listOf(Estados.NA_FILA, Estados.RODANDO, Estados.RODANDO), lidas.map { it!!.status })
        assertEquals(10_000_000L, lidas.last()!!.custoObservadoE8)
        assertEquals(listOf(1, 2), eventos.await().map { it.size })
        val projecao = ProjecaoDaSessao.de(lidas.last()!!, t.banco.eventos().daSessao(id))
        assertEquals(BigDecimal("0.10000000"), projecao.custoObservadoUsd)
        assertEquals(listOf("Maestro AI Android session queued.", "vai"), projecao.eventos.map { it.mensagem })
    }

    private fun recusa(resultado: Resultado<*>): String = (resultado as Resultado.Recusado).mensagem

    @Test
    fun subirTetoSoAcimaDoTetoEDoObservadoNumaSessaoRetomavel() {
        val id = criar().id
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_SO_RETOMAVEL, recusa(t.sessoes.subirTeto(id, BigDecimal("6"))))
        t.sessoes.somarCusto(id, BigDecimal("5.5"))
        assertTrue(t.sessoes.transicionar(id, Estados.LIMITE_DE_CUSTO, "teto", t.evento(EventoDaSessao.BLOQUEADO, "teto")))

        assertEquals("Teto financeiro em USD deve ser positivo.", recusa(t.sessoes.subirTeto(id, BigDecimal.ZERO)))
        assertEquals(RepositorioDeConfiguracoes.MENSAGEM_TETO_ACIMA_DO_MAXIMO, recusa(t.sessoes.subirTeto(id, BigDecimal("92233720368.54775808"))))
        // Igual ao teto atual, igual ao observado, e entre os dois: os três recusados.
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_NAO_SOBE, recusa(t.sessoes.subirTeto(id, BigDecimal("5"))))
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_NAO_SOBE, recusa(t.sessoes.subirTeto(id, BigDecimal("5.5"))))
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_NAO_SOBE, recusa(t.sessoes.subirTeto(id, BigDecimal("5.2"))))
        assertEquals(500_000_000L, t.sessoes.carregar(id)!!.tetoDeCustoE8)
        // O mesmo portão no SQL, sem a conferência do repositório na frente: igual ao observado não sobe.
        assertEquals(0, t.banco.sessoes().subirTeto(id, Estados.RETOMAVEIS.toList(), 550_000_000L, "2026-09-28T00:00:00Z"))

        val elevada = (t.sessoes.subirTeto(id, BigDecimal("6")) as Resultado.Ok).valor
        assertEquals(600_000_000L, elevada.tetoDeCustoE8)
        assertEquals(Estados.LIMITE_DE_CUSTO, elevada.status)
        assertEquals("Teto financeiro da sessao elevado para US$ 6.00 pelo operador.", t.mensagens(id).last())
        // E igual ao teto atual, acima do observado, também não sobe.
        assertEquals(0, t.banco.sessoes().subirTeto(id, Estados.RETOMAVEIS.toList(), 600_000_000L, "2026-09-28T00:00:00Z"))

        // O portão está no SQL: uma linha que voltou a rodar entre a leitura e a escrita não muda.
        assertTrue(t.sessoes.transicionar(id, Estados.RODANDO, null, null, seSituacaoEm = setOf(Estados.LIMITE_DE_CUSTO)))
        assertEquals(0, t.banco.sessoes().subirTeto(id, Estados.RETOMAVEIS.toList(), 700_000_000L, "2026-09-28T00:00:00Z"))
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_SO_RETOMAVEL, recusa(t.sessoes.subirTeto(id, BigDecimal("7"))))
        assertEquals(600_000_000L, t.sessoes.carregar(id)!!.tetoDeCustoE8)
    }

    @Test
    fun observarTodasTrazPrimeiroATocadaPorUltimoEAsTrintaMaisRecentes() = runBlocking {
        val antiga = criar().id
        val nova = criar().id
        assertEquals(listOf(nova, antiga), t.sessoes.observarTodas().first().map { it.id })
        // Um evento na mais antiga a leva ao topo, como o `ORDER BY updated_at DESC` do web.
        assertTrue(t.sessoes.anotar(antiga, t.evento(EventoDaSessao.RODANDO, "tocada")))
        assertEquals(listOf(antiga, nova), t.sessoes.observarTodas().first().map { it.id })
        // O corte do web: trinta.
        repeat(29) { criar() }
        val lista = t.sessoes.observarTodas().first()
        assertEquals(30, lista.size)
        assertFalse(lista.any { it.id == nova })
    }

    @Test
    fun subirTetoRecusaReduzirMesmoAcimaDoObservado() {
        val id = criar().id
        t.sessoes.somarCusto(id, BigDecimal("2"))
        assertTrue(t.sessoes.transicionar(id, Estados.LIMITE_DE_CUSTO, "teto", t.evento(EventoDaSessao.BLOQUEADO, "teto")))
        assertEquals(RepositorioDeSessoes.MENSAGEM_TETO_NAO_SOBE, recusa(t.sessoes.subirTeto(id, BigDecimal("4"))))
        assertEquals(500_000_000L, t.sessoes.carregar(id)!!.tetoDeCustoE8)
    }

    @Test
    fun subirTetoQuePerdeACorridaNoSqlNaoMudaNadaNemAnota() {
        val id = criar().id
        assertTrue(t.sessoes.transicionar(id, Estados.LIMITE_DE_CUSTO, "teto", t.evento(EventoDaSessao.BLOQUEADO, "teto")))
        val jornal = t.mensagens(id)
        // A sessão volta a rodar entre a leitura do repositório e a escrita: o relógio do
        // evento é lido nesse intervalo, e é nele que a corrida é injetada.
        var corrida: (() -> Unit)? = {
            assertEquals(1, t.banco.sessoes().mudarStatus(id, listOf(Estados.LIMITE_DE_CUSTO), Estados.RODANDO, null, "2026-09-28T00:00:00.000Z", null))
        }
        val repositorio = RepositorioDeSessoes(t.banco) {
            corrida?.invoke()
            corrida = null
            t.relogio()
        }
        assertEquals(Retomada.MENSAGEM_MUDOU_DE_ESTADO, recusa(repositorio.subirTeto(id, BigDecimal("7"))))
        assertEquals(500_000_000L, t.sessoes.carregar(id)!!.tetoDeCustoE8)
        assertEquals(jornal, t.mensagens(id))
    }

    @Test
    fun observarTodasEmiteAListaAoCriarOutraSessao() = runBlocking {
        val primeira = criar().id
        val listas = async(Dispatchers.IO) { withTimeout(10_000) { t.sessoes.observarTodas().take(2).toList() } }
        delay(500)
        val segunda = criar().id
        val lidas = listas.await()
        assertEquals(listOf(primeira), lidas[0].map { it.id })
        // A tocada por último primeiro (`atualizadaEm DESC`; o relógio de teste avança a cada leitura).
        assertEquals(listOf(segunda, primeira), lidas[1].map { it.id })
    }
}
