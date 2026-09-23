package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contrato de saída do turno serial.
 *
 * A primeira parte é a suíte canônica, portada de
 * `maestro-app/src-tauri/src/session_orchestration.rs` em `3c8babc`
 * (3946–4608): os casos que exercem `validate_serial_turn_output`,
 * `validate_serial_revised_content_lock` e `validate_final_release_candidate`.
 * Os que dependem de `final_release_audit_failure` — ABNT, links e decisões de
 * turno sem revisão — vão com a MAEANDR-18.
 *
 * Duas adaptações, declaradas no próprio caso: a mensagem de campo duplicado é
 * do Jackson ("Duplicate field"), e o caso corrigido da trava ganha
 * `revised_block_origins`, que este repositório exige quando o texto revisado
 * tem bloco editado (Discussion #41).
 */
class TurnoSerialTest {

    private fun saida(stdout: String, status: String): TurnoSerial.Saida =
        when (val resultado = TurnoSerial.validar(stdout, status)) {
            is TurnoSerial.Resultado.Valido -> resultado.saida
            is TurnoSerial.Resultado.Violado ->
                fail("esperava turno válido, e o contrato recusou: ${resultado.motivo}")
        }

    private fun motivo(stdout: String, status: String): String =
        when (val resultado = TurnoSerial.validar(stdout, status)) {
            is TurnoSerial.Resultado.Violado -> resultado.motivo
            is TurnoSerial.Resultado.Valido -> fail("esperava recusa, e o turno foi aceito")
        }

    // -- Suíte canônica ------------------------------------------------------

    @Test
    fun `turno READY inalterado sem texto final e aceito`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }
</maestro_revision_report>"""

        assertNull(saida(stdout, "READY").textoFinal)
    }

    @Test
    fun `relatorio YAML de turno inalterado e recusado`() {
        val stdout = "MAESTRO_STATUS: READY\n<maestro_revision_report>\ncustody: unchanged\n" +
            "changes: []\n</maestro_revision_report>"

        assertContains(motivo(stdout, "READY"), "JSON")
    }

    @Test
    fun `custody duplicado e recusado antes de transferir a custodia`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{"custody":"unchanged","custody":"revised","changes":[]}
</maestro_revision_report>
<maestro_final_text>Revisado.</maestro_final_text>"""

        // O canônico aceita "duplicate" ou "duplicad" em minúscula; o Jackson
        // escreve "Duplicate field".
        assertContains(motivo(stdout, "READY"), "duplicate", ignoreCase = true)
    }

    private val rascunhoComPendencia =
        "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."

    @Test
    fun `trava do turno recusa bloco recebido alterado sem declaracao`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{
  "reviewer": "gemini",
  "status": "READY",
  "changed_blocks": [
    {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
  ],
  "custody": "revised"
}
</maestro_revision_report>
<maestro_final_text>
# Titulo

Paragrafo aprovado encurtado.

Referencia removida.
</maestro_final_text>"""

        val veredito = TurnoSerial.validarTrava(rascunhoComPendencia, saida(stdout, "READY"))

        val violada = veredito as? TravaDeConteudo.Veredito.Violada
            ?: fail("esperava violação da trava, e a revisão foi aprovada")
        assertContains(violada.motivo, "B0002")
    }

    @Test
    fun `violacao da trava so deixa de valer quando o mesmo revisor corrige`() {
        val invalido = """MAESTRO_STATUS: READY
<maestro_revision_report>
{
  "reviewer": "gemini",
  "status": "READY",
  "changed_blocks": [
    {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
  ],
  "custody": "revised"
}
</maestro_revision_report>
<maestro_final_text>
# Titulo

Paragrafo aprovado encurtado.

Referencia removida.
</maestro_final_text>"""
        assertTrue(
            TurnoSerial.validarTrava(rascunhoComPendencia, saida(invalido, "READY"))
                is TravaDeConteudo.Veredito.Violada,
        )

        // Adaptação: o canônico corrige só declarando B0002. Aqui o texto
        // revisado tem blocos editados, e o registro de procedência é exigido.
        val corrigido = """MAESTRO_STATUS: READY
<maestro_revision_report>
{
  "reviewer": "gemini",
  "status": "READY",
  "changed_blocks": [
    {"block_id": "B0002", "protocol_basis": "depth preservation", "required": true},
    {"block_id": "B0003", "protocol_basis": "bibliographic integrity", "required": true}
  ],
  "revised_block_origins": [
    {"prefix": "# Titulo", "origin": "B0001"},
    {"prefix": "Paragrafo aprovado encurtado.", "origin": "B0002"},
    {"prefix": "Referencia removida.", "origin": "B0003"}
  ],
  "custody": "revised"
}
</maestro_revision_report>
<maestro_final_text>
# Titulo

Paragrafo aprovado encurtado.

Referencia removida.
</maestro_final_text>"""
        assertEquals(
            TravaDeConteudo.Veredito.Aprovada,
            TurnoSerial.validarTrava(rascunhoComPendencia, saida(corrigido, "READY")),
        )
    }

    @Test
    fun `NOT_READY inalterado com mudanca corrigivel e recusado`() {
        val stdout = """MAESTRO_STATUS: NOT_READY
<maestro_revision_report>
{
  "reviewer": "codex",
  "status": "NOT_READY",
  "custody": "unchanged",
  "changes": [
    {
      "line_range": "12-14",
      "issue": "unsupported claim can be removed with supplied text",
      "action": "remove the unsupported clause",
      "required": true
    }
  ]
}
</maestro_revision_report>"""

        assertContains(motivo(stdout, "NOT_READY"), "correctable")
    }

    @Test
    fun `evidencia do operador fica registrada no turno inalterado`() {
        val stdout = """MAESTRO_STATUS: NOT_READY
<maestro_revision_report>
{ "reviewer": "claude", "status": "NOT_READY", "custody": "unchanged", "changes": [], "operator_evidence_required": [{ "issue": "missing source", "required": true }] }
</maestro_revision_report>"""

        assertTrue(saida(stdout, "NOT_READY").exigeEvidenciaDoOperador)
    }

    @Test
    fun `NOT_READY sem custody unchanged nao pausa por evidencia do operador`() {
        val stdout = """MAESTRO_STATUS: NOT_READY
<maestro_revision_report>
{ "reviewer": "claude", "status": "NOT_READY", "changes": [], "operator_evidence_required": [{ "issue": "missing source", "required": true }] }
</maestro_revision_report>"""

        assertContains(motivo(stdout, "NOT_READY"), "custody unchanged")
    }

    @Test
    fun `custody escrito em prosa de outro campo nao conta`() {
        val stdout = """MAESTRO_STATUS: NOT_READY
<maestro_revision_report>
{
  "reviewer": "claude",
  "status": "NOT_READY",
  "summary": "custody: unchanged",
  "changes": [],
  "operator_evidence_required": [{ "issue": "missing source", "required": true }]
}
</maestro_revision_report>"""

        assertContains(motivo(stdout, "NOT_READY"), "custody unchanged")
    }

    @Test
    fun `texto final truncado e recusado`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "revised", "changes": [] }
</maestro_revision_report>
<maestro_final_text>
Texto incompleto"""

        assertContains(motivo(stdout, "READY"), "maestro_final_text")
    }

    @Test
    fun `texto final READY com lacunas bibliograficas e recusado`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "revised", "changes": [] }
