package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompts da sessão. O que se fixa aqui é o contrato entre as duas pontas: o
 * prompt tem de pedir exatamente o que a trava e o validador do turno cobram.
 */
class PromptsDaSessaoTest {

    private val pedido = PromptsDaSessao.PedidoDaSessao(
        titulo = "Ensaio sobre custódia",
        pedido = "Revise o ensaio.",
        conteudoInicial = "",
        protocolo = "# Protocolo\n\n§1 Regra.",
    )

    private val textoAtual = "# Titulo\n\nParagrafo um.\n\nParagrafo dois."

    private fun revisao(relatorios: List<PromptsDaSessao.RelatorioDeTurno> = emptyList()) =
        PromptsDaSessao.revisao(
            pedido = pedido,
            execucao = "run-1",
            turno = 2,
            textoAtual = textoAtual,
            autorAtual = "claude",
            revisor = "codex",
            relatoriosAnteriores = relatorios,
            turnoDeFechamento = false,
        )

    @Test
    fun `revisao pede o registro de procedencia que a trava cobra`() {
        assertContains(revisao(), InstrucaoDoRegistro.TEXTO)
    }

    @Test
    fun `revisao nao pede campo que a trava daqui nao le`() {
        // O prompt do web manda usar `new_block_count`; a trava deste
        // repositório decide crescimento pelo registro e ignoraria o campo.
        assertFalse(revisao().contains("new_block_count"))
    }

    @Test
    fun `revisao nao promete auditoria de links que ainda nao existe`() {
        // A frase do web volta quando a auditoria entrar (MAEANDR-18); antes
        // disso, o agente contaria com uma checagem ausente.
        assertFalse(revisao().contains("audits public links"))
        assertContains(revisao(), "Do not fabricate URLs.")
    }

    @Test
    fun `revisao diz a forma que a trava cobra`() {
        // A trava recusa token que não é exato, lista vazia ou repetida e ID
        // reescrito. O agente precisa saber disso antes de gastar uma nova
        // tentativa paga numa regra que ninguém lhe disse.
        assertContains(revisao(), "exactly as the manifest shows it")
        assertContains(
            revisao(),
            "change_type is one exact token or a non-empty list of distinct tokens; " +
                "only \"addition\", \"split\" and \"reorder\" grant permission.",
        )
    }

    @Test
    fun `revisao leva o manifesto do texto atual`() {
        assertContains(revisao(), TravaDeConteudo.formatarManifestoParaPrompt(textoAtual))
    }

    @Test
    fun `cada secao do prompt de revisao e marcador de eco`() {
        val prompt = EspacoUnicode.caixaBaixaAscii(revisao())
        val presentes = TurnoSerial.MARCADORES_DE_ECO.filter { prompt.contains(it) }

        // O cabeçalho deste aplicativo e as cinco seções que o canônico vigia.
        assertEquals(6, presentes.size, "marcadores presentes: $presentes")
    }

    @Test
    fun `prompt de revisao devolvido pelo agente e recusado como eco`() {
        val stdout = "MAESTRO_STATUS: READY\n" + revisao() +
            "\n<maestro_revision_report>\n{ \"custody\": \"unchanged\", \"changes\": [] }\n" +
            "</maestro_revision_report>"

        val resultado = TurnoSerial.validar(stdout, "READY")

        assertTrue(resultado is TurnoSerial.Resultado.Violado)
        assertContains(resultado.motivo, "prompt/protocol")
    }

    @Test
    fun `rascunho sem conteudo inicial diz que nao ha`() {
        val prompt = PromptsDaSessao.rascunho(pedido, "run-1")

        assertTrue(prompt.startsWith(PromptsDaSessao.CABECALHO_DO_RASCUNHO))
        assertContains(prompt, "## Existing Editor Content\n\nNo existing editor content was provided.")
        assertContains(prompt, "```markdown\n# Protocolo\n\n§1 Regra.\n```\n")
    }

    @Test
    fun `titulo perde NUL, espaco das pontas e passa de 200 pontos de codigo`() {
        val longo = " Ti\u0000tulo " + "😀".repeat(250)
        val prompt = PromptsDaSessao.rascunho(pedido.copy(titulo = longo), "run-1")

        val linha = prompt.lines().single { it.startsWith("Session: ") }.removePrefix("Session: ")
        assertTrue(linha.startsWith("Titulo "))
        assertEquals(200, EspacoUnicode.contarPontosDeCodigo(linha))
        assertFalse(Character.isHighSurrogate(linha.last()), "par substituto partido")
    }

    @Test
    fun `tentativa corretiva diz o numero e o teto`() {
        assertContains(
            PromptsDaSessao.secaoDeTentativaCorretiva(2),
            "This is corrective retry 2/3 for this same reviewer turn.",
        )
    }

    // -- Histórico -----------------------------------------------------------

    private fun turno(nome: String, status: String, relatorio: String?) =
        PromptsDaSessao.RelatorioDeTurno(nome, "review", status, relatorio, "artefato-$nome")

    @Test
    fun `historico vazio diz que nao ha relatorios`() {
        assertEquals(
            "No prior revision reports are recorded for this serial cycle.",
            PromptsDaSessao.historicoDeRevisoes(emptyList()),
        )
    }

    @Test
    fun `historico deixa de fora tentativa recusada e falha operacional`() {
        val historico = PromptsDaSessao.historicoDeRevisoes(
            listOf(
                turno("claude", "READY", "{\"ok\": 1}"),
                turno("grok", "CONTRACT_VIOLATION", "{\"ruim\": 1}"),
                turno("deepseek", "COST_LIMIT_REACHED", null),
            ),
        )

        assertContains(historico, "### claude / review / `READY`")
        assertFalse(historico.contains("grok"))
        assertFalse(historico.contains("deepseek"))
    }

    @Test
    fun `turno sem relatorio entra como falha de contrato`() {
        assertContains(
            PromptsDaSessao.historicoDeRevisoes(listOf(turno("gemini", "NOT_READY", null))),
            "No complete maestro_revision_report block was returned by gemini.",
        )
    }

    @Test
    fun `historico fica em ordem cronologica`() {
        val historico = PromptsDaSessao.historicoDeRevisoes(
            listOf(turno("primeiro", "READY", "{}"), turno("segundo", "READY", "{}")),
        )

        assertTrue(historico.indexOf("primeiro") < historico.indexOf("segundo"))
    }

    @Test
    fun `cada relatorio tem teto de 8000 pontos de codigo`() {
        val historico = PromptsDaSessao.historicoDeRevisoes(
            listOf(turno("claude", "READY", "😀".repeat(9_000))),
        )

        val corpo = historico.substringAfter("```text\n").substringBefore("\n```")
        assertEquals(8_000, EspacoUnicode.contarPontosDeCodigo(corpo))
    }

    @Test
    fun `teto global prefere os relatorios mais recentes`() {
        val turnos = (1..8).map { turno("agente$it", "READY", "x".repeat(8_000)) }

        val historico = PromptsDaSessao.historicoDeRevisoes(turnos)

        assertTrue(EspacoUnicode.contarPontosDeCodigo(historico) <= 48_000)
        assertContains(historico, "### agente8 /")
        assertFalse(historico.contains("### agente1 /"))
    }
}
