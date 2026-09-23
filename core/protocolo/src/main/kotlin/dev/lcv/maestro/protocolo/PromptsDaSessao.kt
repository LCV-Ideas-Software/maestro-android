package dev.lcv.maestro.protocolo

/**
 * Montagem dos prompts da sessão editorial: o pedido de rascunho, o turno
 * serial de revisão, o histórico de relatórios e a seção de nova tentativa
 * corretiva.
 *
 * **A fonte é o web, não o desktop.** O `sessions.ts` do `admin-app` tem a
 * variante só por API (`buildDraftPrompt`, `buildRevisionPrompt`,
 * `buildRevisionHistoryBlock` e `correctiveRetrySection`, linhas 1705, 2081,
 * 2145 e 2173 em 23/09/2026). O `editorial_prompts.rs` monta prompts para
 * CLI, com argumentos de linha de comando e guardas por CLI que aqui não
 * existem. O bloco de histórico o web declara como porte exato do desktop.
 *
 * **Afastamentos declarados**, todos para dizer ao agente a verdade sobre este
 * aplicativo:
 *
 * - "Web/API" vira "Android/API", e "web module"/"web engine" vira "Android
 *   app".
 * - O item `changed_blocks` do contrato de saída descreve a trava deste
 *   repositório. O do web manda usar `new_block_count`, que a trava daqui não
 *   lê: aqui o crescimento é decidido pelo registro de procedência, e a seção
 *   *Revised Block Origins* leva o texto de [InstrucaoDoRegistro].
 * - O título é saneado como no `sanitizeText` do web (sem NUL, aparado, até
 *   200), contando pontos de código para não partir um par substituto, e
 *   aparado pela classe de espaço deste módulo ([EspacoUnicode]).
 */
public object PromptsDaSessao {

    /** `MAX_CORRECTIVE_CONTRACT_RETRIES_PER_TURN` do canônico. */
    public const val MAX_TENTATIVAS_CORRETIVAS_POR_TURNO: Int = 3

    internal const val CABECALHO_DO_RASCUNHO = "# Maestro Editorial AI - Android/API Draft Request"
    internal const val CABECALHO_DA_REVISAO =
        "# Maestro Editorial AI - Android/API Serial Review-Rewrite Turn"

    /** O que a sessão pediu. Os textos chegam já saneados pela entrada. */
    public data class PedidoDaSessao(
        val titulo: String,
        val pedido: String,
        val conteudoInicial: String,
        val protocolo: String,
    )

    /**
     * Um turno serial já concluído, para o histórico. [relatorio] é o
     * `maestro_revision_report` do turno, ou `null` quando o agente não
     * devolveu um; [artefato] é a referência do artefato gravado.
     */
    public data class RelatorioDeTurno(
        val nome: String,
        val papel: String,
        val status: String,
        val relatorio: String?,
        val artefato: String,
    )

    /**
     * Estados que não são deliberação: tentativa recusada ou falha
     * operacional. Ficam como evidência, fora do histórico que instrui o
     * próximo revisor.
     */
    private val STATUS_NAO_DELIBERATIVOS = setOf(
        "CONTRACT_VIOLATION",
        "QUALITY_GUARD_REJECTED",
        "READY_REJECTED",
        "RUNNING",
        "AGENT_FAILED_NO_OUTPUT",
        "AGENT_FAILED_EMPTY",
        "STOPPED_BY_USER",
        "COST_LIMIT_REACHED",
    )

