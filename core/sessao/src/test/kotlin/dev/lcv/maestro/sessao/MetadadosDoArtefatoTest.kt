package dev.lcv.maestro.sessao

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** A aba Metadados: o `JSON.stringify(meta, null, 2)` do web, campo a campo. */
class MetadadosDoArtefatoTest {

    private fun resumo(custo: BigDecimal, modelo: String?, anterior: String?) = ResumoDoArtefato(
        id = "artifact-1",
        sessaoId = "android-1",
        ciclo = 2,
        turno = 5,
        agente = "codex",
        papel = "revision",
        status = "READY",
        titulo = "t",
        custoUsd = custo,
        modelo = modelo,
        artefatoAnteriorId = anterior,
        bytesDoConteudo = 1234,
        linksInvalidos = 0,
        criadoEm = "2026-09-28T10:00:00.000Z",
    )

    @Test
    fun `os onze campos na ordem do web com dois espacos de recuo`() {
        val esperado = """{
  "id": "artifact-1",
  "cycle": 2,
  "turn": 5,
  "agent": "codex",
  "role": "revision",
  "status": "READY",
  "model": "gpt-6-astra",
  "cost_usd": 0.0123,
  "previous_artifact_id": "artifact-0",
  "content_bytes": 1234,
  "created_at": "2026-09-28T10:00:00.000Z"
}"""
        assertEquals(esperado, resumo(BigDecimal("0.01230000"), "gpt-6-astra", "artifact-0").metadadosJson())
    }

    @Test
    fun `o que falta sai null e custo pequeno ou zero sai sem expoente`() {
        val json = resumo(BigDecimal("0.00000050"), null, null).metadadosJson()
        assertEquals(true, json.contains("\"model\": null,"))
        assertEquals(true, json.contains("\"previous_artifact_id\": null,"))
        assertEquals(true, json.contains("\"cost_usd\": 0.0000005,"))
        assertEquals(true, resumo(BigDecimal("0E-8"), null, null).metadadosJson().contains("\"cost_usd\": 0,"))
    }
}
