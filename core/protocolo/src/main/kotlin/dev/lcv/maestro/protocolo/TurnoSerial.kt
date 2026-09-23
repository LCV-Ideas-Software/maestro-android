package dev.lcv.maestro.protocolo

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode

/**
 * O contrato de saída de um turno serial de revisão: o que a resposta do
 * agente precisa trazer para contar como turno válido.
 *
 * Porte de `maestro-app/src-tauri/src/session_orchestration.rs` em `3c8babc`:
 * `validate_serial_turn_output` (2536–2586), `validate_serial_revised_content_lock`
 * (2588–2596), `require_balanced_tag` e `require_balanced_optional_tag`
 * (3084–3108) e `contains_prompt_or_protocol_echo` (3110–3123), com
 * `extract_tagged_block` de `editorial_io.rs:310`. As mensagens são as do
 * canônico, palavra por palavra, porque voltam ao agente como instrução.
 *
 * **Afastamentos declarados.**
 *
 * - O relatório é lido pelo mesmo leitor estrito da trava
 *   ([LeituraDoRelatorio.LEITOR]), e o erro do parser aparece pela primeira
 *   linha, como lá. O texto do erro é do Jackson, não do serde.
 * - Os três campos que o turno usa — `custody`, `changes` e
 *   `operator_evidence_required` — são lidos com a tipagem que o serde aplica
 *   a eles: nome exato, `custody` texto ou nulo, as duas listas ausentes ou
 *   lista. A forma de `changed_blocks` e do registro vem do mesmo leitor da
 *   trava ([LeituraDoRelatorio.ler]) e é conferida em todo turno. Esse leitor
 *   é um pouco mais estrito que o `serde` do canônico, que no turno só confere
 *   tipos: aqui um `block_id` malformado, um `change_type` vazio ou repetido e
 *   um bloco declarado duas vezes já recusam o turno, mesmo sem texto
 *   revisado. O canônico só os recusa quando a trava roda.
 * - Os marcadores de eco incluem o cabeçalho do prompt deste aplicativo
 *   ([PromptsDaSessao]), que o canônico não conhece.
 * - Os dois ramos do canônico que recusam bloco vazio depois de extraído não
 *   têm caminho: `extract_tagged_block` já devolve ausência para bloco vazio, e
 *   a recusa acontece como bloco ausente.
 *
 * **Este objeto valida a forma do turno, não decide o que fazer com ele.**
 * `NOT_READY` com custódia inalterada e sem mudanças passa aqui, como passa
 * no canônico, mas **não** é pausa por evidência do operador: a camada de
 * decisão do canônico (`not_ready_unchanged_release_audit_failure` e
 * `unrevised_serial_turn_runtime_action`) sempre manda o mesmo revisor
 * corrigir, até esgotar as tentativas, sem pausar. Os testes canônicos
 * dizem isso no nome: `..._retries_then_exhausts_without_pause` e
 * `..._cannot_pause_for_operator_evidence`. Essa camada chama a auditoria do
 * candidato final e é portada com ela (MAEANDR-18). O prompt de revisão diz o
 * mesmo ao agente.
 */
public object TurnoSerial {

    /** `SerialTurnOutput`. */
    public data class Saida(
        val textoFinal: String?,
        val relatorio: String,
        val exigeEvidenciaDoOperador: Boolean,
    )

    /** Resultado de [validar]. */
    public sealed interface Resultado {
        public data class Valido(val saida: Saida) : Resultado

        /** [motivo] é a mensagem canônica. */
        public data class Violado(val motivo: String) : Resultado
    }

    internal const val TAG_DO_RELATORIO = "maestro_revision_report"
    internal const val TAG_DO_TEXTO_FINAL = "maestro_final_text"

    /**
     * `extract_maestro_status` (`editorial_io.rs:283`): a primeira linha que,
     * aparada e sem diferença de caixa ASCII, é exatamente
     * `MAESTRO_STATUS: READY` ou `MAESTRO_STATUS: NOT_READY`; `null` quando
     * nenhuma é. Qualquer linha conta, não só a primeira, como no canônico.
     */
    public fun extrairStatus(saida: String): String? {
        for (linha in saida.split("\n")) {
            when (EspacoUnicode.caixaBaixaAscii(EspacoUnicode.aparar(linha))) {
                "maestro_status: ready" -> return "READY"
                "maestro_status: not_ready" -> return "NOT_READY"
            }
        }
        return null
    }