    /** `correctiveRetrySection`. */
    public fun secaoDeTentativaCorretiva(tentativa: Int): String = listOf(
        "\n\n## Mandatory Corrective Retry\n",
        "\nThis is corrective retry $tentativa/$MAX_TENTATIVAS_CORRETIVAS_POR_TURNO for this same reviewer turn.",
        "\nYour previous answer failed the required output contract or identified a blocker without revising the article.",
        "\nYou MUST resolve every correctable blocker in this turn by producing `custody: \"revised\"` and a complete `<maestro_final_text>`.",
        "\nUnresolved evidence markers or bibliographic lacunae in the current text are correctable defects: if supplied evidence does not verify them, remove or rewrite the unsupported claim/reference in the article and explain the quarantine in the report. Do not preserve `[EVIDENCIA_PENDENTE]`, bracketed lacunae, or unverifiable reference placeholders in `<maestro_final_text>`.",
        "\nOnly request operator evidence for a decision that cannot be made by deleting, narrowing, or quarantining the unsupported claim without harming the article.\n",
    ).joinToString("")

    /** `buildDraftPrompt`. */
    public fun rascunho(pedido: PedidoDaSessao, execucao: String): String {
        val conteudo = pedido.conteudoInicial.ifEmpty { "No existing editor content was provided." }
        return """$CABECALHO_DO_RASCUNHO

Run: `$execucao`
Session: ${sanearTitulo(pedido.titulo)}

## Language Contract

- Internal coordination between agents/peers MUST be written in en_US.
- The operator-facing deliverable MUST be written in Brazilian Portuguese (pt_BR).
- Do not use CLI or local filesystem. This Android app operates through provider APIs only.

## Role Contract

You are the drafter selected to open the editorial session.
You submit a complete text to the editorial panel, but you never vote as reviewer of your own text.
Read and obey the full editorial protocol before writing. The protocol is provided by the Maestro Android app automatically; do not ask the operator to provide it again.
Do not invent links. If evidence is missing, mark it explicitly as [EVIDENCIA_PENDENTE].

## Operator Request

${pedido.pedido}

## Existing Editor Content

$conteudo

## Full Editorial Protocol

```markdown
${pedido.protocolo}
```
"""
    }

    /**
     * `buildRevisionHistoryBlock`: os relatórios úteis mais recentes primeiro,
     * até 8 000 pontos de código cada e 48 000 no total, depois devolvidos à
     * ordem cronológica.
     */
    public fun historicoDeRevisoes(turnos: List<RelatorioDeTurno>): String {
        val maxHistorico = 48_000
        val maxRelatorio = 8_000
        val secoes = mutableListOf<String>()
        var usados = 0
        for (turno in turnos.asReversed()) {
            if (turno.status in STATUS_NAO_DELIBERATIVOS) continue
            val extraido = turno.relatorio
                ?: "No complete maestro_revision_report block was returned by ${turno.nome}. Treat this artifact as a contract failure, not as deliberative substance."
            val relatorio = EspacoUnicode.aparar(extraido)
            if (relatorio.isEmpty()) continue
            val cabecalho = "\n### ${turno.nome} / ${turno.papel} / `${turno.status}`\n\nArtifact: `${turno.artefato}`\n\n```text\n"
            val rodape = "\n```\n"
            val fixos = EspacoUnicode.contarPontosDeCodigo(cabecalho) + EspacoUnicode.contarPontosDeCodigo(rodape)
            if (usados + fixos >= maxHistorico) break
            val limite = minOf(maxRelatorio, maxHistorico - usados - fixos)
            val secao = cabecalho + EspacoUnicode.primeirosPontosDeCodigo(relatorio, limite) + rodape
            usados += EspacoUnicode.contarPontosDeCodigo(secao)
            secoes += secao
        }
        secoes.reverse()
        val historico = secoes.joinToString("")
        return if (EspacoUnicode.soEspaco(historico)) {
            "No prior revision reports are recorded for this serial cycle."
        } else {
            historico
        }
    }

