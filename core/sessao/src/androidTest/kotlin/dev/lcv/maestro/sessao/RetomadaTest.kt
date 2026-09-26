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
 * O checkpoint por turno e a retomada depois de o processo morrer: o banco é
 * fechado, cada objeto descartado e tudo reaberto sobre o mesmo arquivo.
 */
@RunWith(AndroidJUnit4::class)
class RetomadaTest {

    private val t = BancoDeTeste()
    private val escala = listOf(Provedor.CODEX, Provedor.GEMINI, Provedor.CLAUDE)
    private val textoDoRascunho = "Rascunho do Claude."
    private val textoRevisado = "Texto revisado pelo Gemini.\n\nCom dois parágrafos."

    @After
    fun fechar() = t.fechar()

    private fun eventos(id: String) = Jornal.lerEstrito(t.sessoes.carregar(id)!!.eventosJson)

    /** Rascunho aceito, revisão `ready` do Codex, revisão `not_ready` do Gemini que assume a custódia; pausa por custo. */
    private fun sessaoNoMeioDaRodada(): Triple<String, ArtefatoEntidade, ArtefatoEntidade> {
        val id = t.sessoes.criar(t.entrada()).id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.RODANDO)), seSituacaoEm = Estados.ATIVOS)
        val rascunho = (t.ponto.gravarTurno(
            id, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> ProgressoCircular(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, escala, emptySet(), emptySet(), 1) },
            evento = t.evento(EventoDaSessao.PRONTO, "rascunho", Provedor.CLAUDE),
        ) as Gravacao.Gravada).artefato!!
        val aprovacao = (t.ponto.gravarTurno(
            id, t.artefato(id, 2, Provedor.CODEX, status = "ready", texto = textoDoRascunho, anteriorId = rascunho.id),
            { _ -> ProgressoCircular(Provedor.CLAUDE, textoDoRascunho, rascunho.id, rascunho.id, 1, 1, escala, setOf(Provedor.CODEX), setOf(Provedor.CODEX), 2) },
            evento = t.evento(EventoDaSessao.PRONTO, "codex aprovou", Provedor.CODEX),
        ) as Gravacao.Gravada).artefato!!
        val revisao = (t.ponto.gravarTurno(
            id, t.artefato(id, 3, Provedor.GEMINI, status = "not_ready", texto = textoRevisado, anteriorId = aprovacao.id),
            { a -> ProgressoCircular(Provedor.GEMINI, textoRevisado, a!!.id, aprovacao.id, 1, 2, escala, setOf(Provedor.CODEX, Provedor.GEMINI), emptySet(), 3) },
            Remendo(status = Campo.Presente("paused_cost_limit"), erro = Campo.Presente("teto")),
            evento = t.evento(EventoDaSessao.NAO_PRONTO, "gemini reescreveu", Provedor.GEMINI),
        ) as Gravacao.Gravada).artefato!!
        return Triple(id, aprovacao, revisao)
    }

    @Test
    fun checkpointGravaArtefatoCustodiaEJornalJuntos() {
        val (id, aprovacao, revisao) = sessaoNoMeioDaRodada()
        val linha = t.sessoes.carregar(id)!!
        assertEquals("paused_cost_limit", linha.status)
        assertEquals("gemini", linha.autorAtual)
        assertEquals(textoRevisado, linha.textoAtual)
        val estado = EstadoCircular.validar(linha, EstadoCircular.ler(linha.estadoCircularJson)!!) { t.artefatos.um(id, it) }
        assertEquals(revisao.id, estado.artefatoDeCustodiaId)
        assertEquals(aprovacao.id, estado.artefatoAnteriorId)
        assertEquals(3, t.artefatos.daSessao(id).size)
        assertEquals(listOf("rascunho", "codex aprovou", "gemini reescreveu"), eventos(id).drop(1).map { it.mensagem })
    }

    @Test
    fun checkpointPerdidoNaoDeixaArtefatoNemEvento() {
        val id = t.sessoes.criar(t.entrada()).id
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        val antes = t.sessoes.carregar(id)!!
        val gravacao = t.ponto.gravarTurno(
            id, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> ProgressoCircular(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, escala, emptySet(), emptySet(), 1) },
            evento = t.evento(EventoDaSessao.PRONTO, "rascunho tardio", Provedor.CLAUDE),
        )
        assertEquals(Gravacao.Perdida, gravacao)
        assertEquals(0, t.artefatos.daSessao(id).size)
        assertEquals(antes, t.sessoes.carregar(id))
    }

    @Test
    fun falhaDepoisDoInsertDesfazOArtefato() {
        val id = t.sessoes.criar(t.entrada()).id
        try {
            t.ponto.gravarTurno(id, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho), { error("morreu antes do commit") })
            fail("devia propagar")
        } catch (erro: IllegalStateException) {
            assertEquals("morreu antes do commit", erro.message)
        }
        assertEquals(0, t.artefatos.daSessao(id).size)
        assertEquals(Estados.NA_FILA, t.sessoes.carregar(id)!!.status)
    }

    @Test
    fun persistirProgressoSemCustodiaAceitaELancaAMensagemDoWeb() {
        val id = t.sessoes.criar(t.entrada()).id
        try {
            t.retomada.persistirProgressoCircular(id, ProgressoCircular(Provedor.CLAUDE, "x", null, null, 1, 0, escala, emptySet(), emptySet(), 1))
            fail("devia lançar")
        } catch (erro: IllegalStateException) {
            assertEquals("Cannot persist circular progress without accepted draft custody.", erro.message)
        }
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
        assertNull(preparacao.sessao.erro)
        val mensagens = eventos(id).map { it.mensagem }
        assertTrue(mensagens[mensagens.size - 2].startsWith("Sessao retomada pelo operador com Claude como lider do ciclo."))
        assertEquals(Retomada.MENSAGEM_RETOMADA, mensagens.last())
        assertEquals(preparacao.eventos.map { it.mensagem }, mensagens)
        // A âncora do tempo é o agora da retomada, não `criadaEm`.
        assertTrue(preparacao.ancora > FormatoDeInstante.ler(preparacao.sessao.criadaEm)!!)
    }

    @Test
    fun execucaoNovaAncoraOTempoEmCriadaEm() {
        val id = t.sessoes.criar(t.entrada(tetoDeMinutos = 30)).id
        val preparacao = t.retomada.preparar(id) as Preparacao.Nova
        assertEquals(FormatoDeInstante.ler(preparacao.sessao.criadaEm), preparacao.ancora)
        assertEquals(Provedor.CLAUDE, preparacao.lider)
        assertEquals(escala, preparacao.escala)
        assertEquals(1, preparacao.eventos.size)
    }

    @Test
    fun execucaoNovaCanceladaAntesDoPreparoNaoComeca() {
        val id = t.sessoes.criar(t.entrada()).id
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertEquals(Preparacao.Perdida, t.retomada.preparar(id))
        assertEquals(Estados.CANCELADA, t.sessoes.carregar(id)!!.status)
        val outra = t.sessoes.criar(t.entrada()).id
        assertTrue(t.sessoes.marcarInterrompida(outra))
        assertEquals(Preparacao.Perdida, t.retomada.preparar(outra))
    }

    @Test
    fun estadoCircularAdulteradoNoArquivoFalhaFechadoComOEventoBloqueado() {
        val (id, _, _) = sessaoNoMeioDaRodada()
        t.reabrir()
        val linha = t.sessoes.carregar(id)!!
        val adulterado = linha.estadoCircularJson.replace("\"round\":1", "\"round\":0")
        assertTrue(adulterado != linha.estadoCircularJson)
        t.sessoes.persistir(id, Remendo(estadoCircularJson = Campo.Presente(adulterado)))
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        val preparacao = t.retomada.preparar(id) as Preparacao.CustodiaInvalida
        assertEquals("Circular custody integrity check failed: Circular custody progress contains invalid counters or artifact references.", preparacao.mensagem)
        val depois = t.sessoes.carregar(id)!!
        assertEquals(Estados.RETOMADA_INVALIDA, depois.status)
        assertEquals(preparacao.mensagem, depois.erro)
        val ultimo = eventos(id).last()
        assertEquals(EventoDaSessao.BLOQUEADO, ultimo.status)
        assertEquals(Provedor.GEMINI, ultimo.agente)
        assertEquals("draft", ultimo.papel)
        assertEquals(preparacao.mensagem, ultimo.mensagem)
        assertEquals(3, t.artefatos.daSessao(id).size)
    }

    @Test
    fun jornalMalformadoPausaSemAnexarEvento() {
        for (jornal in listOf("[{\"at\":1}]", "{}", "[")) {
            val id = t.sessoes.criar(t.entrada()).id
            val linha = t.sessoes.carregar(id)!!
            t.banco.sessoes().atualizar(linha.copy(eventosJson = jornal))
            val preparacao = t.retomada.preparar(id) as Preparacao.JornalInvalido
            assertTrue(preparacao.mensagem, preparacao.mensagem.startsWith("Session journal integrity check failed: Session event journal "))
            val depois = t.sessoes.carregar(id)!!
            assertEquals(Estados.RETOMADA_INVALIDA, depois.status)
            assertEquals(preparacao.mensagem, depois.erro)
            assertEquals(jornal, depois.eventosJson)
        }
    }

    @Test
    fun artefatoOrfaoAlemDoContadorReservaOTurnoSemVirarCustodia() {
        val (id, _, revisao) = sessaoNoMeioDaRodada()
        t.artefatos.criar(t.artefato(id, 5, Provedor.CLAUDE, status = "running", texto = "perdido"))
        t.reabrir()
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.Retomar
        assertEquals(5, preparacao.progresso.turnoDoArtefato)
        assertEquals(revisao.id, preparacao.progresso.artefatoDeCustodiaId)
    }

    @Test
    fun legadoQueContradizALinhaFalhaFechado() {
        val id = t.sessoes.criar(t.entrada()).id
        t.artefatos.criar(t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = "Outro texto."))
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("paused_cost_limit"), autorAtual = Campo.Presente("claude"), textoAtual = Campo.Presente("Texto que não bate.")))
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.CustodiaInvalida
        assertEquals("Circular custody integrity check failed: Legacy circular custody cannot be reconstructed from the existing artifacts.", preparacao.mensagem)
        assertEquals(1, t.artefatos.daSessao(id).size)
    }

    @Test
    fun legadoSemArtefatosCriaARecuperacaoNaMesmaTransacaoDaRetomada() {
        val id = t.sessoes.criar(t.entrada()).id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("paused_cost_limit"), autorAtual = Campo.Presente("codex"), textoAtual = Campo.Presente("Texto legado.")))
        t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES)
        val preparacao = t.retomada.preparar(id) as Preparacao.Retomar
        val criado = t.artefatos.daSessao(id).single()
        assertEquals(criado.id, preparacao.progresso.artefatoDeCustodiaId)
        assertEquals(0, criado.ciclo)
        assertEquals("codex", criado.agente)
        assertTrue(criado.relatorioDeRevisaoJson.contains("legacy_custody_recovery"))
        assertNotNull(EstadoCircular.ler(t.sessoes.carregar(id)!!.estadoCircularJson))
    }

    @Test
    fun pedirRecusaSessaoAtivaEConcluida() {
        val id = t.sessoes.criar(t.entrada()).id
        assertEquals("Sessao ainda ativa; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("finished"), textoFinal = Campo.Presente("fim")))
        assertEquals("Sessao concluida; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        t.sessoes.persistir(id, Remendo(status = Campo.Presente("paused_cost_limit")))
        assertEquals("Sessao concluida; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        assertEquals("Sessao Maestro AI nao encontrada.", (t.retomada.pedir("android-x", null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
    }

    @Test
    fun pedirValidaOPainelEOLider() {
        val id = t.sessoes.criar(t.entrada()).id
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.CANCELADA)))
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
        t.sessoes.persistir(id, Remendo(status = Campo.Presente(Estados.AGUARDANDO_AUTENTICACAO)))
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        assertEquals("Sessao ainda ativa; nada a retomar.", (t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) as Resultado.Recusado).mensagem)
        assertEquals(1, eventos(id).count { it.mensagem.startsWith("Sessao retomada pelo operador") })
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
        assertFalse(t.sessoes.carregar(id)!!.estadoCircularJson.contains("\"claude\""))
    }
}
