package dev.lcv.maestro.protocolo

/**
 * A instrução que o prompt de revisão leva ao agente para ele emitir
 * `revised_block_origins`.
 *
 * Mora no mesmo módulo da trava porque as duas pontas do contrato têm de
 * mudar juntas: um portão que exige uma seção que nenhum prompt pede recusaria
 * toda revisão com edição. A montagem completa do prompt é entrega posterior
 * deste mesmo módulo; ela inclui este texto, e a trava não tem chamador vivo
 * antes disso.
 *
 * Em inglês, como o resto do contrato do relatório (`editorial_prompts.rs`
 * pede *"en_US JSON-like audit data"*). A definição de bloco repete a regra de
 * [TravaDeConteudo.segmentarBlocos] com as mesmas palavras de propósito: se o
 * agente segmentar diferente do portão, a revisão dele é recusada.
 */
public object InstrucaoDoRegistro {

    public val TEXTO: String = listOf(
        "Inside maestro_revision_report, include `revised_block_origins`: one object for EVERY " +
            "block of your revised text, in the same order as the text.",
        "A block is a maximal run of non-blank lines. A line containing only whitespace is " +
            "blank. Several blank lines in a row separate two blocks once and never create an " +
            "empty block. Headings, list items and quotes follow the same rule.",
        "For each block give `prefix`: its opening copied exactly, starting at its first " +
            "non-whitespace character, at least 20 characters, or the whole block if it is " +
            "shorter. Differences in spacing and line breaks inside the prefix do not matter.",
        "Give `origin`: the received block ID the block comes from, whether kept unchanged or " +
            "rewritten, or the string \"addition\" if the block is new.",
        "A block whose text is the text of a received block, apart from spacing and line " +
            "breaks, is an unchanged copy of it, however you produced it, and names a received " +
            "block with that text. When several received blocks have identical text, the IDs you " +
            "give their unchanged copies are your account of which copy went where: naming them " +
            "in a different order from the received text declares that they moved. Each " +
            "received block is named by one unchanged copy only; once every received block with " +
            "that text is named, each further copy is an addition.",
        "Every addition, and every piece of a split after the first, carries its own " +
            "`protocol_basis` inside its `revised_block_origins` object.",
        "If there is any addition, `changed_blocks` also has an entry that declares " +
            "change_type \"addition\" with its own `protocol_basis`, under the ID of a received " +
            "block, for example the block the addition follows (a list such as " +
            "[\"edit\", \"addition\"] is accepted).",
        "If one received block becomes several, every piece names the same ID and that block's " +
            "`changed_blocks` entry declares change_type \"split\".",
        "List the received blocks your revised text keeps twice, leaving out deleted blocks " +
            "and additions: once in the order of the received text, and once in the order they " +
            "first appear in your revised text, each counted once. Every block whose position " +
            "differs between the two lists declares \"reorder\" in its `changed_blocks` entry, so " +
            "when two blocks trade places both declare it, and a deleted or added block moves " +
            "nothing by itself. A block also declares \"reorder\" when a later " +
            "piece of it is separated from the previous piece by a block that came after it in " +
            "the received text. A list such as [\"split\", \"reorder\"] is accepted.",
        "Do not number the entries or give positions: the order of the entries is the order of " +
            "the blocks.",
        "A missing, extra, reordered or mismatched entry is a contract violation and does not " +
            "count as READY.",
    ).joinToString(" ")
}
