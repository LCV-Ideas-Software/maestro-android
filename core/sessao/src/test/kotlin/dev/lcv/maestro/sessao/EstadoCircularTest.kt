package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A custódia em colunas: uma entrada que falha por verificação, e a restauração do web. */
class EstadoCircularTest {

    private val texto = "Texto aceito.\n\nSegundo parágrafo."
    private val escala = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)
    private val rascunho = Fixtures.artefato("artifact-r", Fixtures.entrada(turno = 1, agente = Provedor.CLAUDE, texto = "Rascunho."))
    private val anterior = Fixtures.artefato("artifact-p", Fixtures.entrada(turno = 2, agente = Provedor.GEMINI, papel = "revision", status = "ready", texto = "Anterior."))
    private val custodia = Fixtures.artefato("artifact-c", Fixtures.entrada(turno = 3, agente = Provedor.CODEX, papel = "revision", status = "not_ready", texto = texto))
    private val artefatos = mapOf(rascunho.id to rascunho, anterior.id to anterior, custodia.id to custodia)

    private val sessao = Fixtures.sessao(
        autorAtual = "codex", textoAtual = texto, custodiaArtefatoId = custodia.id, artefatoAnteriorId = anterior.id,
        rodada = 1, indiceDoTurno = 1, turnoDoArtefato = 3, escala = escala, agentesValidos = listOf(Provedor.CLAUDE), aprovacoesEstaveis = listOf(Provedor.GEMINI),
    )

    private fun validar(sessao: SessaoEntidade = this.sessao) = EstadoCircular.validar(sessao) { artefatos[it] }

    private fun mensagem(bloco: () -> Unit): String = assertFailsWith<IntegridadeDeLinks.Falha>(block = bloco).message!!

    @Test
    fun `a custodia valida volta tipada`() {
        val c = validar()
        assertEquals(Provedor.CODEX, c.autorAtual)
        assertEquals(texto, c.textoAtual)
        assertEquals(setOf(Provedor.CLAUDE), c.agentesValidos)
        assertEquals(setOf(Provedor.GEMINI), c.aprovacoesEstaveis)
        assertEquals(3, c.turnoDoArtefato)
        assertEquals(escala, c.escala)
    }

    @Test
    fun `cada verificacao falha com a sua mensagem`() {
        val contadores = "Circular custody progress contains invalid counters or artifact references."
        assertEquals(contadores, mensagem { validar(sessao.copy(rodada = 0)) })
        assertEquals(contadores, mensagem { validar(sessao.copy(indiceDoTurno = -1)) })
        assertEquals(contadores, mensagem { validar(sessao.copy(turnoDoArtefato = 0)) })
        assertEquals(contadores, mensagem { validar(sessao.copy(custodiaArtefatoId = null)) })
        assertEquals(contadores, mensagem { validar(sessao.copy(artefatoAnteriorId = "")) })
        assertEquals("Circular custody state contains an unknown draft author.", mensagem { validar(sessao.copy(autorAtual = "anthropic")) })
        assertEquals("Circular custody progress contains an unknown reviewer.", mensagem { validar(sessao.copy(agentesValidosJson = "[\"bing\"]")) })
        assertEquals("Circular custody progress contains an unknown reviewer.", mensagem { validar(sessao.copy(escalaJson = "\"claude\"")) })
        assertEquals("Circular custody state contains duplicate roster members.", mensagem { validar(sessao.copy(escalaJson = "[\"claude\",\"claude\"]")) })
        assertEquals("Circular custody progress references a reviewer outside its roster.", mensagem { validar(sessao.copy(aprovacoesEstaveisJson = "[\"grok\"]")) })
        assertEquals("Circular custody references a missing or rejected artifact.", mensagem { validar(sessao.copy(custodiaArtefatoId = "artifact-zzz")) })
        assertEquals("Circular custody references a missing or rejected artifact.", mensagem { EstadoCircular.validar(sessao) { if (it == custodia.id) custodia.copy(status = "blocked") else artefatos[it] } })
        assertEquals("Circular custody chain references a missing previous artifact.", mensagem { validar(sessao.copy(artefatoAnteriorId = "artifact-zzz")) })
        assertEquals("Circular custody artifact author or turn does not match persisted state.", mensagem { validar(sessao.copy(turnoDoArtefato = 2)) })
        assertEquals("Circular custody artifact author or turn does not match persisted state.", mensagem { validar(sessao.copy(autorAtual = "gemini")) })
        assertEquals("Circular custody artifact text does not match the session row.", mensagem { validar(sessao.copy(textoAtual = "Outro texto.")) })
        assertEquals("Circular custody artifact text does not match the session row.", mensagem { validar(sessao.copy(textoAtual = " ")) })
    }

    @Test
    fun `artefato de outra sessao nao serve de custodia`() {
        val alheio = custodia.copy(sessaoId = "android-2")
        val mensagem = mensagem { EstadoCircular.validar(sessao) { if (it == custodia.id) alheio else artefatos[it] } }
        assertEquals("Circular custody references a missing or rejected artifact.", mensagem)
    }

    @Test
    fun `o texto canonico apara como o javascript, normaliza crlf e recusa nul`() {
        assertEquals("a\nb", EstadoCircular.textoCanonico("﻿ a\r\nb  "))
        assertEquals("a\nb", EstadoCircular.textoCanonico(EstadoCircular.textoCanonico("﻿ a\r\nb  ")))
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { EstadoCircular.textoCanonico("a\u0000b") }
        assertEquals("Accepted text contains a NUL character.", erro.message)
    }

    @Test
    fun `listas de agentes sao lidas estritamente`() {
        assertEquals(listOf(Provedor.CLAUDE, Provedor.GROK), EstadoCircular.lerAgentes("[\"claude\",\"grok\"]"))
        assertEquals(emptyList(), EstadoCircular.lerAgentes("[]"))
        assertNull(EstadoCircular.lerAgentes("[\"anthropic\"]"))
        assertNull(EstadoCircular.lerAgentes("{}"))
        assertNull(EstadoCircular.lerAgentes("lixo"))
        assertEquals("[\"codex\",\"gemini\"]", EstadoCircular.agentesJson(listOf(Provedor.CODEX, Provedor.GEMINI)))
    }

    @Test
    fun `restaurar mantem o turno exato e as aprovacoes com a mesma escala`() {
        val restaurado = EstadoCircular.restaurar(validar(), escala, Provedor.CODEX, emptyList())
        assertEquals(1, restaurado.rodada)
        assertEquals(1, restaurado.indiceDoTurno)
        assertEquals(setOf(Provedor.CLAUDE), restaurado.agentesValidos)
        assertEquals(setOf(Provedor.GEMINI), restaurado.aprovacoesEstaveis)
        assertEquals(custodia.id, restaurado.artefatoDeCustodiaId)
        assertEquals(anterior.id, restaurado.artefatoAnteriorId)
    }

    @Test
    fun `restaurar cruza a rodada quando o indice passa do fim e zera os validos`() {
        val restaurado = EstadoCircular.restaurar(validar(sessao.copy(indiceDoTurno = 4)), escala, Provedor.CODEX, emptyList())
        assertEquals(2, restaurado.rodada)
        assertEquals(1, restaurado.indiceDoTurno)
        assertEquals(emptySet(), restaurado.agentesValidos)
    }

    @Test
    fun `escala diferente recomeca a rodada e filtra as aprovacoes ao painel novo sem o autor`() {
        val custodia = validar()
        val novaEscala = listOf(Provedor.GEMINI, Provedor.CODEX, Provedor.CLAUDE, Provedor.GROK)
        val restaurado = EstadoCircular.restaurar(custodia, novaEscala, Provedor.GEMINI, emptyList())
        assertEquals(0, restaurado.indiceDoTurno)
        assertEquals(emptySet(), restaurado.agentesValidos)
        assertEquals(emptySet(), restaurado.aprovacoesEstaveis)
        val mantida = EstadoCircular.restaurar(custodia, novaEscala, Provedor.CODEX, emptyList())
        assertEquals(setOf(Provedor.GEMINI), mantida.aprovacoesEstaveis)
    }

    @Test
    fun `relatorio deliberativo exclui rascunhos rejeitados e status nao deliberativos`() {
        assertNull(EstadoCircular.relatorioDeliberativo(rascunho))
        assertNull(EstadoCircular.relatorioDeliberativo(anterior.copy(status = "blocked")))
        val relatorio = EstadoCircular.relatorioDeliberativo(custodia)!!
        assertEquals("Codex", relatorio.nome)
        assertEquals("review", relatorio.papel)
        assertEquals("NOT_READY", relatorio.status)
        assertEquals(custodia.id, relatorio.artefato)
        assertNull(EstadoCircular.relatorioDeliberativo(custodia.copy(relatorioDeRevisaoJson = ""))!!.relatorio)
        assertEquals(true, EstadoCircular.cadeiaAceita(custodia))
        assertEquals(false, EstadoCircular.cadeiaAceita(custodia.copy(status = "running")))
    }
}