</maestro_revision_report>
<maestro_final_text>
---
title: Teste
---

# Teste

Texto com referencia incompleta (AUTOR, [s. d.]).

## Referencias bibliograficas

AUTOR. Obra. [Edicao consultada nao identificada]. [S. l.: s. n.], [s. d.].
</maestro_final_text>"""

        assertContains(motivo(stdout, "READY"), "bibliographic")
    }

    @Test
    fun `revisao NOT_READY com marcador de evidencia pendente e recusada`() {
        val stdout = """MAESTRO_STATUS: NOT_READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "NOT_READY", "custody": "revised", "changes": [{ "line_range": "12-14", "issue": "evidence still pending", "action": "preserved marker for next reviewer" }] }
</maestro_revision_report>
<maestro_final_text>
# Texto em revisao

Este texto ainda contem [EVIDENCIA_PENDENTE] para que o proximo revisor resolva a lacuna antes do release.
</maestro_final_text>"""

        assertContains(motivo(stdout, "NOT_READY"), "bibliographic integrity")
    }

    @Test
    fun `eco do prompt ou do protocolo e recusado`() {
        val stdout = """MAESTRO_STATUS: READY
# Maestro Editorial AI - Serial Review-Rewrite Turn
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }
</maestro_revision_report>"""

        assertContains(motivo(stdout, "READY"), "prompt/protocol")
    }

    @Test
    fun `bloco repetido mas equilibrado e tolerado`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }
</maestro_revision_report>
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }
</maestro_revision_report>"""

        assertNull(saida(stdout, "READY").textoFinal)
    }

    @Test
    fun `bloco desequilibrado continua recusado`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }
