package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.provedores.Provedor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A reivindicação, o checkpoint por turno e a retomada depois de o processo
 * morrer: o banco é fechado, cada objeto descartado e tudo reaberto sobre o
 * mesmo arquivo.
 */
@RunWith(AndroidJUnit4::class)
class RetomadaTest {

    private val t = BancoDeTeste()
    private val escala = listOf(Provedor.CODEX, Provedor.GEMINI, Provedor.CLAUDE)
    private val textoDoRascunho = "Rascunho do Claude."
    private val textoRevisado = "Texto revisado pelo Gemini.\n\nCom dois parágrafos."

    @After
    fun fechar() = t.fechar()

    private fun custodia(
        autor: Provedor, texto: String, custodiaId: String, anteriorId: String, rodada: Int, indice: Int,
        validos: Set<Provedor>, estaveis: Set<Provedor>, turno: Int,
    ) = Custodia(autor, texto, custodiaId, anteriorId, rodada, indice, escala, validos, estaveis, turno)

    /** Reivindica uma sessão nova e devolve a execução. */
    private fun reivindicar(id: String): Long = (t.retomada.preparar(id) as Preparacao.Nova).execucao

    /** Rascunho aceito, revisão `ready` do Codex, revisão `not_ready` do Gemini que assume a custódia; pausa por custo. */
    private fun sessaoNoMeioDaRodada(): Triple<String, ArtefatoEntidade, ArtefatoEntidade> {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val rascunho = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
            evento = t.evento(EventoDaSessao.PRONTO, "rascunho", Provedor.CLAUDE),
        ) as Gravacao.Gravada).artefato!!
        val aprovacao = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 2, Provedor.CODEX, status = "ready", texto = textoDoRascunho, anteriorId = rascunho.id),
            { _ -> custodia(Provedor.CLAUDE, textoDoRascunho, rascunho.id, rascunho.id, 1, 1, setOf(Provedor.CODEX), setOf(Provedor.CODEX), 2) },
            evento = t.evento(EventoDaSessao.PRONTO, "codex aprovou", Provedor.CODEX),
        ) as Gravacao.Gravada).artefato!!
        val revisao = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 3, Provedor.GEMINI, status = "not_ready", texto = textoRevisado, anteriorId = aprovacao.id),
            { a -> custodia(Provedor.GEMINI, textoRevisado, a!!.id, aprovacao.id, 1, 2, setOf(Provedor.CODEX, Provedor.GEMINI), emptySet(), 3) },
            status = "paused_cost_limit", erro = "teto",
            evento = t.evento(EventoDaSessao.NAO_PRONTO, "gemini reescreveu", Provedor.GEMINI),
        ) as Gravacao.Gravada).artefato!!
        return Triple(id, aprovacao, revisao)
    }

    @Test
    fun prepararReivindicaASessaoNumaExecucaoNova() {
        val id = t.sessoes.criar(t.entrada(tetoDeMinutos = 30)).id
        val preparacao = t.retomada.preparar(id) as Preparacao.Nova
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, linha.status)
        assertEquals(preparacao.execucao, linha.execucaoAtual)
        assertNotNull(t.banco.execucoes().uma(preparacao.execucao))
        assertEquals(FormatoDeInstante.ler(linha.criadaEm), preparacao.ancora)
        assertEquals(Provedor.CLAUDE, preparacao.lider)
        assertEquals(escala, preparacao.escala)
        assertEquals(1, preparacao.eventos.size)
        // Uma segunda execução reivindica de novo (a primeira morreu): a antiga perde a cerca.
        val segunda = t.retomada.preparar(id) as Preparacao.Nova
        assertTrue(segunda.execucao > preparacao.execucao)
        assertFalse(t.sessoes.anotar(id, t.evento(EventoDaSessao.RODANDO, "tardio"), execucao = preparacao.execucao))
        assertTrue(t.sessoes.anotar(id, t.evento(EventoDaSessao.RODANDO, "atual"), execucao = segunda.execucao))
    }

    @Test
    fun sessaoCanceladaOuReconciliadaNaoEReivindicada() {
        val id = t.sessoes.criar(t.entrada()).id
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertEquals(Preparacao.Perdida, t.retomada.preparar(id))
        assertEquals(Estados.CANCELADA, t.sessoes.carregar(id)!!.status)
        assertNull(t.sessoes.carregar(id)!!.execucaoAtual)
        val outra = t.sessoes.criar(t.entrada()).id
        assertTrue(t.sessoes.marcarInterrompida(outra))
        assertEquals(Preparacao.Perdida, t.retomada.preparar(outra))
        assertEquals(0, t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").size)
    }

    @Test
    fun checkpointGravaArtefatoCustodiaEEventoJuntos() {
        val (id, aprovacao, revisao) = sessaoNoMeioDaRodada()
        val linha = t.sessoes.carregar(id)!!
        assertEquals("paused_cost_limit", linha.status)
        assertEquals("teto", linha.erro)
        assertEquals("gemini", linha.autorAtual)
        assertEquals(textoRevisado, linha.textoAtual)
        assertEquals(revisao.id, linha.custodiaArtefatoId)
        assertEquals(aprovacao.id, linha.artefatoAnteriorId)
        assertEquals(1, linha.rodada)
        assertEquals(2, linha.indiceDoTurno)
        assertEquals(3, linha.turnoDoArtefato)
        assertEquals(listOf(Provedor.CODEX, Provedor.GEMINI), EstadoCircular.lerAgentes(linha.agentesValidosJson))
        assertEquals(3, t.artefatos.daSessao(id).size)
        assertEquals(textoRevisado, revisao.textoAceito)
        assertEquals(listOf("rascunho", "codex aprovou", "gemini reescreveu"), t.mensagens(id).drop(1))
    }

    @Test
    fun checkpointPerdidoNaoDeixaArtefatoNemEvento() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        val antes = t.sessoes.carregar(id)!!
        val gravacao = t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
            evento = t.evento(EventoDaSessao.PRONTO, "rascunho tardio", Provedor.CLAUDE),
        )
        assertEquals(Gravacao.Perdida, gravacao)
        assertEquals(0, t.artefatos.daSessao(id).size)
        assertEquals(antes, t.sessoes.carregar(id))
    }

    @Test
    fun checkpointDeExecucaoSuperadaFalhaNaCerca() {
        val id = t.sessoes.criar(t.entrada()).id
        val antiga = reivindicar(id)
        val nova = reivindicar(id)
        assertTrue(nova > antiga)
        val gravacao = t.ponto.gravarTurno(
            id, antiga, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
            evento = t.evento(EventoDaSessao.PRONTO, "da execucao morta", Provedor.CLAUDE),
        )
        assertEquals(Gravacao.Perdida, gravacao)
        assertEquals(0, t.artefatos.daSessao(id).size)
        assertEquals(Estados.RODANDO, t.sessoes.carregar(id)!!.status)
        assertNull(t.sessoes.carregar(id)!!.custodiaArtefatoId)
        assertTrue(t.ponto.gravarTurno(
            id, nova, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) is Gravacao.Gravada)
    }

    @Test
    fun falhaDepoisDoInsertDesfazOArtefato() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        try {
            t.ponto.gravarTurno(id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho), { error("morreu antes do commit") })
            fail("devia propagar")
        } catch (erro: IllegalStateException) {
            assertEquals("morreu antes do commit", erro.message)
        }
        assertEquals(0, t.artefatos.daSessao(id).size)
        assertNull(t.sessoes.carregar(id)!!.custodiaArtefatoId)
    }

    @Test
    fun textoComNulERecusadoNoCheckpoint() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        try {
            t.ponto.gravarTurno(id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = "a\u0000b"), { a -> custodia(Provedor.CLAUDE, "a\u0000b", a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) })
            fail("devia recusar")
        } catch (erro: dev.lcv.maestro.protocolo.IntegridadeDeLinks.Falha) {
            assertEquals("Accepted text contains a NUL character.", erro.message)
        }
        assertEquals(0, t.artefatos.daSessao(id).size)
    }

    @Test
    fun retomaDoTurnoExatoDepoisDeDescartarEReabrirOBanco() {
        val (id, aprovacao, revisao) = sessaoNoMeioDaRodada()
        t.reabrir()
        val pedido = t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        assertEquals(Estados.NA_FILA, (pedido as Resultado.Ok).valor.status)
        val preparacao = t.retomada.preparar(id) as Preparacao.Retomar
        assertEquals(1, preparacao.progresso.rodada)
        assertEquals(2, preparacao.progresso.indiceDoTurno)
        assertEquals(setOf(Provedor.CODEX, Provedor.GEMINI), preparacao.progresso.agentesValidos)
        assertEquals(emptySet<Provedor>(), preparacao.progresso.aprovacoesEstaveis)
        assertEquals(revisao.id, preparacao.progresso.artefatoDeCustodiaId)
        assertEquals(aprovacao.id, preparacao.progresso.artefatoAnteriorId)
        assertEquals(3, preparacao.progresso.turnoDoArtefato)
        assertEquals(listOf(aprovacao.id, revisao.id), preparacao.progresso.relatorios.map { it.artefato })
        assertEquals(Provedor.GEMINI, preparacao.autorAtual)
        assertEquals(textoRevisado, preparacao.textoAtual)
        assertEquals(escala, preparacao.escala)
        assertEquals(Provedor.CLAUDE, preparacao.lider)
        assertEquals(Estados.RODANDO, preparacao.sessao.status)
        assertEquals(preparacao.execucao, preparacao.sessao.execucaoAtual)
        assertNull(preparacao.sessao.erro)
        val mensagens = t.mensagens(id)
        assertTrue(mensagens[mensagens.size - 2].startsWith("Sessao retomada pelo operador com Claude como lider do ciclo."))
        assertEquals(Retomada.MENSAGEM_RETOMADA, mensagens.last())
        assertEquals(preparacao.eventos.map { it.mensagem }, mensagens)
        assertTrue(preparacao.ancora > FormatoDeInstante.ler(preparacao.sessao.criadaEm)!!)
    }

    @Test
    fun custodiaAdulteradaNoArquivoFalhaFechadoComOEventoBloqueado() {
        val (id, _, _) = sessaoNoMeioDaRodada()
        t.reabrir()
        t.adulterar("UPDATE sessoes SET rodada = 0 WHERE id = '$id'")
        val execucaoAnterior = t.sessoes.carregar(id)!!.execucaoAtual
        assertNotNull(execucaoAnterior)
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        val preparacao = t.retomada.preparar(id) as Preparacao.CustodiaInvalida
        assertEquals("Circular custody integrity check failed: Circular custody progress contains invalid counters or artifact references.", preparacao.mensagem)
        val depois = t.sessoes.carregar(id)!!
        assertEquals(Estados.RETOMADA_INVALIDA, depois.status)
        assertEquals(preparacao.mensagem, depois.erro)
        // A reivindicação foi desfeita com a transação: a execução que fica é a da última execução válida.
        assertEquals(execucaoAnterior, depois.execucaoAtual)
        val ultimo = t.sessoes.eventos(id).last()
        assertEquals(EventoDaSessao.BLOQUEADO, ultimo.status)
        assertEquals(Provedor.GEMINI, ultimo.agente)
        assertEquals("draft", ultimo.papel)
        assertEquals(preparacao.mensagem, ultimo.mensagem)
        assertEquals(3, t.artefatos.daSessao(id).size)
    }

    @Test
    fun textoAdulteradoNaLinhaNaoCasaComOArtefato() {
        val (id, _, _) = sessaoNoMeioDaRodada()
        t.adulterar("UPDATE sessoes SET textoAtual = 'outro' WHERE id = '$id'")
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.CustodiaInvalida
        assertEquals("Circular custody integrity check failed: Circular custody artifact text does not match the session row.", preparacao.mensagem)
    }

    @Test
    fun textoSemCustodiaNumaSessaoRetomavelFalhaFechado() {
        val id = t.sessoes.criar(t.entrada()).id
        t.adulterar("UPDATE sessoes SET status = 'paused_cost_limit', autorAtual = 'codex', textoAtual = 'Texto sem custodia.' WHERE id = '$id'")
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.CustodiaInvalida
        assertTrue(preparacao.mensagem.endsWith("Circular custody progress contains invalid counters or artifact references."))
        assertEquals(Estados.RETOMADA_INVALIDA, t.sessoes.carregar(id)!!.status)
    }

    @Test
    fun artefatoOrfaoAlemDoContadorReservaOTurnoSemVirarCustodia() {
        val (id, _, revisao) = sessaoNoMeioDaRodada()
        t.artefatos.inserir(t.artefato(id, 5, Provedor.CLAUDE, status = "running", texto = "perdido"))
        t.reabrir()
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.Retomar
        assertEquals(5, preparacao.progresso.turnoDoArtefato)
        assertEquals(revisao.id, preparacao.progresso.artefatoDeCustodiaId)
        assertEquals(5, t.sessoes.carregar(id)!!.turnoDoArtefato)
    }

    @Test
    fun pedirRecusaSessaoAtivaEConcluida() {
        val id = t.sessoes.criar(t.entrada()).id
        assertEquals("Sessao ainda ativa; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        val execucao = reivindicar(id)
        assertTrue(t.sessoes.concluir(id, execucao, "fim", "finished", null))
        assertEquals("Sessao concluida; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        assertEquals("Sessao Maestro AI nao encontrada.", (t.retomada.pedir("android-x", null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
    }

    @Test
    fun pedirValidaOPainelEOLider() {
        val id = t.sessoes.criar(t.entrada()).id
        t.sessoes.cancelar(id)
        fun recusa(lider: String?, painel: List<String>?, chaves: Map<Provedor, Boolean?> = BancoDeTeste.TODAS_AS_CHAVES) =
            (t.retomada.pedir(id, lider, painel, chaves) as Resultado.Recusado).mensagem
        assertEquals("O painel de retomada contem agente invalido ou duplicado.", recusa(null, listOf("claude", "bing")))
        assertEquals("O painel de retomada contem agente invalido ou duplicado.", recusa(null, listOf("claude", "claude")))
        assertEquals("Selecione ao menos dois agentes para retomar a revisao circular.", recusa(null, listOf("claude")))
        assertEquals("O agente lider da retomada deve pertencer ao painel selecionado.", recusa("grok", listOf("claude", "codex")))
        assertEquals("O agente lider da retomada deve pertencer ao painel selecionado.", recusa("anthropic", listOf("claude", "codex")))
        assertEquals("Agentes indisponiveis para retomada: Codex, Gemini.", recusa(null, null, BancoDeTeste.TODAS_AS_CHAVES + mapOf(Provedor.CODEX to false, Provedor.GEMINI to null)))
        val ok = t.retomada.pedir(id, "codex", listOf("gemini", "codex"), BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Ok
        assertEquals("codex", ok.valor.liderDoCiclo)
        assertEquals(listOf(Provedor.GEMINI, Provedor.CODEX), RepositorioDeSessoes.lerAgentes(ok.valor.agentesAtivosJson))
        assertNull(ok.valor.erro)
        val (lider, escalaNova) = t.retomada.escala(ok.valor)
        assertEquals(Provedor.CODEX, lider)
        assertEquals(listOf(Provedor.GEMINI, Provedor.CODEX), escalaNova)
    }

    @Test
    fun segundoPedidoDeRetomadaNaoEnfileiraDuasVezes() {
        val id = t.sessoes.criar(t.entrada()).id
        t.sessoes.transicionar(id, Estados.AGUARDANDO_AUTENTICACAO, Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO, null)
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        assertEquals("Sessao ainda ativa; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        assertEquals(1, t.mensagens(id).count { it.startsWith("Sessao retomada pelo operador") })
    }

    @Test
    fun escalaDifereQuandoOPainelMudaEAsAprovacoesSaoFiltradas() {
        val (id, _, revisao) = sessaoNoMeioDaRodada()
        t.reabrir()
        t.retomada.pedir(id, "gemini", listOf("gemini", "codex", "grok"), BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.Retomar
        assertEquals(listOf(Provedor.CODEX, Provedor.GROK, Provedor.GEMINI), preparacao.escala)
        assertEquals(0, preparacao.progresso.indiceDoTurno)
        assertEquals(emptySet<Provedor>(), preparacao.progresso.agentesValidos)
        assertEquals(revisao.id, preparacao.progresso.artefatoDeCustodiaId)
        val linha = t.sessoes.carregar(id)!!
        assertEquals(listOf(Provedor.CODEX, Provedor.GROK, Provedor.GEMINI), EstadoCircular.lerAgentes(linha.escalaJson))
        assertEquals(0, linha.indiceDoTurno)
    }
}
