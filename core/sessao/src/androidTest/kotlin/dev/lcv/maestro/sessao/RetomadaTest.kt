package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

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
        // Uma segunda execução reivindica de novo (a primeira morreu): a antiga perde a cerca
        // e a sua linha é fechada, para não ficar aberta no orçamento de 24 horas.
        val segunda = t.retomada.preparar(id) as Preparacao.Nova
        assertTrue(segunda.execucao > preparacao.execucao)
        val superada = t.banco.execucoes().uma(preparacao.execucao)!!
        assertNotNull(superada.fim)
        assertEquals(Retomada.MOTIVO_SUPERADA, superada.motivoDaParada)
        assertNull(t.banco.execucoes().uma(segunda.execucao)!!.fim)
        assertEquals(1, t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").count { it.fim == null })
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
        val morta = reivindicar(outra)
        assertTrue(t.sessoes.marcarInterrompida(outra, morta))
        // Quem declarou o worker morto fechou a execução dele: ela não fica aberta no orçamento de 24 horas.
        val fechada = t.banco.execucoes().uma(morta)!!
        assertNotNull(fechada.fim)
        assertEquals(RepositorioDeSessoes.MOTIVO_INTERROMPIDA, fechada.motivoDaParada)
        assertEquals(Preparacao.Perdida, t.retomada.preparar(outra))
        assertEquals(0, t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").count { it.fim == null })
    }

    @Test
    fun artefatoDeOutraSessaoNaoEntraNoCheckpoint() {
        val a = t.sessoes.criar(t.entrada()).id
        val b = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(a)
        assertThrows(IllegalArgumentException::class.java) {
            t.ponto.gravarTurno(
                a, execucao, t.artefato(b, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
                { c -> custodia(Provedor.CLAUDE, textoDoRascunho, c!!.id, c.id, 1, 0, emptySet(), emptySet(), 1) },
            )
        }
        assertEquals(0, t.artefatos.daSessao(b).size)
        assertEquals(0, t.artefatos.daSessao(a).size)
        assertNull(t.sessoes.carregar(a)!!.custodiaArtefatoId)
    }

    @Test
    fun relatorioAcimaDoTetoERecusadoNoCheckpoint() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val entrada = t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho)
            .copy(relatorioDeRevisao = "{\"n\":\"" + "a".repeat(MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO) + "\"}")
        val erro = assertThrows(IntegridadeDeLinks.Falha::class.java) {
            t.ponto.gravarTurno(id, execucao, entrada, { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) })
        }
        assertEquals("Artifact revision report exceeds 120000 code points.", erro.message)
        assertEquals(0, t.artefatos.daSessao(id).size)
        // No limite exato o relatório entra inteiro, sem corte.
        val noLimite = entrada.copy(relatorioDeRevisao = "a".repeat(MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO))
        val gravado = (t.ponto.gravarTurno(id, execucao, noLimite, { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) }) as Gravacao.Gravada).artefato!!
        assertEquals(MarkdownDoArtefato.MAX_PONTOS_DO_RELATORIO, gravado.relatorioDeRevisaoJson.length)
    }

    @Test
    fun textoAceitoAcimaDoTetoERecusadoNoArtefatoNoCheckpointENaConclusao() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val grande = "a".repeat(MarkdownDoArtefato.MAX_BYTES_DO_TEXTO + 1)
        val mensagem = "Accepted text exceeds ${MarkdownDoArtefato.MAX_BYTES_DO_TEXTO} bytes."
        // No artefato, mesmo com a custódia apontando para um texto curto (uma revisão bloqueada guarda o seu próprio texto).
        val doArtefato = assertThrows(IntegridadeDeLinks.Falha::class.java) {
            t.ponto.gravarTurno(
                id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = grande),
                { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
            )
        }
        assertEquals(mensagem, doArtefato.message)
        assertEquals(0, t.artefatos.daSessao(id).size)
        // No checkpoint sem artefato, com o texto da custódia grande.
        val rascunho = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) as Gravacao.Gravada).artefato!!
        val doCheckpoint = assertThrows(IntegridadeDeLinks.Falha::class.java) {
            t.ponto.gravarTurno(id, execucao, null, { _ -> custodia(Provedor.CLAUDE, grande, rascunho.id, rascunho.id, 1, 1, emptySet(), emptySet(), 1) })
        }
        assertEquals(mensagem, doCheckpoint.message)
        assertEquals(textoDoRascunho, t.sessoes.carregar(id)!!.textoAtual)
        // Na conclusão.
        val daConclusao = assertThrows(IntegridadeDeLinks.Falha::class.java) { t.sessoes.concluir(id, execucao, grande, "finished", null) }
        assertEquals(mensagem, daConclusao.message)
        assertEquals(Estados.RODANDO, t.sessoes.carregar(id)!!.status)
        assertNull(t.sessoes.carregar(id)!!.textoFinal)
    }

    @Test
    fun sairDeQueuedOuRunningFechaAExecucaoNaMesmaTransacao() {
        // Pausa no checkpoint.
        val pausada = t.sessoes.criar(t.entrada()).id
        val execucaoPausada = reivindicar(pausada)
        assertTrue(t.ponto.gravarTurno(
            pausada, execucaoPausada, t.artefato(pausada, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
            status = "paused_cost_limit", erro = "teto",
        ) is Gravacao.Gravada)
        assertEquals("paused_cost_limit", t.banco.execucoes().uma(execucaoPausada)!!.motivoDaParada)
        // Conclusão.
        val concluida = t.sessoes.criar(t.entrada()).id
        val execucaoConcluida = reivindicar(concluida)
        assertTrue(t.sessoes.concluir(concluida, execucaoConcluida, "Texto final.", "finished", null))
        assertEquals("finished", t.banco.execucoes().uma(execucaoConcluida)!!.motivoDaParada)
        // Cancelamento pelo operador, com o worker ainda vivo.
        val cancelada = t.sessoes.criar(t.entrada()).id
        val execucaoCancelada = reivindicar(cancelada)
        assertTrue(t.sessoes.cancelar(cancelada) is Resultado.Ok)
        assertEquals(Estados.CANCELADA, t.banco.execucoes().uma(execucaoCancelada)!!.motivoDaParada)
        // Um checkpoint que segue `running` deixa a execução aberta.
        val viva = t.sessoes.criar(t.entrada()).id
        val execucaoViva = reivindicar(viva)
        assertTrue(t.ponto.gravarTurno(
            viva, execucaoViva, t.artefato(viva, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) is Gravacao.Gravada)
        assertNull(t.banco.execucoes().uma(execucaoViva)!!.fim)
        assertEquals(listOf(execucaoViva), t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").filter { it.fim == null }.map { it.seq })
    }

    @Test
    fun bytesDoConteudoMedemOMarkdownQueOLeitorRecebe() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        // Um custo com mais casas do que a coluna guarda: a renderização da linha (10.000000)
        // difere da que sairia da entrada crua (9.999999), e a medida tem de ser a da linha.
        val entrada = t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho).copy(custoUsd = BigDecimal("9.99999949999"))
        val gravado = (t.ponto.gravarTurno(
            id, execucao, entrada,
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) as Gravacao.Gravada).artefato!!
        val lido = t.artefatos.um(id, gravado.id)!!
        val markdown = MarkdownDoArtefato.doArtefato(lido)
        assertTrue(markdown.contains("- Cost USD: 10.000000"))
        assertEquals(markdown.toByteArray(Charsets.UTF_8).size.toLong(), lido.bytesDoConteudo)
        assertEquals(DetalheDoArtefato.de(lido, null).conteudoMd, markdown)
    }

    @Test
    fun prepararLeOEstadoDaLinhaDepoisDeReivindicar() {
        // A execução anterior gravou o rascunho aceito; a que a substitui tem de ver a custódia e retomar, não redigir de novo.
        val id = t.sessoes.criar(t.entrada()).id
        val antiga = reivindicar(id)
        assertTrue(t.ponto.gravarTurno(
            id, antiga, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) is Gravacao.Gravada)
        val nova = t.retomada.preparar(id) as Preparacao.Retomar
        assertEquals(Provedor.CLAUDE, nova.autorAtual)
        assertEquals(textoDoRascunho, nova.textoAtual)
        assertEquals(0, nova.progresso.indiceDoTurno)
        assertTrue(nova.execucao > antiga)
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
    fun markdownAcimaDoTetoERecusadoNoCheckpoint() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val enorme = "a".repeat(MarkdownDoArtefato.MAX_PONTOS_DE_CODIGO)
        try {
            t.ponto.gravarTurno(id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = enorme), { a -> custodia(Provedor.CLAUDE, enorme, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) })
            fail("devia recusar")
        } catch (erro: dev.lcv.maestro.protocolo.IntegridadeDeLinks.Falha) {
            assertEquals("Artifact markdown exceeds 500000 code points.", erro.message)
        }
        assertEquals(0, t.artefatos.daSessao(id).size)
    }

    @Test
    fun linhaAcimaDeUmMebibyteERecusadaNoCheckpoint() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val emojis = "😀".repeat(300_000)
        try {
            t.ponto.gravarTurno(id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = emojis), { a -> custodia(Provedor.CLAUDE, emojis, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) })
            fail("devia recusar")
        } catch (erro: dev.lcv.maestro.protocolo.IntegridadeDeLinks.Falha) {
            assertEquals("Artifact row exceeds 1048576 bytes.", erro.message)
        }
        assertEquals(0, t.artefatos.daSessao(id).size)
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
        // A reivindicação foi desfeita com a transação: a execução que fica é a da última execução válida,
        // e ela é fechada junto da pausa, para não ficar aberta no orçamento.
        assertEquals(execucaoAnterior, depois.execucaoAtual)
        assertNotNull(t.banco.execucoes().uma(execucaoAnterior!!)!!.fim)
        assertEquals(0, t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").count { it.fim == null })
        val ultimo = t.sessoes.eventos(id).last()
        assertEquals(EventoDaSessao.BLOQUEADO, ultimo.status)
        assertEquals(Provedor.GEMINI, ultimo.agente)
        assertEquals("draft", ultimo.papel)
        assertEquals(preparacao.mensagem, ultimo.mensagem)
        assertEquals(3, t.artefatos.daSessao(id).size)
    }

    @Test
    fun custodiaInvalidaComAExecucaoAindaAbertaFechaAExecucaoMorta() {
        // O processo morreu em `running`, sem reconciliação; o WorkManager reexecuta o worker sobre uma custódia adulterada.
        val id = t.sessoes.criar(t.entrada()).id
        val morta = reivindicar(id)
        assertTrue(t.ponto.gravarTurno(
            id, morta, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) is Gravacao.Gravada)
        t.reabrir()
        t.adulterar("UPDATE sessoes SET rodada = 0 WHERE id = '$id'")
        assertNull(t.banco.execucoes().uma(morta)!!.fim)
        assertTrue(t.retomada.preparar(id) is Preparacao.CustodiaInvalida)
        assertEquals(Estados.RETOMADA_INVALIDA, t.sessoes.carregar(id)!!.status)
        val fechada = t.banco.execucoes().uma(morta)!!
        assertNotNull(fechada.fim)
        assertEquals(Retomada.MOTIVO_SUPERADA, fechada.motivoDaParada)
        assertEquals(0, t.banco.execucoes().naJanela("2000-01-01T00:00:00.000Z").count { it.fim == null })
    }

    @Test
    fun reconciliacaoSoPausaAExecucaoQueInspecionou() {
        // A reconciliação leu a execução 1 como morta; antes de escrever, o worker substituto reivindicou a sessão.
        val id = t.sessoes.criar(t.entrada()).id
        val inspecionada = reivindicar(id)
        val substituta = reivindicar(id)
        assertFalse(t.sessoes.marcarInterrompida(id, inspecionada))
        assertFalse(t.sessoes.marcarInterrompida(id, null))
        val viva = t.sessoes.carregar(id)!!
        assertEquals(Estados.RODANDO, viva.status)
        assertEquals(substituta, viva.execucaoAtual)
        assertNull(t.banco.execucoes().uma(substituta)!!.fim)
        // Com a execução certa, a reconciliação passa e fecha só ela.
        assertTrue(t.sessoes.marcarInterrompida(id, substituta))
        assertEquals(RepositorioDeSessoes.MOTIVO_INTERROMPIDA, t.banco.execucoes().uma(substituta)!!.motivoDaParada)
        // Uma sessão nunca reivindicada só é reconciliada com `null`.
        val naFila = t.sessoes.criar(t.entrada()).id
        assertFalse(t.sessoes.marcarInterrompida(naFila, 999L))
        assertTrue(t.sessoes.marcarInterrompida(naFila, null))
    }

    @Test
    fun textoAceitoVazioERecusadoEAutorGravadoSemTextoEhCustodiaInvalida() {
        val id = t.sessoes.criar(t.entrada()).id
        val execucao = reivindicar(id)
        val rascunho = (t.ponto.gravarTurno(
            id, execucao, t.artefato(id, 1, Provedor.CLAUDE, papel = "draft", texto = textoDoRascunho),
            { a -> custodia(Provedor.CLAUDE, textoDoRascunho, a!!.id, a.id, 1, 0, emptySet(), emptySet(), 1) },
        ) as Gravacao.Gravada).artefato!!
        // Um checkpoint cujo texto aceito é só espaço é recusado antes de ser gravado.
        val vazio = assertThrows(IntegridadeDeLinks.Falha::class.java) {
            t.ponto.gravarTurno(id, execucao, null, { _ -> custodia(Provedor.CLAUDE, "   ", rascunho.id, rascunho.id, 1, 1, emptySet(), emptySet(), 1) })
        }
        assertEquals("Accepted text is empty.", vazio.message)
        assertEquals(textoDoRascunho, t.sessoes.carregar(id)!!.textoAtual)
        // Uma linha com autor e sem texto (adulterada) é custódia inválida na retomada, não uma sessão nova a redigir de novo.
        t.adulterar("UPDATE sessoes SET textoAtual = '' WHERE id = '$id'")
        val preparacao = t.retomada.preparar(id)
        assertTrue(preparacao is Preparacao.CustodiaInvalida)
        assertEquals(Estados.RETOMADA_INVALIDA, t.sessoes.carregar(id)!!.status)
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
