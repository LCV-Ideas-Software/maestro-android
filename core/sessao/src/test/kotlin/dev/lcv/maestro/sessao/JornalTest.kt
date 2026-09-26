package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `parseSessionEventsStrict`, `parseJson(events_json, [])` e o acréscimo no nível do nó. */
class JornalTest {

    private val evento = EventoDaSessao(
        em = Fixtures.ISO_AGORA, status = EventoDaSessao.PRONTO, mensagem = "ok", agente = Provedor.GROK, papel = "revision",
        custoUsd = BigDecimal("0.00012345"), fonteDoCusto = Custo.Fonte.ESTIMATIVA, modelo = "grok-4.7",
        auditoriaDeLinks = listOf(Fixtures.linha("l1", tom = "error")),
        auditoriaFinal = Json.ESTRITO.createObjectNode().put("stage", "abnt"),
    )

    @Test
    fun `leitura estrita reproduz as mensagens do web`() {
        val invalido = assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.lerEstrito("[{\"at\":1}") }
        assertTrue(invalido.message!!.startsWith("Session event journal contains invalid JSON: "))
        val naoLista = assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.lerEstrito("{}") }
        assertEquals("Session event journal must be a JSON array.", naoLista.message)
        val semCampos = assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.lerEstrito("[{\"at\":1}]") }
        assertEquals("Session event journal contains an event without at, status and message.", semCampos.message)
        val duplicado = assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.lerEstrito("[{\"at\":\"x\",\"at\":\"y\"}]") }
        assertTrue(duplicado.message!!.startsWith("Session event journal contains invalid JSON: "))
        assertEquals(emptyList(), Jornal.lerEstrito("[]"))
    }

    @Test
    fun `leitura tolerante devolve lista vazia ou pula o evento malformado`() {
        assertEquals(emptyList(), Jornal.lerTolerante("nada"))
        assertEquals(emptyList(), Jornal.lerTolerante(null))
        assertEquals(emptyList(), Jornal.lerTolerante("{}"))
        val lidos = Jornal.lerTolerante("[{\"at\":1},{\"at\":\"a\",\"status\":\"queued\",\"message\":\"m\"}]")
        assertEquals(listOf("m"), lidos.map { it.mensagem })
    }

    @Test
    fun `evento inteiro vai e volta com os nomes do web`() {
        val texto = Jornal.serializar(listOf(evento))
        val no = Json.ESTRITO.readTree(texto)[0]
        assertEquals("grok", no.get("agent").textValue())
        assertEquals("estimate", no.get("cost_source").textValue())
        assertEquals(BigDecimal("0.00012345"), no.get("cost_usd").decimalValue())
        assertEquals("l1", no.get("link_audit")[0].get("link_id").textValue())
        assertEquals("abnt", no.get("final_audit").get("stage").textValue())
        assertEquals(listOf(evento), Jornal.lerEstrito(texto))
    }

    @Test
    fun `anexar preserva um campo que o modelo nao conhece`() {
        val jornal = "[{\"at\":\"2026-09-25T00:00:00.000Z\",\"status\":\"queued\",\"message\":\"m\",\"campo_novo\":{\"x\":[1,2]}}]"
        val novo = Jornal.anexar(jornal, EventoDaSessao(em = Fixtures.ISO_AGORA, status = EventoDaSessao.RODANDO, mensagem = "n"))
        val lista = Json.ESTRITO.readTree(novo)
        assertEquals(2, lista.size())
        assertEquals(2, lista[0].get("campo_novo").get("x")[1].intValue())
        assertEquals("n", lista[1].get("message").textValue())
    }

    @Test
    fun `anexar num jornal corrompido falha em vez de recomecar o jornal`() {
        assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.anexar("{}", EventoDaSessao(em = "x", status = "queued", mensagem = "m")) }
        assertFailsWith<IntegridadeDeLinks.Falha> { Jornal.anexar("[", EventoDaSessao(em = "x", status = "queued", mensagem = "m")) }
    }
}
