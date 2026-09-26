package dev.lcv.maestro.sessao

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** As funções puras da custódia circular, uma entrada que falha por mensagem. */
class EstadoCircularTest {

    private val texto = "Texto aceito.\n\nSegundo parágrafo."
    private val escala = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI)
    private val rascunho = Fixtures.artefato("artifact-r", Fixtures.entrada(turno = 1, agente = Provedor.CLAUDE, conteudoMd = "Rascunho."))
    private val anterior = Fixtures.artefato("artifact-p", Fixtures.entrada(turno = 2, agente = Provedor.GEMINI, papel = "revision", status = "ready", conteudoMd = "Anterior."))
    private val custodia = Fixtures.artefato("artifact-c", Fixtures.entrada(turno = 3, agente = Provedor.CODEX, papel = "revision", status = "not_ready", conteudoMd = texto))
    private val artefatos = mapOf(rascunho.id to rascunho, anterior.id to anterior, custodia.id to custodia)

    private val progresso = ProgressoCircular(
        autorAtual = Provedor.CODEX, textoAtual = "$texto\n", artefatoDeCustodiaId = custodia.id, artefatoAnteriorId = anterior.id,
        rodada = 1, indiceDoTurno = 1, escala = escala, agentesValidos = setOf(Provedor.CLAUDE), aprovacoesEstaveis = setOf(Provedor.GEMINI),
        turnoDoArtefato = 3,
    )
    private val json = EstadoCircular.serializar("android-1", progresso, Fixtures.AGORA)
    private val sessao = Fixtures.sessao(autorAtual = "codex", textoAtual = "$texto\n", estadoCircularJson = json)

    private fun validar(sessao: SessaoEntidade = this.sessao, json: String = this.json) =
        EstadoCircular.validar(sessao, EstadoCircular.ler(json)!!) { artefatos[it] }

    private fun mutado(bloco: (ObjectNode) -> Unit): String {
        val no = Json.ESTRITO.readTree(json) as ObjectNode
        bloco(no)
        return Json.ESTRITO.writeValueAsString(no)
    }

    private fun mensagem(bloco: () -> Unit): String = assertFailsWith<IntegridadeDeLinks.Falha>(block = bloco).message!!

    @Test
    fun `serializa como o web e o estado valido volta tipado`() {
        val no = Json.ESTRITO.readTree(json)
        assertEquals(2, no.get("schema_version").intValue())
        assertEquals(FormatoDoRegistro.sha256(texto), no.get("current_draft_sha256").textValue())
        assertEquals(Fixtures.ISO_AGORA, no.get("updated_at").textValue())
        val estado = validar()
        assertEquals(Provedor.CODEX, estado.autorDaCustodia)
        assertEquals(listOf(Provedor.CLAUDE), estado.agentesValidosDaRodada)
        assertEquals(listOf(Provedor.GEMINI), estado.aprovacoesEstaveis)
        assertEquals(3, estado.turnoDoArtefato)
    }

    @Test
    fun `serializar aplica os minimos do web`() {
        val no = Json.ESTRITO.readTree(EstadoCircular.serializar("x", progresso.copy(rodada = 0, indiceDoTurno = -3, turnoDoArtefato = 0), Fixtures.AGORA))
        assertEquals(1, no.get("round").intValue())
        assertEquals(0, no.get("turn_index").intValue())
        assertEquals(1, no.get("artifact_turn").intValue())
    }

    @Test
    fun `ler distingue sem estado de estado invalido`() {
        assertNull(EstadoCircular.ler(""))
        assertNull(EstadoCircular.ler(" {} \n"))
        assertNull(EstadoCircular.ler(null))
        assertTrue(mensagem { EstadoCircular.ler("{\"a\":") }.startsWith("Circular custody state contains invalid JSON: "))
        assertTrue(mensagem { EstadoCircular.ler("{\"a\":1,\"a\":2}") }.startsWith("Circular custody state contains invalid JSON: "))
        assertEquals("Circular custody state must be an object.", mensagem { EstadoCircular.ler("[1]") })
        assertNull(EstadoCircular.lerTolerante("[1]"))
    }

    @Test
    fun `cada verificacao falha com a mensagem do web`() {
        assertEquals("Unsupported circular custody schema version: 1.", mensagem { validar(json = mutado { it.put("schema_version", 1) }) })
        assertEquals("Unsupported circular custody schema version: undefined.", mensagem { validar(json = mutado { it.remove("schema_version") }) })
        assertEquals("Circular custody run_id does not match the session.", mensagem { validar(json = mutado { it.put("run_id", "outra") }) })
        assertEquals("Circular custody state contains an unknown draft author.", mensagem { validar(json = mutado { it.put("current_draft_author_key", "anthropic") }) })
        assertEquals("Circular custody author does not match the session row.", mensagem { validar(sessao = sessao.copy(autorAtual = "claude")) })
        assertEquals("Circular custody progress contains an unknown reviewer.", mensagem { validar(json = mutado { it.putArray("valid_round_agents").add("bing") }) })
        assertEquals("Circular custody progress contains an unknown reviewer.", mensagem { validar(json = mutado { it.put("round_roster", "claude") }) })
        assertEquals("Circular custody state contains duplicate roster members.", mensagem { validar(json = mutado { it.putArray("round_roster").add("claude").add("claude") }) })
        assertEquals("Circular custody progress references a reviewer outside its roster.", mensagem { validar(json = mutado { it.putArray("stable_serial_approval_agents").add("grok") }) })
        assertEquals("Circular custody progress contains invalid counters or artifact references.", mensagem { validar(json = mutado { it.put("round", 0) }) })
        assertEquals("Circular custody progress contains invalid counters or artifact references.", mensagem { validar(json = mutado { it.put("turn_index", 1.5) }) })
        assertEquals("Circular custody progress contains invalid counters or artifact references.", mensagem { validar(json = mutado { it.put("previous_artifact_id", "") }) })
        assertEquals("Circular custody references a missing or rejected artifact.", mensagem { validar(json = mutado { it.put("current_draft_artifact", "artifact-zzz") }) })
        assertEquals("Circular custody chain references a missing previous artifact.", mensagem { validar(json = mutado { it.put("previous_artifact_id", "artifact-zzz") }) })
        assertEquals("Circular custody artifact author or turn does not match persisted state.", mensagem { validar(json = mutado { it.put("artifact_turn", 2) }) })
        assertEquals("Circular custody artifact text does not match the session row.", mensagem { validar(sessao = sessao.copy(textoAtual = "Outro texto.")) })
        val hashErrado = mutado { it.put("current_draft_sha256", "0".repeat(64)) }
        assertEquals("Circular custody draft hash does not match the accepted artifact.", mensagem { validar(json = hashErrado) })
    }

    @Test
    fun `um inteiro escrito como 2 ponto 0 conta como inteiro no javascript`() {
        val estado = validar(json = mutado { it.put("round", 1.0) })
        assertEquals(1, estado.rodada)
    }

    @Test
    fun `restaurar mantem o turno exato e as aprovacoes com a mesma escala`() {
        val estado = validar()
        val restaurado = EstadoCircular.restaurar(estado, escala, Provedor.CODEX, emptyList())
        assertEquals(1, restaurado.rodada)
        assertEquals(1, restaurado.indiceDoTurno)
        assertEquals(setOf(Provedor.CLAUDE), restaurado.agentesValidos)
        assertEquals(setOf(Provedor.GEMINI), restaurado.aprovacoesEstaveis)
    }

    @Test
    fun `restaurar cruza a rodada quando o indice passa do fim e zera os validos`() {
        val estado = validar(json = mutado { it.put("turn_index", 4) })
        val restaurado = EstadoCircular.restaurar(estado, escala, Provedor.CODEX, emptyList())
        assertEquals(2, restaurado.rodada)
        assertEquals(1, restaurado.indiceDoTurno)
        assertEquals(emptySet(), restaurado.agentesValidos)
    }

    @Test
    fun `escala diferente recomeca a rodada e filtra as aprovacoes ao painel novo sem o autor`() {
        val estado = validar()
        val novaEscala = listOf(Provedor.GEMINI, Provedor.CODEX, Provedor.CLAUDE, Provedor.GROK)
        val restaurado = EstadoCircular.restaurar(estado, novaEscala, Provedor.GEMINI, emptyList())
        assertEquals(0, restaurado.indiceDoTurno)
        assertEquals(emptySet(), restaurado.agentesValidos)
        assertEquals(emptySet(), restaurado.aprovacoesEstaveis)
        val mantida = EstadoCircular.restaurar(estado, novaEscala, Provedor.CODEX, emptyList())
        assertEquals(setOf(Provedor.GEMINI), mantida.aprovacoesEstaveis)
    }

    @Test
    fun `legado sem artefatos cria o artefato de recuperacao`() {
        val criados = mutableListOf<EntradaDeArtefato>()
        val progresso = EstadoCircular.reconstruirLegado(sessao, Provedor.CODEX, texto, emptyList()) { entrada ->
            criados += entrada
            Fixtures.artefato("artifact-novo", entrada)
        }
        assertEquals(1, criados.size)
        assertEquals(0, criados[0].ciclo)
        assertEquals(1, criados[0].turno)
        assertEquals("{\"reviewer\":\"codex\",\"role\":\"legacy_custody_recovery\",\"status\":\"ready\",\"custody\":\"recovered\"}", criados[0].relatorioDeRevisao)
        assertEquals("artifact-novo", progresso.artefatoDeCustodiaId)
        assertEquals("artifact-novo", progresso.artefatoAnteriorId)
        assertEquals(1, progresso.rodada)
        assertEquals(1, progresso.turnoDoArtefato)
    }

    @Test
    fun `legado com artefatos que contradizem a linha falha fechado`() {
        val mensagem = mensagem {
            EstadoCircular.reconstruirLegado(sessao, Provedor.CODEX, "Outro texto.", listOf(rascunho, anterior, custodia)) { error("não cria") }
        }
        assertEquals("Legacy circular custody cannot be reconstructed from the existing artifacts.", mensagem)
    }

    @Test
    fun `legado com custodia encontrada usa o ultimo artefato aceito da cadeia como anterior`() {
        val posterior = Fixtures.artefato("artifact-d", Fixtures.entrada(ciclo = 2, turno = 4, agente = Provedor.CLAUDE, papel = "revision", status = "ready", conteudoMd = "Outro."))
        val bloqueado = Fixtures.artefato("artifact-b", Fixtures.entrada(ciclo = 3, turno = 5, agente = Provedor.GEMINI, papel = "revision", status = "blocked", conteudoMd = "x"))
        val progresso = EstadoCircular.reconstruirLegado(sessao, Provedor.CODEX, texto, listOf(rascunho, anterior, custodia, posterior, bloqueado)) { error("não cria") }
        assertEquals(custodia.id, progresso.artefatoDeCustodiaId)
        assertEquals(posterior.id, progresso.artefatoAnteriorId)
        assertEquals(2, progresso.rodada)
        assertEquals(5, progresso.turnoDoArtefato)
        assertEquals(listOf("artifact-p", "artifact-c", "artifact-d"), progresso.relatorios.map { it.artefato })
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
    }
}
