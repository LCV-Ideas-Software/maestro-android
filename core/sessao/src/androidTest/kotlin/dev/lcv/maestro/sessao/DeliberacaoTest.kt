package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.PromptsDaSessao
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import dev.lcv.maestro.provedores.MAX_TOKENS_DE_SAIDA
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado as RespostaDoProvedor
import dev.lcv.maestro.provedores.Uso
import java.math.BigDecimal
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

/**
 * O `runSession` no aparelho, sobre Room em arquivo: os tetos que protegem a
 * fatura (especificação, seção 8), a retomada que descarta e reconstrói
 * tudo, o cancelamento em voo, a chamada paga sem resultado (decisão 16) e
 * os desfechos do laço serial. O chamador e a auditoria são dublês
 * combinados; nada toca provedor real.
 */
@RunWith(AndroidJUnit4::class)
class DeliberacaoTest {

    private val t = BancoDeTeste()
    private val d = DeliberacaoDeTeste(t)
    private val dois = listOf(Provedor.CLAUDE, Provedor.CODEX)
    private val tres = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)

    @After
    fun fechar() = t.fechar()

    private fun executar(id: String, deliberacao: Deliberacao = d.deliberacao()): Desfecho = runBlocking(Dispatchers.IO) { deliberacao.executar(id) }

    private fun criar(agentes: List<Provedor> = dois, teto: BigDecimal = BigDecimal("5"), tetoDeMinutos: Int? = null): String =
        t.sessoes.criar(t.entrada(agentes = agentes, tetoDeMinutos = tetoDeMinutos).copy(tetoDeCustoUsd = teto)).id

    /** A estimativa da chamada de rascunho do líder, exatamente como a deliberação a calcula. */
    private fun estimativaDoRascunho(id: String): BigDecimal {
        val linha = t.sessoes.carregar(id)!!
        val pedido = PromptsDaSessao.PedidoDaSessao(linha.titulo, linha.pedido, linha.textoAtual, linha.protocolo)
        val bloco = (CitacoesDaSessao.de(emptyList(), linha.protocolo) as Citacoes.Lidas).bloco
        return Custo.estimar(PromptsDaSessao.rascunho(pedido, id, bloco), MAX_TOKENS_DE_SAIDA.toLong(), Taxas.PADRAO.getValue(Provedor.CLAUDE))!!
    }

    // ── o caminho feliz ──────────────────────────────────────────────────

    @Test
    fun sessaoNovaRedigeRevisaEConverge() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())

        assertEquals(Desfecho.Convergida, executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.CONVERGIDA, linha.status)
        assertEquals(DeliberacaoDeTeste.TEXTO_A, linha.textoFinal)
        assertEquals("claude", linha.autorAtual)
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX), d.chamadas.map { it.first })
        assertEquals(
            listOf(
                "Maestro AI Android session queued.",
                "Draft call started.",
                "Initial draft produced.",
                "Serial revision turn started in round 1.",
                "Reviewer left custody unchanged.",
                "All eligible reviewers returned READY.",
            ),
            d.mensagens(id),
        )
        val artefatos = t.artefatos.daSessao(id)
        assertEquals(listOf("draft" to "ready", "revision" to "ready"), artefatos.map { it.papel to it.status })
        assertEquals(artefatos[0].id, artefatos[1].artefatoAnteriorId)
        // O turno sem revisão auditou o texto de custódia (memorizado na execução) e a finalização o auditou de novo, fresca.
        assertEquals(listOf(DeliberacaoDeTeste.TEXTO_A, DeliberacaoDeTeste.TEXTO_A), d.textosAuditados)
        // A execução fechou com o status; o marcador da chamada saiu com o checkpoint.
        val execucao = t.banco.execucoes().uma(linha.execucaoAtual!!)!!
        assertEquals(Estados.CONVERGIDA, execucao.motivoDaParada)
        assertNull(execucao.chamadaEmVoo)
        // O custo observado é a soma das duas chamadas, com a fonte do provedor.
        assertTrue(linha.custoObservadoE8 > 0)
        assertEquals("provider", t.banco.eventos().daSessao(id).first { it.mensagem == "Initial draft produced." }.fonteDoCusto)
        // O bloco do manifesto foi ao prompt, e o prompt de revisão diz que os links são auditados.
        assertTrue(d.chamadas[0].second.prompt.contains("vazio inicializado pelo Maestro porque nenhum manifesto foi anexado"))
        assertTrue(d.chamadas[1].second.prompt.contains("audits public links automatically"))
        assertEquals(2, d.progressos.size)
    }

    @Test
    fun revisaoTransfereACustodiaEExigeNovaRodadaDeAprovacoes() {
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.revisado(Provedor.CODEX))
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.pronto(Provedor.GEMINI))

        assertEquals(Desfecho.Convergida, executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals("codex", linha.autorAtual)
        assertEquals(DeliberacaoDeTeste.TEXTO_B, linha.textoFinal)
        // Codex reescreveu (custódia transferida), Gemini aprovou, Claude (líder) fechou o circuito.
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI, Provedor.CLAUDE), d.chamadas.map { it.first })
        assertTrue(d.mensagens(id).contains("Reviewer revised custody text."))
        val revisao = t.artefatos.daSessao(id)[1]
        assertEquals("not_ready", revisao.status)
        assertEquals(DeliberacaoDeTeste.TEXTO_B, revisao.textoAceito)
        assertEquals(revisao.id, linha.custodiaArtefatoId)
    }

    // ── os tetos que protegem a fatura (seção 8) ─────────────────────────

    @Test
    fun custoInsuficienteParaOProximoTurnoParaAntesDaChamada() {
        // O teto cobre o rascunho por um centavo e não cobre a revisão: a segunda chamada não acontece.
        val referencia = criar()
        val id = criar(teto = estimativaDoRascunho(referencia).add(BigDecimal("0.01")))
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_CUSTO), executar(id))

        assertEquals(listOf(Provedor.CLAUDE), d.chamadas.map { it.first })
        assertEquals(Estados.LIMITE_DE_CUSTO, t.sessoes.carregar(id)!!.status)
        assertTrue(d.mensagens(id).contains("Cost guard blocked provider call before Codex."))
        // O evento leva a estimativa que estourou.
        assertNotNull(t.banco.eventos().daSessao(id).last().custoE8)
    }

    @Test
    fun noLimiteExatoAChamadaAconteceEOCentavoSeguinteRecusa() {
        val referencia = criar()
        val estimativa = estimativaDoRascunho(referencia)

        val noLimite = criar(teto = estimativa)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_CUSTO), executar(noLimite))
        assertEquals(listOf(Provedor.CLAUDE), d.chamadas.map { it.first })
        assertTrue(d.mensagens(noLimite).contains("Initial draft produced."))

        d.chamadas.clear()
        val umAbaixo = criar(teto = estimativa.subtract(BigDecimal("0.00000001")))
        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_CUSTO), executar(umAbaixo))
        assertTrue(d.chamadas.isEmpty())
        assertEquals(listOf("Maestro AI Android session queued.", "Cost guard blocked draft call before Claude."), d.mensagens(umAbaixo))
    }

    @Test
    fun respostaSemUsageCaiNaEstimativaEOJornalDizQueEEstimado() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.concluida(DeliberacaoDeTeste.TEXTO_A, Uso(null, null)))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())

        assertEquals(Desfecho.Convergida, executar(id))

        val rascunho = t.banco.eventos().daSessao(id).first { it.mensagem == "Initial draft produced." }
        assertEquals("estimate", rascunho.fonteDoCusto)
        assertTrue(rascunho.custoE8!! > 0)
        assertEquals("provider", t.banco.eventos().daSessao(id).first { it.mensagem == "Reviewer left custody unchanged." }.fonteDoCusto)
    }

    @Test
    fun tetoDeTempoParaComORelogioInjetadoSemChamar() {
        val id = criar(tetoDeMinutos = 1)
        d.deslocamento = Duration.ofMinutes(2)

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_TEMPO), executar(id))

        assertTrue(d.chamadas.isEmpty())
        assertEquals(Estados.LIMITE_DE_TEMPO, t.sessoes.carregar(id)!!.status)
        assertTrue(d.mensagens(id).contains("Time guard blocked draft call before Claude."))
    }

    // ── retomada: descartar e reconstruir ────────────────────────────────

    @Test
    fun retomadaDescartaEReconstroiTudoEContinuaDoTurnoCerto() {
        val id = criar(tres, tetoDeMinutos = 1)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(Provedor.CODEX)).also { /* aprova */ })
        // Depois do turno do Codex o tempo acaba: pausa com a custódia no meio da rodada.
        var chamadasAntes = 0
        val chamadorQueEsgota = Chamador { provedor, pedido, restante ->
            chamadasAntes += 1
            val resposta = d.chamador.chamar(provedor, pedido, restante)
            if (provedor == Provedor.CODEX) d.deslocamento = Duration.ofMinutes(2)
            resposta
        }
        val primeira = Deliberacao(t.sessoes, t.retomada, t.ponto, t.artefatos, d.anexos, chamadorQueEsgota, d.auditoria, d.relogio)
        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_TEMPO), executar(id, primeira))
        assertEquals(2, chamadasAntes)
        val pausada = t.sessoes.carregar(id)!!
        assertEquals(1, pausada.rodada)
        assertEquals(1, pausada.indiceDoTurno)

        // O processo morre: banco fechado, cada objeto descartado, tudo reaberto sobre o mesmo arquivo.
        t.reabrir()
        val d2 = DeliberacaoDeTeste(t)
        assertTrue(d2.t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        d2.responde(Provedor.GEMINI, DeliberacaoDeTeste.pronto(Provedor.GEMINI))
        d2.responde(Provedor.CLAUDE, DeliberacaoDeTeste.pronto(Provedor.CLAUDE))

        assertEquals(Desfecho.Convergida, executar(id, d2.deliberacao()))

        // Nenhum rascunho pago de novo, e o próximo turno foi exatamente o do Gemini; com o Claude autor,
        // as aprovações do Codex e do Gemini convergem sem o turno de fechamento.
        assertEquals(listOf(Provedor.GEMINI), d2.chamadas.map { it.first })
        assertTrue(d2.mensagens(id).contains(Retomada.MENSAGEM_RETOMADA))
        assertEquals(Estados.CONVERGIDA, t.sessoes.carregar(id)!!.status)
        assertEquals(3, t.artefatos.daSessao(id).size)
    }

    // ── decisão 16: chamada paga sem resultado ───────────────────────────

    @Test
    fun chamadaPagaSemResultadoNaoERetomadaSozinha() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        // O processo morre durante a chamada ao Codex: nada é gravado depois do marcador.
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(), lancar = CancellationException("processo morto")))
        var cancelada = false
        try {
            executar(id)
        } catch (erro: CancellationException) {
            cancelada = true
        }
        assertTrue(cancelada)
        val morta = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, morta.status)
        assertEquals("codex", t.banco.execucoes().uma(morta.execucaoAtual!!)!!.chamadaEmVoo)

        // O WorkManager reenfileira: a segunda execução passa por `preparar` e não paga de novo.
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        val chamadasAntes = d.chamadas.size
        assertEquals(Desfecho.ChamadaIndeterminada(Provedor.CODEX), executar(id))
        assertEquals(chamadasAntes, d.chamadas.size)
        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.ERRO, linha.status)
        assertEquals(RepositorioDeSessoes.mensagemDeChamadaIndeterminada(Provedor.CODEX), linha.erro)
        assertEquals(linha.erro, d.mensagens(id).last())
        val execucao = t.banco.execucoes().uma(morta.execucaoAtual!!)!!
        assertNotNull(execucao.fim)
        assertEquals(RepositorioDeSessoes.MOTIVO_INTERROMPIDA, execucao.motivoDaParada)
        // Só o operador retoma; e a retomada segue do turno do Codex, sem novo rascunho.
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        assertEquals(Desfecho.Convergida, executar(id))
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.CODEX), d.chamadas.map { it.first })
    }

    @Test
    fun oMarcadorSaiComODesfechoDeCadaChamada() {
        // Codex: resposta incompleta (pane cobrada); Gemini reescreve (A→B); o Claude espera o circuito e o
        // Codex é redesenhado e aprova (o turno limpo zera a contagem de panes); o Claude fecha e falha (HTTP,
        // pane sem cobrança) no último turno da rodada: rodada incompleta. Nenhuma chamada deixa o marcador.
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), RespostaDoProvedor.FalhaHttp(500, "PROVIDER_HTTP_500: down"))
        d.responde(Provedor.CODEX, RespostaDoProvedor.Incompleta("stop_reason: max_tokens", DeliberacaoDeTeste.USO), DeliberacaoDeTeste.pronto(Provedor.CODEX))
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.revisado(Provedor.GEMINI))

        assertEquals(Desfecho.Pausada(Estados.RODADA_INCOMPLETA), executar(id))

        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI, Provedor.CODEX, Provedor.CLAUDE), d.chamadas.map { it.first })
        assertTrue(d.mensagens(id).contains("Reviewer redrawn: original_author_closure_waiting_for_full_peer_circuit."))
        val linha = t.sessoes.carregar(id)!!
        assertEquals("Operational failure at the end of the round; review circuit incomplete.", linha.erro)
        assertEquals(2, linha.rodada)
        val execucao = t.banco.execucoes().uma(linha.execucaoAtual!!)!!
        assertNull(execucao.chamadaEmVoo)
        assertEquals(
            listOf("Operational turn failure (1/3): stop_reason: max_tokens", "Operational turn failure (1/3): PROVIDER_HTTP_500: down"),
            d.mensagens(id).filter { it.startsWith("Operational") },
        )
        // A resposta incompleta foi cobrada; a falha HTTP, não.
        val eventos = t.banco.eventos().daSessao(id)
        assertNotNull(eventos.first { it.mensagem.endsWith("stop_reason: max_tokens") }.custoE8)
        assertNull(eventos.first { it.mensagem.endsWith("PROVIDER_HTTP_500: down") }.custoE8)
    }

    // ── cancelamento em voo ──────────────────────────────────────────────

    @Test
    fun cancelamentoDuranteAChamadaNaoGravaArtefatoNemEvento() = runBlocking {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        val portao = CompletableDeferred<Unit>()
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(), antes = portao))
        val execucao = async(Dispatchers.IO) { d.deliberacao().executar(id) }
        // Espera a chamada do Codex estar em voo, cancela pelo operador e só então solta a resposta.
        withTimeout(10_000) { while (d.chamadas.size < 2) kotlinx.coroutines.delay(20) }
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        val antes = d.mensagens(id)
        // O texto do turno foi auditado antes da chamada, com a sessão ativa, para o pacote do portão (decisão 23).
        val auditoriasAntes = d.auditorias
        portao.complete(Unit)

        assertEquals(Desfecho.Interrompida, execucao.await())
        assertEquals(antes, d.mensagens(id))
        assertEquals(1, t.artefatos.daSessao(id).size)
        assertEquals(Estados.CANCELADA, t.sessoes.carregar(id)!!.status)
        // A releitura pós-chamada para antes da auditoria do turno: nenhuma requisição do motor para uma sessão cancelada.
        assertEquals(auditoriasAntes, d.auditorias)
    }

    // ── rodada 1 do Codex na #70 ─────────────────────────────────────────

    @Test
    fun duasExecucoesCruzadasSomamOsSeusCustos() = runBlocking {
        // A execução antiga está com a chamada ao Codex em voo; o operador cancela e retoma; a nova execução
        // corre inteira; a antiga volta e soma o que pagou. O total é a soma das três chamadas, não o maior.
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        val portao = CompletableDeferred<Unit>()
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(), antes = portao), DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto()))
        val antiga = async(Dispatchers.IO) { d.deliberacao().executar(id) }
        withTimeout(10_000) { while (d.chamadas.size < 2) kotlinx.coroutines.delay(20) }
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        assertEquals(Desfecho.Convergida, executar(id))
        val depoisDaNova = t.sessoes.carregar(id)!!.custoObservadoE8
        portao.complete(Unit)
        assertEquals(Desfecho.Interrompida, antiga.await())

        val custoDeUmaChamada = t.banco.eventos().daSessao(id).first { it.mensagem == "Initial draft produced." }.custoE8!!
        assertEquals(2 * custoDeUmaChamada, depoisDaNova)
        assertEquals(3 * custoDeUmaChamada, t.sessoes.carregar(id)!!.custoObservadoE8)
    }

    @Test
    fun guardaDeCustoLeOTotalGravadoENaoOLocal() {
        // Outra execução gasta entre o rascunho e o turno do revisor: o guarda vê o total gravado e para antes da chamada.
        val referencia = criar()
        val id = criar(teto = estimativaDoRascunho(referencia).add(BigDecimal("4")))
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        val deliberacao = Deliberacao(
            t.sessoes, t.retomada, t.ponto, t.artefatos, d.anexos, d.chamador, d.auditoria, d.relogio,
            aoAvancar = { progresso -> if (progresso.agente == Provedor.CLAUDE) t.sessoes.somarCusto(id, BigDecimal("100")) },
        )

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_CUSTO), executar(id, deliberacao))

        assertEquals(listOf(Provedor.CLAUDE), d.chamadas.map { it.first })
        assertTrue(d.mensagens(id).contains("Cost guard blocked provider call before Codex."))
    }

    @Test
    fun tetoDeTempoValeAntesDeCadaTentativaCorretiva() {
        // O tempo acaba durante a resposta que viola o contrato: a tentativa corretiva não é paga.
        val id = criar(tetoDeMinutos = 1)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.pronto())
        val chamadorQueEsgota = Chamador { provedor, pedido, restante ->
            val resposta = d.chamador.chamar(provedor, pedido, restante)
            if (provedor == Provedor.CODEX) d.deslocamento = Duration.ofMinutes(2)
            resposta
        }
        val deliberacao = Deliberacao(t.sessoes, t.retomada, t.ponto, t.artefatos, d.anexos, chamadorQueEsgota, d.auditoria, d.relogio)

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_TEMPO), executar(id, deliberacao))

        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX), d.chamadas.map { it.first })
        val mensagens = d.mensagens(id)
        assertEquals(1, mensagens.count { it.startsWith("Reclassificado para CONTRACT_VIOLATION") })
        assertEquals(0, mensagens.count { it.startsWith("Corrective retry") })
        assertEquals("Time guard blocked provider call before Codex.", mensagens.last())
    }

    @Test
    fun respostaIncompletaSemUsoECobradaComASaidaMaxima() {
        // Uma geração parada no teto de saída sem contagem de tokens custa o teto inteiro, não zero
        // (com o teto de US$ 5 a cobrança esgotaria a sessão antes do rascunho de reserva: o teto aqui é maior).
        val id = criar(tres, teto = BigDecimal("20"))
        d.responde(Provedor.CLAUDE, RespostaDoProvedor.Incompleta("stop_reason: max_tokens", Uso(null, null)))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.pronto(Provedor.GEMINI))
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        // A estimativa é a do prompt de rascunho de antes da execução (depois, `textoAtual` já é o texto final).
        val esperado = Dinheiro.paraE8(estimativaDoRascunho(id))

        assertEquals(Desfecho.Convergida, executar(id))

        val evento = t.banco.eventos().daSessao(id).first { it.mensagem.startsWith("Draft attempt failed with Claude") }
        assertEquals("estimate", evento.fonteDoCusto)
        assertEquals(esperado, evento.custoE8)
    }

    @Test
    fun tentativasCorretivasNaoRenascemNaRetomada() {
        // Duas tentativas corretivas gravadas, o worker para; a segunda execução herda a contagem dos artefatos bloqueados.
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.naoProntoSemMudanca())
        d.parar = { d.chamadas.size >= 3 }
        assertEquals(Desfecho.Interrompida, executar(id))
        assertEquals(2, t.artefatos.daSessao(id).count { it.status == "blocked" })

        d.parar = { false }
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.pronto())
        assertEquals(Desfecho.Convergida, executar(id))

        val mensagens = d.mensagens(id)
        // 1 tentativa inicial + 3 corretivas ao todo, nas duas execuções: a quarta violação esgota e pula o turno.
        assertEquals(4, mensagens.count { it.startsWith("Reclassificado para CONTRACT_VIOLATION") })
        assertEquals(listOf("Corrective retry 1/3 started in round 1.", "Corrective retry 2/3 started in round 1.", "Corrective retry 3/3 started in round 1."), mensagens.filter { it.startsWith("Corrective retry") })
        assertTrue(mensagens.contains("Operational turn failure (1/3): Corrective retries exhausted; reviewer turn skipped without a vote."))
    }

    @Test
    fun respostaSemMaestroStatusValeComoNotReady() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        // Sem a linha `MAESTRO_STATUS`, o web lê NOT_READY: com a custódia inalterada, é violação de contrato.
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.semStatus(Provedor.CODEX), DeliberacaoDeTeste.pronto(Provedor.CODEX))

        assertEquals(Desfecho.Convergida, executar(id))

        val mensagens = d.mensagens(id)
        assertEquals(1, mensagens.count { it.startsWith("Reclassificado para CONTRACT_VIOLATION: NOT_READY unchanged is not a valid serial-review outcome") })
        assertTrue(mensagens.contains("Corrective retry 1/3 started in round 1."))
    }

    @Test
    fun paradaPeloWorkerInterrompeSemGravar() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        d.parar = { d.chamadas.size >= 1 }

        assertEquals(Desfecho.Interrompida, executar(id))

        assertEquals(1, d.chamadas.size)
        // O rascunho pago foi gravado antes da parada; a sessão segue `running` para o WorkManager reenfileirar.
        assertTrue(d.mensagens(id).contains("Initial draft produced."))
        assertEquals(Estados.RODANDO, t.sessoes.carregar(id)!!.status)
    }

    // ── o laço serial ────────────────────────────────────────────────────

    @Test
    fun tresPanesSeguidasPausamPorPaneDeRevisores() {
        val id = criar(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI, Provedor.GROK))
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, RespostaDoProvedor.FalhaDeRede("PROVIDER_NETWORK_ERROR: a"))
        d.responde(Provedor.GEMINI, RespostaDoProvedor.RespostaInvalida("gemini returned a 2xx body that is not a JSON object"))
        d.responde(Provedor.GROK, RespostaDoProvedor.SemChave)

        assertEquals(Desfecho.Pausada(Estados.PANE_DE_REVISORES), executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals("3 consecutive reviewer turns failed operationally; session paused for operator action.", linha.erro)
        assertEquals(3, d.mensagens(id).count { it.startsWith("Operational turn failure") })
        assertTrue(d.mensagens(id).contains("Operational turn failure (3/3): Grok sem chave configurada neste aparelho."))
    }

    @Test
    fun tentativasCorretivasEsgotadasViramPaneEArtefatosBloqueados() {
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        // NOT_READY sem texto revisado é violação de contrato: três tentativas corretivas e o turno é pulado.
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.naoProntoSemMudanca(), DeliberacaoDeTeste.naoProntoSemMudanca())
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.pronto(Provedor.GEMINI))
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        // Depois de o turno do Codex ser pulado, o Codex ainda está pendente: o redesenho o chama de novo.
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto(Provedor.CODEX))

        assertEquals(Desfecho.Convergida, executar(id))

        val mensagens = d.mensagens(id)
        assertEquals(3, mensagens.count { it.startsWith("Corrective retry") })
        assertEquals(4, mensagens.count { it.startsWith("Reclassificado para CONTRACT_VIOLATION: NOT_READY unchanged is not a valid serial-review outcome") })
        assertTrue(mensagens.contains("Operational turn failure (1/3): Corrective retries exhausted; reviewer turn skipped without a vote."))
        assertEquals(4, t.artefatos.daSessao(id).count { it.status == "blocked" })
        // O prompt da tentativa corretiva leva a seção obrigatória.
        assertTrue(d.chamadas[2].second.prompt.contains("## Mandatory Corrective Retry"))
        assertTrue(d.chamadas[2].second.prompt.contains("This is corrective retry 1/3"))
    }

    @Test
    fun prontoSobreTextoQueFalhaNaAuditoriaERecusadoSemTentativa() {
        val id = criar()
        d.auditar = { texto -> if (texto == DeliberacaoDeTeste.TEXTO_A) DeliberacaoDeTeste.falha("bibliographic_integrity", "texto com pendencia") else null }
        // O Codex aprova o texto A sem mudar — READY recusado pelo portão, sem tentativa corretiva e sem contar
        // como agente válido; redesenhado (o Claude espera o circuito), reescreve para o B; o Claude fecha sobre o B.
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto(Provedor.CODEX), DeliberacaoDeTeste.revisado(Provedor.CODEX))

        assertEquals(Desfecho.Convergida, executar(id))

        val mensagens = d.mensagens(id)
        assertTrue(mensagens.contains("READY rejected by release gate: texto com pendencia"))
        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.CODEX, Provedor.CLAUDE), d.chamadas.map { it.first })
        assertEquals(0, mensagens.count { it.startsWith("Corrective retry") })
        val recusado = t.artefatos.daSessao(id).first { it.status == "ready_rejected" }
        assertEquals("codex", recusado.agente)
        val evento = t.banco.eventos().daSessao(id).first { it.mensagem.startsWith("READY rejected") }
        assertTrue(evento.auditoriaFinalJson!!.contains("\"gate\":\"bibliographic_integrity\""))
        // O relatório do turno recusado fica fora do histórico que instrui o próximo revisor.
        assertTrue(d.chamadas[2].second.prompt.contains("No prior revision reports are recorded for this serial cycle."))
        // Plano D: o texto A foi auditado uma vez; o B, no turno sem revisão do Gemini (o do Claude veio da memória) e, fresco, na finalização.
        assertEquals(listOf(DeliberacaoDeTeste.TEXTO_A, DeliberacaoDeTeste.TEXTO_B, DeliberacaoDeTeste.TEXTO_B), d.textosAuditados)
    }

    @Test
    fun oRevisorDeUmTextoReprovadoRecebeOPacoteDoPortaoSemTentativaCorretiva() {
        // Decisão 23 do operador (29/09/2026): sem editor no aparelho, o revisor é quem corrige o link
        // reprovado, e só corrige o que lhe mostram — o pacote vai em todo turno sobre texto reprovado.
        val id = criar()
        d.auditar = { texto ->
            if (texto == DeliberacaoDeTeste.TEXTO_A) DeliberacaoDeTeste.falha("link_integrity", "link rejeitado pelo operador") else null
        }
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.revisado(Provedor.CODEX))

        assertEquals(Desfecho.Convergida, executar(id))

        val primeiraRevisao = d.chamadas[1]
        assertEquals(Provedor.CODEX, primeiraRevisao.first)
        assertTrue(primeiraRevisao.second.prompt.contains("## Current Deterministic Editorial Gate Packet"))
        assertTrue(primeiraRevisao.second.prompt.contains("Reason: link rejeitado pelo operador"))
        assertEquals(0, d.mensagens(id).count { it.startsWith("Corrective retry") })
        // Sobre o texto aprovado pela auditoria, o turno seguinte vai sem pacote.
        assertFalse(d.chamadas[2].second.prompt.contains("## Current Deterministic Editorial Gate Packet"))
        val linha = t.sessoes.carregar(id)!!
        assertEquals(DeliberacaoDeTeste.TEXTO_B, linha.textoFinal)
        // O texto auditado é, byte a byte, o texto que a linha grava: é por ele que as linhas de link da sessão são achadas.
        assertEquals(d.textosAuditados.last(), linha.textoAtual)
    }

    @Test
    fun tetoDeTempoValeDepoisDaAuditoriaDoPortaoAntesDaChamadaDoRevisor() {
        // A auditoria do texto reprovado vai à rede e pode gastar o resto do teto: o revisor não é pago
        // e a sessão pausa por tempo, não por falha do revisor (achado do Codex na #78).
        val id = criar(tetoDeMinutos = 1)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        d.auditar = {
            d.deslocamento = Duration.ofMinutes(2)
            DeliberacaoDeTeste.falha("link_integrity", "link rejeitado pelo operador")
        }

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_TEMPO), executar(id))

        assertEquals(listOf(Provedor.CLAUDE), d.chamadas.map { it.first })
        assertEquals(Estados.LIMITE_DE_TEMPO, t.sessoes.carregar(id)!!.status)
        assertEquals("Time guard blocked provider call before Codex.", d.mensagens(id).last())
    }

    @Test
    fun htmlCruNuncaViraTextoFinalPelaAuditoriaReal() {
        // A auditoria de produção (`AuditoriaFinal.falha`), sem rede: os textos não têm link, e o coletor recusa se for chamado.
        val registro = RegistroDeLinksRoom(t.banco, null, t.relogio)
        val motor = AuditoriaFinal.MotorDeLinks { candidato ->
            IntegridadeDeLinks.auditar(candidato, AnalisadorDeUrlOkHttp, { error("o texto não tem link") }, registro, t.relogio)
        }
        d.auditar = { texto -> AuditoriaFinal.falha(texto, motor, t.relogio()) }
        // Dois blocos, como o TEXTO_A: a reescrita do Codex troca só o segundo, o do HTML, e o declara.
        val comHtml = "Alpha aprovado.\n\nBeta <script>alert(1)</script> aprovado."
        val id = criar()
        // O Claude redige com HTML cru; o READY do Codex sobre ele é recusado pelo portão; o Codex reescreve
        // limpo e o Claude fecha sobre o texto limpo (critério de aceite da MAEANDR-21, seção 4.4).
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(comHtml), DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto(Provedor.CODEX), DeliberacaoDeTeste.revisado(Provedor.CODEX))

        val desfecho = executar(id)
        val linha = t.sessoes.carregar(id)!!
        assertEquals("erro da sessão: ${linha.erro}; jornal: ${d.mensagens(id)}", Desfecho.Convergida, desfecho)
        assertEquals(DeliberacaoDeTeste.TEXTO_B, linha.textoFinal)
        assertTrue(d.mensagens(id).any { it.startsWith("READY rejected by release gate") })
        val recusa = t.banco.eventos().daSessao(id).first { it.mensagem.startsWith("READY rejected") }
        assertTrue(recusa.auditoriaFinalJson!!.contains("raw_html_in_final_text"))
    }

    @Test
    fun auditoriaFinalFrescaPausaAConvergencia() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        // O texto passa no turno sem revisão; um link morre entre o voto e a finalização, e a auditoria fresca o vê.
        d.auditar = { if (d.auditorias > 1) DeliberacaoDeTeste.falha("link_integrity", "final candidate has unresolved link-integrity evidence") else null }

        assertEquals(Desfecho.Pausada(Estados.AUDITORIA_FINAL), executar(id))
        assertEquals(2, d.auditorias)

        val linha = t.sessoes.carregar(id)!!
        assertEquals("final candidate has unresolved link-integrity evidence", linha.erro)
        assertNull(linha.textoFinal)
        assertEquals("Final release audit failed (link_integrity): final candidate has unresolved link-integrity evidence", d.mensagens(id).last())
        assertEquals(Estados.AUDITORIA_FINAL, t.banco.execucoes().uma(linha.execucaoAtual!!)!!.motivoDaParada)
    }

    @Test
    fun evidenciaDoOperadorPausaAntesDoRevisorPagoEDaConvergencia() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho("Alpha aprovado.\n\nSegundo Silva (2020, p. 3), beta."))
        // Um manifesto anexado que exige evidência do operador: o texto cita e o manifesto não tem a fonte.
        d.anexos.adicionar(
            id, "citation-manifest.json", "application/json",
            """{"schema_version":"citation_manifest.v1","protocol_hash":"$HASH_DO_PROTOCOLO","citations":[],"sources":[]}""".toByteArray(),
        )
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())

        val desfecho = executar(id)

        assertEquals(Desfecho.Pausada(Estados.AUDITORIA_FINAL), desfecho)
        // Nenhum revisor pago, nenhuma auditoria de finalização.
        assertEquals(listOf(Provedor.CLAUDE), d.chamadas.map { it.first })
        assertEquals(0, d.auditorias)
        assertTrue(d.mensagens(id).last().startsWith("Final release audit failed (abnt_citation_operator_evidence): citation gate requires operator evidence"))
    }

    @Test
    fun aRodadaViraEAsAprovacoesEstaveisSobrevivemAVirada() {
        // Codex aprova A; Gemini reescreve (A→B, autor Gemini, aprovações zeradas); Claude fecha o circuito da
        // rodada 1 sobre B (o Codex e o Gemini são válidos); a rodada vira, os válidos zeram, o estável fica;
        // rodada 2: Codex aprova B e converge (o Gemini, autor, não conta).
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), DeliberacaoDeTeste.pronto(Provedor.CLAUDE))
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto(Provedor.CODEX), DeliberacaoDeTeste.pronto(Provedor.CODEX))
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.revisado(Provedor.GEMINI))

        assertEquals(Desfecho.Convergida, executar(id))

        assertEquals(listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI, Provedor.CLAUDE, Provedor.CODEX), d.chamadas.map { it.first })
        val linha = t.sessoes.carregar(id)!!
        assertEquals(2, linha.rodada)
        assertEquals("gemini", linha.autorAtual)
        assertEquals(DeliberacaoDeTeste.TEXTO_B, linha.textoFinal)
    }

    @Test
    fun tetoDeTurnosSeriaisPausaSemUnanimidade() {
        val id = criar()
        // Dois agentes: 8 turnos seriais no máximo; as custódias alternam A→B→A… sem nunca convergir.
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho(), *Array(6) { DeliberacaoDeTeste.revisadoDeVolta(Provedor.CLAUDE) })
        d.responde(Provedor.CODEX, *Array(6) { DeliberacaoDeTeste.revisado(Provedor.CODEX) })

        assertEquals(Desfecho.Pausada(Estados.LIMITE_DE_CICLOS), executar(id))
        assertEquals(9, d.chamadas.size)

        val linha = t.sessoes.carregar(id)!!
        assertEquals("Serial turn cap of 8 turns reached without unanimity.", linha.erro)
        assertTrue(d.mensagens(id).contains("Serial turn cap reached (8); session stopped without unanimity."))
    }

    @Test
    fun rascunhoVazioPassaAoProximoAgenteETodosFalhandoPausaSemRascunho() {
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.concluida("   "))
        d.responde(Provedor.CODEX, RespostaDoProvedor.FalhaHttp(503, "PROVIDER_HTTP_503"))
        d.responde(Provedor.GEMINI, RespostaDoProvedor.Incompleta("stop_reason: safety", DeliberacaoDeTeste.USO))

        assertEquals(Desfecho.Pausada(Estados.SEM_RASCUNHO), executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals("All active agents failed to produce an initial draft.", linha.erro)
        assertNull(linha.autorAtual)
        assertEquals(
            listOf(
                "Draft attempt failed with Claude: O provedor Claude retornou um rascunho vazio (texto em branco).. Trying next active agent.",
                "Draft attempt failed with Codex: PROVIDER_HTTP_503. Trying next active agent.",
                "Draft attempt failed with Gemini: stop_reason: safety. Trying next active agent.",
            ),
            d.mensagens(id).filter { it.startsWith("Draft attempt failed") },
        )
        assertTrue(d.mensagens(id).contains("Draft fallback call started."))
    }

    @Test
    fun janelaDeAutenticacaoVencidaPausaAguardandoAutenticacao() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, RespostaDoProvedor.ExigeAutenticacao)

        assertEquals(Desfecho.Pausada(Estados.AGUARDANDO_AUTENTICACAO), executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO, linha.erro)
        assertEquals(Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO, d.mensagens(id).last())
        // Retomável: a custódia do rascunho está intacta, e a pausa (execução fechada) não conta como chamada em voo.
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto())
        assertEquals(Desfecho.Convergida, executar(id))
    }

    @Test
    fun paneDoRascunhoLimpaOMarcadorAntesDaProximaChamada() {
        val id = criar(tres)
        d.responde(Provedor.CLAUDE, RespostaDoProvedor.FalhaHttp(500, "PROVIDER_HTTP_500"))
        // O worker é parado logo depois da primeira tentativa: o marcador do Claude tem de ter saído com o evento da pane.
        d.parar = { d.chamadas.size >= 1 }

        assertEquals(Desfecho.Interrompida, executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, linha.status)
        assertNull(t.banco.execucoes().uma(linha.execucaoAtual!!)!!.chamadaEmVoo)
        // A segunda execução não é "chamada indeterminada": segue e paga o rascunho seguinte.
        d.parar = { false }
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.pronto(Provedor.CODEX))
        d.responde(Provedor.GEMINI, DeliberacaoDeTeste.pronto(Provedor.GEMINI))
        assertEquals(Desfecho.Convergida, executar(id))
    }

    @Test
    fun evidenciaDoOperadorVenceAConvergenciaNaRetomada() {
        // Custódia já convergida (o Codex aprovou de forma estável), pausada por custo, com uma citação sem fonte no manifesto.
        val id = criar()
        val execucao = (t.retomada.preparar(id) as Preparacao.Nova).execucao
        val texto = "Alpha aprovado.\n\nSegundo Silva (2020, p. 3), beta."
        val rascunho = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = texto),
            { a -> Custodia(Provedor.CLAUDE, texto, a!!.id, a.id, 1, 0, dois, emptySet(), emptySet(), 1) },
        ) as Gravacao.Gravada).artefato!!
        assertTrue(t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 2, Provedor.CODEX, status = "ready", texto = texto, anteriorId = rascunho.id),
            { _ -> Custodia(Provedor.CLAUDE, texto, rascunho.id, rascunho.id, 1, 1, dois, setOf(Provedor.CODEX), setOf(Provedor.CODEX), 2) },
            status = Estados.LIMITE_DE_CUSTO, erro = "teto",
        ) is Gravacao.Gravada)
        d.anexos.adicionar(
            id, "citation-manifest.json", "application/json",
            """{"schema_version":"citation_manifest.v1","protocol_hash":"$HASH_DO_PROTOCOLO","citations":[],"sources":[]}""".toByteArray(),
        )
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)

        // Ordem do desktop: a evidência do operador vem antes da convergência — a sessão não finaliza.
        assertEquals(Desfecho.Pausada(Estados.AUDITORIA_FINAL), executar(id))

        assertTrue(d.chamadas.isEmpty())
        assertEquals(0, d.auditorias)
        assertNull(t.sessoes.carregar(id)!!.textoFinal)
        assertTrue(d.mensagens(id).last().startsWith("Final release audit failed (abnt_citation_operator_evidence)"))
    }

    @Test
    fun erroInesperadoViraErrorComAMensagem() {
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.rascunho(), lancar = IllegalStateException("disco cheio")))

        assertEquals(Desfecho.Pausada(Estados.ERRO), executar(id))

        val linha = t.sessoes.carregar(id)!!
        assertEquals("disco cheio", linha.erro)
        assertEquals("disco cheio", d.mensagens(id).last())
        assertEquals(Estados.ERRO, t.banco.execucoes().uma(linha.execucaoAtual!!)!!.motivoDaParada)
    }

    @Test
    fun cancelarERetomarNoMeioDeUmaChamadaNaoDeixaAExecucaoAntigaEscrever() = runBlocking {
        // Emenda A2: `retomar` zera a cerca; a execução antiga, ao voltar da chamada, perde o checkpoint.
        val id = criar()
        d.responde(Provedor.CLAUDE, DeliberacaoDeTeste.rascunho())
        val portao = CompletableDeferred<Unit>()
        d.responde(Provedor.CODEX, DeliberacaoDeTeste.Combinada(DeliberacaoDeTeste.pronto(), antes = portao))
        val antiga = async(Dispatchers.IO) { d.deliberacao().executar(id) }
        withTimeout(10_000) { while (d.chamadas.size < 2) kotlinx.coroutines.delay(20) }
        assertTrue(t.sessoes.cancelar(id) is Resultado.Ok)
        assertTrue(t.retomada.pedir(id, null, null, BancoDeTeste.TODAS_AS_CHAVES) is Resultado.Ok)
        assertNull(t.sessoes.carregar(id)!!.execucaoAtual)
        portao.complete(Unit)
        assertEquals(Desfecho.Interrompida, antiga.await())
        assertEquals(Estados.NA_FILA, t.sessoes.carregar(id)!!.status)
        assertEquals(1, t.artefatos.daSessao(id).size)
        assertFalse(d.mensagens(id).contains("Reviewer left custody unchanged."))
    }

    private companion object {
        /** O hash do protocolo das sessões de teste: o manifesto anexado precisa dele para a sessão o aceitar. */
        val HASH_DO_PROTOCOLO: String = FormatoDoRegistro.sha256(RepositorioDeConfiguracoes.PROTOCOLO_PADRAO)
    }
}
