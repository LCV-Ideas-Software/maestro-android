package dev.lcv.maestro.protocolo

import java.net.URI
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A auditoria do candidato final, com os testes de `session_orchestration.rs`
 * (linhas 4194, 4258, 4609, 4618 e 4632 em `68528f9`). O motor de links corre
 * com dublês de parser, coleta e registro; o parser e a coleta reais são do
 * `:core:provedores`.
 */
class AuditoriaFinalTest {

    private val agora: Instant = Instant.parse("2026-09-24T12:00:00Z")

    private val analisador = IntegridadeDeLinks.AnalisadorDeUrl { url ->
        runCatching { URI(url) }.getOrNull()?.let { uri ->
            uri.scheme?.let { esquema ->
                IntegridadeDeLinks.UrlAnalisada(esquema.lowercase(), uri.host, "", null, uri.rawPath ?: "", url)
            }
        }
    }

    private val registro = object : IntegridadeDeLinks.RegistroDeLinks {
        val linhas = HashMap<String, LinhaDeLink>()
        override fun <T> emTransacao(bloco: () -> T): T = bloco()
        override fun carregar(linkId: String) = linhas[linkId]
        override fun salvar(linha: LinhaDeLink) {
            linhas[linha.linkId] = linha
        }
        override fun anotar(tipo: String, linha: LinhaDeLink) = Unit
        override fun todos() = linhas.values.toList()
    }

    private var chamadasAoMotor = 0

    /** A coleta recusa `localhost` como o motor de evidências do canônico. */
    private val motor = AuditoriaFinal.MotorDeLinks { texto ->
        chamadasAoMotor++
        IntegridadeDeLinks.auditar(
            texto,
            analisador,
            { url ->
                if ("localhost" in url) throw IntegridadeDeLinks.Falha("endereco local bloqueado por seguranca")
                fail("o teste não coleta URL pública: $url")
            },
            registro,
        ) { agora }
    }

    private fun saida(stdout: String, status: String): TurnoSerial.Saida =
        when (val resultado = TurnoSerial.validar(stdout, status)) {
            is TurnoSerial.Resultado.Valido -> resultado.saida
            is TurnoSerial.Resultado.Violado -> fail(resultado.motivo)
        }

    private val pendente = "Texto ainda contem [EVIDENCIA_PENDENTE]."

    /** A sessão sem manifesto: a auditoria de `final_release_audit_failure`. */
    private val semCitacoes = AuditoriaFinal.ContextoDeCitacoes(null, null, null)

    private fun gate(falha: AuditoriaFinal.Falha): String =
        ((falha.contexto as ValorJson.Objeto).campos["gate"] as ValorJson.Texto).valor

    private fun prontoSemMudanca(): TurnoSerial.Saida = saida(
        "MAESTRO_STATUS: READY\n<maestro_revision_report>\n" +
            "{ \"reviewer\": \"grok\", \"status\": \"READY\", \"custody\": \"unchanged\", \"changes\": [] }\n" +
            "</maestro_revision_report>",
        "READY",
    )

    @Test
    fun `READY sem mudanca com rascunho bloqueado nao conta como agente valido`() {
        val saida = prontoSemMudanca()
        val falha = assertNotNull(AuditoriaFinal.falhaDeProntoSemMudanca("READY", saida, pendente, semCitacoes, motor, agora))
        assertTrue(falha.motivo.contains("bibliographic integrity"))
        assertNull(AuditoriaFinal.falhaDeProntoSemMudanca("READY", saida, "Texto limpo.", semCitacoes, motor, agora))
        assertNull(AuditoriaFinal.falhaDeProntoSemMudanca("NOT_READY", saida, pendente, semCitacoes, motor, agora))
        assertFalse(AuditoriaFinal.contaComoAgenteValidoDaRodada("READY", saida, pendente, semCitacoes, motor, agora))
        assertTrue(AuditoriaFinal.contaComoAgenteValidoDaRodada("READY", saida, "Texto limpo.", semCitacoes, motor, agora))
        assertFalse(AuditoriaFinal.contaComoAgenteValidoDaRodada("NOT_READY", saida, pendente, semCitacoes, motor, agora))
    }

    @Test
    fun `NOT_READY sem mudanca exige repeticao corretiva mesmo com rascunho limpo`() {
        val saida = saida(
            "MAESTRO_STATUS: NOT_READY\n<maestro_revision_report>\n" +
                "{ \"reviewer\": \"claude\", \"status\": \"NOT_READY\", \"custody\": \"unchanged\", \"changes\": [], " +
                "\"operator_evidence_required\": [] }\n</maestro_revision_report>",
            "NOT_READY",
        )
        val bloqueada = assertNotNull(AuditoriaFinal.falhaDeNaoProntoSemMudanca("NOT_READY", saida, pendente, semCitacoes, motor, agora))
        assertTrue(bloqueada.motivo.contains("bibliographic integrity"))
        val limpa = assertNotNull(AuditoriaFinal.falhaDeNaoProntoSemMudanca("NOT_READY", saida, "Texto limpo.", semCitacoes, motor, agora))
        assertTrue(limpa.motivo.contains("NOT_READY unchanged"))
        assertFalse(AuditoriaFinal.contaComoAgenteValidoDaRodada("NOT_READY", saida, pendente, semCitacoes, motor, agora))
        assertFalse(AuditoriaFinal.contaComoAgenteValidoDaRodada("NOT_READY", saida, "Texto limpo.", semCitacoes, motor, agora))
        val decisao = assertNotNull(AuditoriaFinal.decisaoDoTurnoSemRevisao("NOT_READY", saida, "Texto limpo.", semCitacoes, motor, agora))
        assertEquals(AuditoriaFinal.DecisaoDoTurnoSemRevisao.REPETICAO_CORRETIVA_EXIGIDA, decisao.decisao)
    }