    /** `buildRevisionPrompt`. */
    public fun revisao(
        pedido: PedidoDaSessao,
        execucao: String,
        turno: Int,
        textoAtual: String,
        autorAtual: String,
        revisor: String,
        relatoriosAnteriores: List<RelatorioDeTurno>,
        turnoDeFechamento: Boolean,
    ): String {
        val manifesto = TravaDeConteudo.formatarManifestoParaPrompt(textoAtual)
        val historico = historicoDeRevisoes(relatoriosAnteriores)
        return """$CABECALHO_DA_REVISAO

Run: `$execucao`
Round turn: `$turno`
Session: ${sanearTitulo(pedido.titulo)}

## Language Contract

- Internal coordination, critique, changelog, retry diagnostics, JSON/report fields, and every non-user-facing agent message MUST be written in en_US.
- The operator-facing article inside `<maestro_final_text>` MUST be written in Brazilian Portuguese (pt_BR).
- Keep protocol markers exactly as specified.
- The editorial protocol is authoritative input, not output. Read and obey it, but do not quote, summarize, restate, or reproduce protocol text in the artifact. Cite compact section IDs only, such as `§V.14` or `§11.7`.

## Role Contract

- Current version author/curator: `$autorAtual`.
- Current reviewer-reviser: `$revisor`.
- Closing redactor turn: `$turnoDeFechamento`.
- You are not allowed to revise a version you just produced.
- If you are the current version author, return `MAESTRO_STATUS: NOT_READY`, set `custody` to `"unchanged"`, omit `<maestro_final_text>`, and state `SELF_REVIEW_BLOCKED`. This condition is a scheduler invariant violation, not an editorial turn.
- The original redactor may act in the closing redactor turn only when the current version author is another peer. In that case, the original redactor reviews the completed peer circuit, may revise only issues raised by prior reviewers or concrete final-delivery blockers, and must preserve all approved content.
- You must act as reviewer and reviser in one turn: inspect the current text, apply only authorized corrections, and return the complete current article.
- A Maestro round is a full circular pass through all active AI agents. This call is one turn inside that round; do not call it a new round in your own report.
- The Android app audits public links automatically when a text attempts finalization. Do not fabricate URLs. If a link cannot be verified from the provided context, mark it as [EVIDENCIA_PENDENTE] instead of inventing one.

## Sovereign Approved-Content Lock

Approved content is locked by default.
You may alter a passage only when at least one hard gate applies:

1. A prior revision report or blocker explicitly cites that passage.
2. The passage contains a concrete, protocol-grounded defect that blocks safe final delivery.
3. A tiny adjacent edit is strictly necessary to keep grammar or continuity after an authorized correction.

If none of those gates applies, preserve the passage exactly. Do not restyle, shorten, reorder, simplify, expand, or replace it.
If a concern is optional, stylistic, vague, or outside scope, mark it as OUT_OF_SCOPE in the report and leave the text unchanged.

## Quality Preservation / Anti-Impoverishment Gate

Codex and Claude are the strongest long-form writers in this system. Gemini is second. DeepSeek, Grok, and Perplexity are useful reviewers but must not flatten stronger prose.
Preserve the strongest existing formulation unless a concrete editorial-protocol defect requires a narrow change.
Do not reduce breadth, depth, articulation, nuance, reflexivity, or argumentative amplitude.
Any deletion, compression, simplification, or structural narrowing must be justified in the report with:

- the exact passage changed;
- the exact protocol requirement;
- why preserving the stronger formulation would be unsafe or incorrect.

If you are unsure, preserve the passage and report the concern instead of rewriting it.

## Evidence and Bibliographic Integrity Gate

- Do not invent links, editions, publishers, years, URLs, page ranges, or source details.
- If evidence is missing, do not pass [EVIDENCIA_PENDENTE], bracketed lacunae, or unsupported reference placeholders forward inside <maestro_final_text>.
- Unverified claims or references are correctable defects when they can be removed, narrowed, generalized, or quarantined without damaging the article.
- Do not convert evidence-pending markers into publicable references, bracketed lacunae, or bibliographic placeholders such as [s. d.], [S. l.: s. n.], or [Edição consultada não identificada].
- If the current text depends on an unverified reference, source, link, or bibliographic detail, revise the article in this same turn by deleting, narrowing, generalizing, or quarantining that dependency unless the missing evidence or operator decision is truly indispensable.
- Missing evidence by itself is not a sufficient reason to pass the blocker forward. First remove, narrow, generalize, or quarantine the unsupported claim/reference; request operator evidence only for a blocker that cannot be resolved by any of those editorial actions.
- A text is not final-deliverable while it still depends on unresolved evidence markers or bibliographic lacunae.
- A blocker that can be corrected with the current text, prior reports, supplied evidence, or the editorial protocol MUST be corrected in this same turn. Do not merely point it out or pass it to the next reviewer.
- Do not return MAESTRO_STATUS: NOT_READY with custody set to "unchanged". That is an invalid pass-through objection.
- If no concrete blocker remains, return MAESTRO_STATUS: READY, set custody to "unchanged", keep changes empty, and omit <maestro_final_text>.
- If any concrete blocker remains, correct it in this same turn, return MAESTRO_STATUS: READY or MAESTRO_STATUS: NOT_READY according to the revised article's safety, set custody to "revised", and include the complete corrected article inside <maestro_final_text>.
- Use operator_evidence_required only to document external evidence still desirable after you have already removed, narrowed, generalized, or quarantined the unsupported dependency in the revised article.

## Required Output Contract

The answer MUST contain exactly these parts:

1. First line: MAESTRO_STATUS: READY or MAESTRO_STATUS: NOT_READY.
2. <maestro_revision_report> containing exactly one valid en_US JSON object, without prose or Markdown code fences before or after it:
   - reviewer
   - current_author
   - status
   - changed_blocks: list every changed received block using unique block_id, change_type, reason, protocol_basis, and required: true|false. Use change_type: "split" or "addition" for extra blocks and change_type: "reorder" whenever approved blocks move, as the Revised Block Origins section defines. Do not duplicate block_id entries.
   - revised_block_origins: required whenever custody is "revised", as the Revised Block Origins section defines.
   - unchanged_approved_blocks: list approved block IDs that you intentionally preserved.
   - changes: list of changed passages, received line/passage reference, reason, protocol citation, and whether the change was required.
   - operator_evidence_required: list of blockers that cannot be corrected from supplied materials and require external evidence or operator decision.
   - out_of_scope: concerns intentionally not changed.
   - quality_preservation: explicit statement that approved strong formulations were preserved; if not, justify each reduction.
   - custody: exactly "revised" when you changed the article, or exactly "unchanged" only when you approve the current article without changing custody.
3. Include <maestro_final_text> containing only the complete operator-facing article in pt_BR only when custody is "revised".
4. If custody is "unchanged", status MUST be READY, changes MUST be empty, <maestro_final_text> MUST be omitted, and all remaining concerns must be non-blocking out_of_scope notes.
5. MAESTRO_STATUS: NOT_READY with custody: "unchanged" is a contract violation: either fix the blocker and transfer revised custody, or approve the current version as READY unchanged.

Anything outside those tags may be discarded by the app.
An incomplete tag, missing closing tag, reproduced protocol text, malformed or truncated JSON/report is a contract violation and will not count as READY.

## Revised Block Origins

${InstrucaoDoRegistro.TEXTO}

## Current Text Block Manifest

Every received block is locked by default. If <maestro_final_text> changes, deletes, compresses, splits, moves, or replaces a received block, the corresponding received block_id MUST appear in changed_blocks with a concrete protocol_basis. Silent changes to approved blocks are contract violations. Extra blocks require change_type: "split" or "addition"; moved approved blocks require change_type: "reorder".

$manifesto

## Operator Request

${pedido.pedido}

## Full Editorial Protocol

```markdown
${pedido.protocolo}
```

## Current Text Under Custody

```markdown
$textoAtual
```

## Prior Serial Revision Reports
$historico
"""
    }

    /** O `sanitizeText(value, 200)` do web. */
    private fun sanearTitulo(titulo: String): String =
        EspacoUnicode.primeirosPontosDeCodigo(EspacoUnicode.aparar(titulo.replace("\u0000", "")), 200)
}