    /** `validate_serial_turn_output`. */
    public fun validar(stdout: String, status: String): Resultado {
        if (status != "READY" && status != "NOT_READY") {
            return Resultado.Violado("invalid serial status: $status")
        }
        if (temEcoDoPrompt(stdout)) {
            return Resultado.Violado("output appears to reproduce prompt/protocol scaffolding")
        }
        exigirTagEquilibrada(stdout, TAG_DO_RELATORIO)?.let { return Resultado.Violado(it) }
        exigirTagOpcionalEquilibrada(stdout, TAG_DO_TEXTO_FINAL)?.let {
            return Resultado.Violado(it)
        }

        val relatorio = extrairBloco(stdout, TAG_DO_RELATORIO)
            ?: return Resultado.Violado("missing complete $TAG_DO_RELATORIO block")
        val campos = when (val lido = lerCampos(relatorio)) {
            is Campos.Falha -> return Resultado.Violado(lido.motivo)
            is Campos.Lidos -> lido
        }
        // A forma de `changed_blocks` e do registro vale em todo turno, com ou
        // sem texto revisado: no canônico o `parse_revision_report` tipado roda
        // antes de qualquer decisão.
        when (val declaracoes = LeituraDoRelatorio.ler(relatorio)) {
            is LeituraDoRelatorio.Leitura.Invalida ->
                return Resultado.Violado("approved-content lock violation: ${declaracoes.motivo}")
            is LeituraDoRelatorio.Leitura.Ambigua ->
                return Resultado.Violado("approved-content lock violation: ${declaracoes.motivo}")
            else -> Unit
        }
        val textoFinal = extrairBloco(stdout, TAG_DO_TEXTO_FINAL)
        val custodiaRevisada = campos.custodia == "revised"
        val custodiaInalterada = campos.custodia == "unchanged"

        if (textoFinal != null) {
            if (!custodiaRevisada) {
                return Resultado.Violado(
                    "$TAG_DO_TEXTO_FINAL requires custody revised in the report",
                )
            }
            IntegridadeBibliografica.validarCandidato(textoFinal)?.let {
                return Resultado.Violado(it)
            }
        }
        if (textoFinal == null && custodiaRevisada) {
            return Resultado.Violado(
                "revised custody requires a complete $TAG_DO_TEXTO_FINAL block",
            )
        }
        if (textoFinal == null && !custodiaInalterada) {
            return Resultado.Violado(
                "$status without $TAG_DO_TEXTO_FINAL must explicitly declare custody unchanged",
            )
        }
        if (textoFinal == null && custodiaInalterada && campos.temMudancas) {
            return Resultado.Violado(
                "correctable changes require custody revised and a complete " +
                    "$TAG_DO_TEXTO_FINAL block",
            )
        }
        return Resultado.Valido(
            Saida(
                textoFinal = textoFinal,
                relatorio = relatorio,
                exigeEvidenciaDoOperador = campos.exigeEvidenciaDoOperador,
            ),
        )
    }

    /**
     * `validate_serial_revised_content_lock`: turno sem texto revisado não tem
     * o que travar.
     */
    public fun validarTrava(rascunhoAtual: String, saida: Saida): TravaDeConteudo.Veredito {
        val revisado = saida.textoFinal ?: return TravaDeConteudo.Veredito.Aprovada
        return TravaDeConteudo.validarRevisao(rascunhoAtual, revisado, saida.relatorio)
    }

    // -- Campos do relatório -----------------------------------------------

    private sealed interface Campos {
        data class Lidos(
            val custodia: String?,
            val temMudancas: Boolean,
            val exigeEvidenciaDoOperador: Boolean,
        ) : Campos

        data class Falha(val motivo: String) : Campos
    }

    private const val PREFIXO_DE_JSON =
        "approved-content lock violation: $TAG_DO_RELATORIO must be one strict JSON object"