    @Test
    fun `READY sem mudanca com manifesto valido conta como agente valido`() {
        // Divergência do canônico: lá os auxiliares auditavam sem manifesto, e
        // este revisor era recusado por `structured_manifest_missing`.
        val saida = prontoSemMudanca()
        val citacoes = AuditoriaFinal.ContextoDeCitacoes("protocol-sha256", manifestoVerificado(), null)
        assertNull(AuditoriaFinal.falhaDeProntoSemMudanca("READY", saida, textoVerificado, citacoes, motor, agora))
        assertTrue(AuditoriaFinal.contaComoAgenteValidoDaRodada("READY", saida, textoVerificado, citacoes, motor, agora))
        assertNull(AuditoriaFinal.decisaoDoTurnoSemRevisao("READY", saida, textoVerificado, citacoes, motor, agora))
        // Controle: sem o manifesto, o mesmo texto é recusado no estágio ABNT.
        val recusa = assertNotNull(
            AuditoriaFinal.falhaDeProntoSemMudanca("READY", saida, textoVerificado, semCitacoes, motor, agora),
        )
        assertEquals("abnt_citation", gate(recusa))
    }

    @Test
    fun `link bloqueado reprova a auditoria sem ir a rede`() {
        val falha = assertNotNull(AuditoriaFinal.falha("Referencia: http://localhost:8787/test", motor, agora))
        assertTrue(falha.motivo.contains("link-integrity"))
        assertEquals("link_integrity", gate(falha))
    }

    @Test
    fun `excesso de links reprova antes do motor`() {
        val texto = (0 until 31).joinToString("\n") { "https://example.com/reference-$it" }
        val falha = assertNotNull(AuditoriaFinal.falha(texto, motor, agora))
        assertTrue(falha.motivo.contains("capacity"))
        assertEquals("link_audit_capacity", gate(falha))
        assertEquals(0, chamadasAoMotor)
    }

    @Test
    fun `link relativo interno do markdown e ignorado`() {
        assertNull(AuditoriaFinal.falha("Leia [a secao interna](#secao-interna).", motor, agora))
    }

    @Test
    fun `citacao sem manifesto reprova no estagio ABNT`() {
        val falha = assertNotNull(
            AuditoriaFinal.falha(
                "Texto (Silva, 2020).\n\n## Referencias\nSILVA, Ana. Obra. Rio: Editora, 2020.",
                motor,
                agora,
            ),
        )
        assertEquals("abnt_citation", gate(falha))
        assertEquals(0, chamadasAoMotor)
    }

    @Test
    fun `o primeiro estagio que falha decide`() {
        val falha = assertNotNull(AuditoriaFinal.falha("Texto (Silva, 2020) com [EVIDENCIA_PENDENTE].", motor, agora))
        assertEquals("bibliographic_integrity", gate(falha))
    }

    @Test
    fun `motor de links que falha reprova fechado`() {
        val quebrado = AuditoriaFinal.MotorDeLinks { throw IntegridadeDeLinks.Falha("disco cheio") }
        val falha = assertNotNull(AuditoriaFinal.falha("Texto limpo.", quebrado, agora))
        assertEquals("link_integrity_engine", gate(falha))
    }

    @Test
    fun `evidencia do operador pausa antes do proximo revisor pago`() {
        val falha = assertNotNull(
            AuditoriaFinal.falhaDeEvidenciaDoOperador(
                "Texto com nota bibliografica[^1].\n\n[^1]: Fonte consultada.",
                "h",
                AuditoriaAbnt.manifestoVazio("h"),
                null,
                agora,
            ),
        )
        assertEquals("abnt_citation_operator_evidence", gate(falha))
        assertNull(AuditoriaFinal.falhaDeEvidenciaDoOperador("Texto limpo.", "h", AuditoriaAbnt.manifestoVazio("h"), null, agora))
    }

    @Test
    fun `o pacote vai ao prompt no formato do to_string_pretty com chaves em ordem`() {
        val pacote = ValorJson.objeto(
            "policy" to ValorJson.texto("p"),
            "gate" to ValorJson.texto("g"),
            "rows" to ValorJson.Lista(listOf(ValorJson.objeto("b" to ValorJson.numero(1), "a" to ValorJson.Nulo))),
            "vazia" to ValorJson.Lista(emptyList()),
            "objeto" to ValorJson.objeto(),
        ).bonito()
        assertEquals(
            "{\n" +
                "  \"gate\": \"g\",\n" +
                "  \"objeto\": {},\n" +
                "  \"policy\": \"p\",\n" +
                "  \"rows\": [\n" +
                "    {\n" +
                "      \"a\": null,\n" +
                "      \"b\": 1\n" +
                "    }\n" +
                "  ],\n" +
                "  \"vazia\": []\n" +
                "}",
            pacote,
        )
    }
}
