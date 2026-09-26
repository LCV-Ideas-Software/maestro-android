package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `SessionEvent` como linha da tabela `eventos`: ida e volta, e leitura tolerante do que é JSON. */
class EventosTest {

    private val evento = EventoDaSessao(
        em = Fixtures.ISO_AGORA, status = EventoDaSessao.PRONTO, mensagem = "ok", agente = Provedor.GROK, papel = "revision",
        custoUsd = BigDecimal("0.00012345"), fonteDoCusto = Custo.Fonte.ESTIMATIVA, modelo = "grok-4.7",
        auditoriaDeLinks = listOf(Fixtures.linha("l1", tom = "error")),
        auditoriaFinal = Json.ESTRITO.createObjectNode().put("stage", "abnt"),
    )

    @Test
    fun `evento inteiro vai e volta pela linha`() {
        val linha = evento.paraEntidade("android-1")
        assertEquals("android-1", linha.sessaoId)
        assertEquals("grok", linha.agente)
        assertEquals("estimate", linha.fonteDoCusto)
        assertEquals(12345L, linha.custoE8)
        assertEquals(evento, linha.paraEvento())
    }

    @Test
    fun `campos json ilegiveis viram nulo e agente desconhecido vira nulo`() {
        val linha = evento.paraEntidade("android-1").copy(agente = "bing", auditoriaDeLinksJson = "lixo", auditoriaFinalJson = "[1]", fonteDoCusto = "x")
        val lido = linha.paraEvento()
        assertNull(lido.agente)
        assertEquals(emptyList(), lido.auditoriaDeLinks)
        assertNull(lido.auditoriaFinal)
        assertNull(lido.fonteDoCusto)
    }

    @Test
    fun `evento minimo nao leva custo nem auditoria`() {
        val linha = EventoDaSessao(em = "x", status = EventoDaSessao.NA_FILA, mensagem = "m").paraEntidade("android-1")
        assertNull(linha.custoE8)
        assertNull(linha.auditoriaDeLinksJson)
        assertNull(linha.auditoriaFinalJson)
        assertNull(linha.paraEvento().custoUsd)
    }
}