    /** `parse_revision_report`, só nos campos que o turno usa. */
    private fun lerCampos(relatorio: String): Campos {
        val raiz = try {
            LeituraDoRelatorio.LEITOR.readTree(relatorio)
        } catch (erro: JacksonException) {
            return Campos.Falha("$PREFIXO_DE_JSON: ${LeituraDoRelatorio.primeiraLinha(erro)}")
        }
        if (raiz == null || !raiz.isObject) return Campos.Falha(PREFIXO_DE_JSON)

        val custodia = raiz.get("custody")
        if (custodia != null && !custodia.isNull && !custodia.isTextual) {
            return Campos.Falha("$PREFIXO_DE_JSON: custody must be a JSON string")
        }
        val mudancas = lista(raiz, "changes") ?: return Campos.Falha(
            "$PREFIXO_DE_JSON: changes must be a JSON array",
        )
        val evidencia = lista(raiz, "operator_evidence_required") ?: return Campos.Falha(
            "$PREFIXO_DE_JSON: operator_evidence_required must be a JSON array",
        )
        return Campos.Lidos(
            custodia = custodia?.takeIf { it.isTextual }?.textValue(),
            temMudancas = mudancas > 0,
            exigeEvidenciaDoOperador = evidencia > 0,
        )
    }

    /**
     * Tamanho da lista, zero quando ela não veio, `null` quando veio com outro
     * tipo. `null` explícito também é outro tipo: o `#[serde(default)]` do
     * canônico só cobre o campo ausente.
     */
    private fun lista(raiz: JsonNode, nome: String): Int? {
        val no = raiz.get(nome) ?: return 0
        return if (no.isArray) no.size() else null
    }

    // -- Tags ----------------------------------------------------------------

    /** `require_balanced_tag`. */
    private fun exigirTagEquilibrada(stdout: String, tag: String): String? {
        val abre = contarOcorrencias(stdout, "<$tag>")
        val fecha = contarOcorrencias(stdout, "</$tag>")
        return when {
            abre == 0 && fecha == 0 -> "missing $tag block"
            // Bloco repetido mas equilibrado é tolerado: vale o último completo.
            abre == fecha -> null
            else -> "incomplete $tag block"
        }
    }

    /** `require_balanced_optional_tag`: ausente ou equilibrado. */
    private fun exigirTagOpcionalEquilibrada(stdout: String, tag: String): String? {
        val abre = contarOcorrencias(stdout, "<$tag>")
        val fecha = contarOcorrencias(stdout, "</$tag>")
        return if (abre == fecha) null else "incomplete $tag block"
    }

    /** Ocorrências sem sobreposição, como o `str::matches(..).count()` do Rust. */
    private fun contarOcorrencias(texto: String, agulha: String): Int {
        var total = 0
        var indice = texto.indexOf(agulha)
        while (indice >= 0) {
            total++
            indice = texto.indexOf(agulha, indice + agulha.length)
        }
        return total
    }

    /**
     * `extract_tagged_block`: o conteúdo do **último** par completo, aparado,
     * ou `null` quando não há par ou ele está vazio.
     */
    internal fun extrairBloco(saida: String, tag: String): String? {
        val abertura = "<$tag>"
        val fechamento = "</$tag>"
        val fecha = saida.lastIndexOf(fechamento)
        if (fecha < 0) return null
        val abre = saida.substring(0, fecha).lastIndexOf(abertura)
        if (abre < 0) return null
        val valor = EspacoUnicode.aparar(saida.substring(abre + abertura.length, fecha))
        return valor.ifEmpty { null }
    }

    // -- Eco do prompt -----------------------------------------------------

    /**
     * Os marcadores do canônico, mais o cabeçalho do prompt de revisão deste
     * aplicativo. O primeiro e o último do canônico não aparecem no prompt
     * daqui, que vem do web; ficam porque recusar eco a mais não custa nada.
     */
    internal val MARCADORES_DE_ECO: List<String> = listOf(
        "# maestro editorial ai - serial review-rewrite turn",
        "## full editorial protocol",
        "## required output contract",
        "## sovereign approved-content lock",
        "## current text under custody",
        "## prior serial revision reports",
        "internal coordination, critique, changelog, and revision report",
        EspacoUnicode.caixaBaixaAscii(PromptsDaSessao.CABECALHO_DA_REVISAO),
    )

    /** `contains_prompt_or_protocol_echo`. */
    internal fun temEcoDoPrompt(stdout: String): Boolean {
        val normalizado = EspacoUnicode.caixaBaixaAscii(stdout)
        return MARCADORES_DE_ECO.any { normalizado.contains(it) }
    }
}