</maestro_revision_report>
<maestro_revision_report>
{ "reviewer": "codex", "status": "READY", "custody": "unchanged", "changes": [] }"""

        assertContains(motivo(stdout, "READY"), "maestro_revision_report")
    }

    // -- Casos deste porte ---------------------------------------------------

    private fun relatorioInalterado(corpo: String) =
        "MAESTRO_STATUS: READY\n<maestro_revision_report>\n$corpo\n</maestro_revision_report>"

    @Test
    fun `status fora de READY e NOT_READY e recusado`() {
        val stdout = relatorioInalterado("""{ "custody": "unchanged", "changes": [] }""")

        assertEquals("invalid serial status: READYISH", motivo(stdout, "READYISH"))
    }

    @Test
    fun `relatorio ausente e recusado`() {
        assertContains(motivo("MAESTRO_STATUS: READY\nnada aqui", "READY"), "missing maestro_revision_report")
    }

    @Test
    fun `relatorio vazio conta como ausente`() {
        val stdout = "MAESTRO_STATUS: READY\n<maestro_revision_report>\n \n</maestro_revision_report>"

        assertContains(motivo(stdout, "READY"), "missing complete maestro_revision_report")
    }

    @Test
    fun `relatorio que nao e objeto e recusado`() {
        assertContains(
            motivo(relatorioInalterado("""[{ "custody": "unchanged" }]"""), "READY"),
            "must be one strict JSON object",
        )
    }

    @Test
    fun `texto depois do objeto e recusado`() {
        val corpo = """{ "custody": "unchanged", "changes": [] } Thanks!"""

        assertContains(motivo(relatorioInalterado(corpo), "READY"), "must be one strict JSON object")
    }

    @Test
    fun `segundo objeto depois do primeiro e recusado`() {
        // JSON válido nos dois pedaços: só o FAIL_ON_TRAILING_TOKENS do leitor
        // compartilhado recusa. Sem ele, valeria o primeiro e o segundo sumiria.
        val corpo = """{ "custody": "unchanged", "changes": [] } { "custody": "revised" }"""

        assertContains(motivo(relatorioInalterado(corpo), "READY"), "must be one strict JSON object")
    }

    @Test
    fun `status vem da linha exata, em qualquer posicao`() {
        assertEquals("READY", TurnoSerial.extrairStatus("MAESTRO_STATUS: READY\n# Titulo"))
        assertEquals("NOT_READY", TurnoSerial.extrairStatus("preambulo\r\n  maestro_status: not_ready  \r\nresto"))
        assertEquals("READY", TurnoSerial.extrairStatus("MAESTRO_STATUS: READY\nMAESTRO_STATUS: NOT_READY"))
        assertNull(TurnoSerial.extrairStatus("# Titulo\n\nMAESTRO_STATUS: READY aparece no corpo."))
        assertNull(TurnoSerial.extrairStatus("MAESTRO_STATUS:  READY"))
        assertNull(TurnoSerial.extrairStatus("MAESTRO_STATUS=READY"))
        // BOM não é espaço para o canônico: a linha não casa.
        assertNull(TurnoSerial.extrairStatus("﻿MAESTRO_STATUS: READY"))
    }

    @Test
    fun `turno so de exclusao com registro passa pelas duas pontas`() {
        // O prompt pede o registro em todo turno revisado. Se a trava
        // recusasse registro correto onde não o exige, o prompt faria trabalho
        // válido falhar.
        val antes = "Primeiro bloco intacto.\n\nSegundo bloco que sai.\n\nTerceiro bloco intacto."
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{
  "custody": "revised",
  "changes": [{"passage": "second block", "reason": "redundant", "required": true}],
  "changed_blocks": [{"block_id": "B0002", "change_type": "delete", "protocol_basis": "redundancy rule"}],
  "revised_block_origins": [
    {"prefix": "Primeiro bloco intacto.", "origin": "B0001"},
    {"prefix": "Terceiro bloco intacto.", "origin": "B0003"}
  ]
}
</maestro_revision_report>
<maestro_final_text>
Primeiro bloco intacto.

Terceiro bloco intacto.
</maestro_final_text>"""

        assertEquals(TravaDeConteudo.Veredito.Aprovada, TurnoSerial.validarTrava(antes, saida(stdout, "READY")))
    }

    @Test
    fun `custody que nao e texto e recusado`() {
        assertContains(
            motivo(relatorioInalterado("""{ "custody": ["unchanged"], "changes": [] }"""), "READY"),
            "custody must be a JSON string",
        )
    }

    @Test
    fun `custody com outra caixa nao e custody`() {
        // O serde casa nome de campo exato.
        assertContains(
            motivo(relatorioInalterado("""{ "Custody": "unchanged", "changes": [] }"""), "READY"),
            "custody unchanged",
        )
    }

    @Test
    fun `changes nulo e recusado`() {
        // `#[serde(default)]` cobre o campo ausente, não o nulo explícito.
        assertContains(
            motivo(relatorioInalterado("""{ "custody": "unchanged", "changes": null }"""), "READY"),
            "changes must be a JSON array",
        )
    }

    @Test
    fun `evidencia do operador que nao e lista e recusada`() {
        val corpo = """{ "custody": "unchanged", "changes": [], "operator_evidence_required": "none" }"""

        assertContains(
            motivo(relatorioInalterado(corpo), "READY"),
            "operator_evidence_required must be a JSON array",
        )
    }

    @Test
    fun `texto final exige custody revised`() {
        val stdout = relatorioInalterado("""{ "custody": "unchanged", "changes": [] }""") +
            "\n<maestro_final_text>\nTexto.\n</maestro_final_text>"

        assertContains(motivo(stdout, "READY"), "requires custody revised")
    }

    @Test
    fun `custody revised sem texto final e recusado`() {
        assertContains(
            motivo(relatorioInalterado("""{ "custody": "revised", "changes": [] }"""), "READY"),
            "revised custody requires a complete maestro_final_text block",
        )
    }

    @Test
    fun `vale o ultimo bloco completo`() {
        val stdout = """MAESTRO_STATUS: READY
<maestro_revision_report>
{ "custody": "revised", "changes": [] }
</maestro_revision_report>
<maestro_revision_report>
{ "custody": "unchanged", "changes": [] }
</maestro_revision_report>"""

        assertEquals("""{ "custody": "unchanged", "changes": [] }""", saida(stdout, "READY").relatorio)
    }

    @Test
    fun `turno sem texto revisado nao passa pela trava`() {
        val inalterado = saida(relatorioInalterado("""{ "custody": "unchanged", "changes": [] }"""), "READY")

        assertEquals(TravaDeConteudo.Veredito.Aprovada, TurnoSerial.validarTrava("Qualquer texto.", inalterado))
    }

    @Test
    fun `changes lista trechos e nao declara bloco para a trava`() {
        // Com o apelido antigo, `changes` valeria como `changed_blocks` e
        // autorizaria a edição do B0002.
        val relatorio = """{
  "custody": "revised",
  "changes": [{"block_id": "B0002", "protocol_basis": "clarity", "change_type": "edit"}],
  "revised_block_origins": [
    {"prefix": "Primeiro bloco intacto.", "origin": "B0001"},
    {"prefix": "Segundo bloco reescrito.", "origin": "B0002"}
  ]
}"""
        val veredito = TravaDeConteudo.validarRevisao(
            "Primeiro bloco intacto.\n\nSegundo bloco original.",
            "Primeiro bloco intacto.\n\nSegundo bloco reescrito.",
            relatorio,
        )

        val violada = veredito as? TravaDeConteudo.Veredito.Violada
            ?: fail("esperava violação da trava, e a revisão foi aprovada")
        assertContains(violada.motivo, "B0002")
    }

    @Test
    fun `eco do cabecalho deste aplicativo e recusado`() {
        val stdout = PromptsDaSessao.CABECALHO_DA_REVISAO + "\n" +
            relatorioInalterado("""{ "custody": "unchanged", "changes": [] }""")

        assertContains(motivo(stdout, "READY"), "prompt/protocol")
        assertFalse(TurnoSerial.temEcoDoPrompt("MAESTRO_STATUS: READY"))
    }
}
