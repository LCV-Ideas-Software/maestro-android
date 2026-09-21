package dev.lcv.maestro.protocolo

import java.security.MessageDigest

/**
 * Trava de conteúdo aprovado.
 *
 * O texto é partido em blocos por linha em branco, cada bloco recebe um
 * identificador `B0001`.., e uma revisão só pode alterar, reordenar ou
 * acrescentar blocos que ela **declarou** na seção `changed_blocks` do
 * `maestro_revision_report`, com `protocol_basis` — e com `change_type`
 * split/addition ou reorder onde for o caso.
 *
 * **Porte do canônico.** A implementação de referência é
 * `maestro-app/src-tauri/src/editorial_content_lock.rs` (Rust). O módulo
 * equivalente no produto web, `content-lock.ts`, é ele próprio um porte
 * daquele, e declara um desvio: chaveia igualdade de bloco pelo texto
 * normalizado em vez do SHA-256. Este porte vem do Rust — decisão do operador
 * em 21/09/2026 —, e por isso mantém o SHA-256, que também é o que alimenta a
 * coluna `sha256_12` do manifesto mostrado aos agentes.
 *
 * Todo espaço em branco passa por [EspacoUnicode]; ver lá por que a API da
 * plataforma está proibida neste módulo.
 */
public object TravaDeConteudo {

    /** Um bloco editorial do texto em custódia. */
    public data class Bloco(
        val id: String,
        val tipo: String,
        val texto: String,
        val hashNormalizado: String,
        val caracteres: Int,
    )

    /** Resultado de [validarRevisao]. */
    public sealed interface Veredito {
        /** A revisão respeitou o que declarou. */
        public data object Aprovada : Veredito

        /** A revisão violou a trava; [motivo] é a mensagem canônica. */
        public data class Violada(val motivo: String) : Veredito
    }

    // -- Segmentação -------------------------------------------------------

    /**
     * Parte o texto em blocos, separados por linha em branco. Normaliza as
     * quebras de linha antes, para que um texto vindo de Windows produza os
     * mesmos blocos que um vindo de Unix.
     */
    public fun segmentarBlocos(texto: String): List<Bloco> {
        val normalizado = texto.replace("\r\n", "\n").replace('\r', '\n')
        return normalizado.split("\n\n")
            .map { EspacoUnicode.aparar(it) }
            .filter { it.isNotEmpty() }
            .mapIndexed { indice, bloco ->
                Bloco(
                    id = "B%04d".format(indice + 1),
                    tipo = classificarBloco(bloco),
                    texto = bloco,
                    hashNormalizado = sha256Hex(normalizarTextoDoBloco(bloco)),
                    // Pontos de código, como no canônico — `length` contaria 2
                    // num emoji e a contagem vai no manifesto que o agente lê.
                    caracteres = EspacoUnicode.contarPontosDeCodigo(bloco),
                )
            }
    }

    /** O manifesto de blocos que vai no prompt, em tabela markdown. */
    public fun formatarManifestoParaPrompt(texto: String): String {
        val blocos = segmentarBlocos(texto)
        if (blocos.isEmpty()) return "No editorial content blocks were detected."

        val linhas = mutableListOf(
            "| block_id | kind | chars | sha256_12 | locked_by_default | excerpt |",
            "|---|---:|---:|---|---|---|",
        )
        for (bloco in blocos) {
            linhas += "| ${bloco.id} | ${bloco.tipo} | ${bloco.caracteres} | " +
                "${bloco.hashNormalizado.substring(0, 12)} | yes | " +
                "${trechoParaTabela(bloco.texto)} |"
        }
        return linhas.joinToString("\n")
    }

    // -- Validação ---------------------------------------------------------

    /**
     * Confere a custódia revisada contra a recebida e contra o relatório.
     *
     * As mensagens de violação são as do canônico, palavra por palavra: elas
     * voltam ao agente como instrução de correção, e traduzi-las mudaria o
     * estímulo que ele recebe.
     */
    public fun validarRevisao(antes: String, depois: String, relatorio: String): Veredito {
        val blocosAntes = segmentarBlocos(antes)
        val blocosDepois = segmentarBlocos(depois)
        val idsAlterados = idsDeBlocosAlterados(blocosAntes, blocosDepois)
        val idsReordenados = idsDeBlocosReordenados(blocosAntes, blocosDepois)
        val houveReordenacao = idsReordenados.isNotEmpty()

        val secao = LeituraDoRelatorio.extrairSecaoChangedBlocks(relatorio)
            ?: return when {
                idsAlterados.isEmpty() &&
                    blocosDepois.size <= blocosAntes.size &&
                    !houveReordenacao -> Veredito.Aprovada

                houveReordenacao -> Veredito.Violada(
                    "approved-content lock violation: revised custody reordered received blocks " +
                        "${idsReordenados.joinToString(", ")} but maestro_revision_report has no " +
                        "changed_blocks section with change_type reorder",
                )

                else -> Veredito.Violada(
                    "approved-content lock violation: revised custody changed received blocks " +
                        "${idsAlterados.joinToString(", ")} but maestro_revision_report has no " +
                        "changed_blocks section with block IDs",
                )
            }

        val declaracoes = LeituraDoRelatorio.extrairDeclaracoes(secao)

        val naoDeclarados = idsAlterados.filter { it !in declaracoes }
        if (naoDeclarados.isNotEmpty()) {
            return Veredito.Violada(
                "approved-content lock violation: changed received blocks " +
                    "${naoDeclarados.joinToString(", ")} without matching " +
                    "changed_blocks declaration",
            )
        }

        val idsQueExigemBase = idsAlterados.toMutableList()
        for (id in idsReordenados) if (id !in idsQueExigemBase) idsQueExigemBase += id

        val semBaseDeProtocolo = idsQueExigemBase.filter { id ->
            declaracoes[id]?.let { !it.temBaseDeProtocolo } ?: false
        }
        if (semBaseDeProtocolo.isNotEmpty()) {
            return Veredito.Violada(
                "approved-content lock violation: changed_blocks entries for " +
                    "${semBaseDeProtocolo.joinToString(", ")} must include protocol_basis",
            )
        }

        if (blocosDepois.size > blocosAntes.size &&
            declaracoes.values.none { it.permiteCrescimento }
        ) {
            return Veredito.Violada(
                "approved-content lock violation: revised custody added new blocks without " +
                    "declaring change_type split/addition in changed_blocks",
            )
        }

        if (houveReordenacao) {
            val semReordenacao = idsReordenados.filter { id ->
                declaracoes[id]?.let { !it.permiteReordenacao } ?: true
            }
            if (semReordenacao.isNotEmpty()) {
                return Veredito.Violada(
                    "approved-content lock violation: reordered received blocks " +
                        "${semReordenacao.joinToString(", ")} must each declare change_type " +
                        "reorder in changed_blocks",
                )
            }
        }

        return Veredito.Aprovada
    }

    // -- Internos ----------------------------------------------------------

    internal data class Declaracao(
        val temBaseDeProtocolo: Boolean,
        val permiteCrescimento: Boolean,
        val permiteReordenacao: Boolean,
    )

    internal fun normalizarTextoDoBloco(texto: String): String =
        EspacoUnicode.dividirPorEspacos(texto).joinToString(" ")

    internal fun classificarBloco(texto: String): String {
        val aparado = EspacoUnicode.apararInicio(texto)
        val linhas = aparado.lines()
        return when {
            aparado.startsWith("#") -> "heading"
            aparado.startsWith(">") -> "quote"
            linhas.all { linha ->
                val inicio = EspacoUnicode.apararInicio(linha)
                inicio.startsWith("- ") || inicio.startsWith("* ") ||
                    inicio.firstOrNull()?.let { it in '0'..'9' } == true
            } -> "list"
            linhas.count { it.contains('|') } >= 2 -> "table"
            else -> "paragraph"
        }
    }

    internal fun sha256Hex(texto: String): String {
        val resumo = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
        val construtor = StringBuilder(resumo.size * 2)
        for (byte in resumo) construtor.append("%02x".format(byte))
        return construtor.toString()
    }

    internal fun trechoParaTabela(texto: String): String {
        val compacto = normalizarTextoDoBloco(texto).replace("|", "\\|").replace('\n', ' ')
        val trecho = EspacoUnicode.primeirosPontosDeCodigo(compacto, 96)
        return if (EspacoUnicode.contarPontosDeCodigo(compacto) > 96) "$trecho..." else trecho
    }

    /**
     * Blocos recebidos que não sobreviveram: para cada bloco de antes, consome
     * uma ocorrência igual entre os de depois; o que não achar par mudou.
     */
    internal fun idsDeBlocosAlterados(antes: List<Bloco>, depois: List<Bloco>): List<String> {
        val contagens = mutableMapOf<String, Int>()
        for (bloco in depois) contagens.merge(bloco.hashNormalizado, 1, Int::plus)

        val alterados = mutableListOf<String>()
        for (bloco in antes) {
            val restante = contagens.getOrDefault(bloco.hashNormalizado, 0)
            if (restante == 0) {
                alterados += bloco.id
            } else {
                contagens[bloco.hashNormalizado] = restante - 1
            }
        }
        return alterados
    }

    /**
     * Blocos que sobreviveram mas trocaram de lugar entre si. Só faz sentido
     * com dois ou mais blocos em comum: com um só, não há ordem a violar.
     */
    internal fun idsDeBlocosReordenados(antes: List<Bloco>, depois: List<Bloco>): List<String> {
        val comuns = contagensComuns(antes, depois)
        if (comuns.values.sum() <= 1) return emptyList()

        val sequenciaAntes = sequenciaDeIdsComuns(antes, antes, comuns)
        val sequenciaDepois = sequenciaDeIdsComuns(antes, depois, comuns)
        if (sequenciaAntes == sequenciaDepois) return emptyList()

        val posicoesAntes = sequenciaAntes.withIndex().associate { (i, id) -> id to i }
        val posicoesDepois = sequenciaDepois.withIndex().associate { (i, id) -> id to i }
        return sequenciaAntes.filter { posicoesAntes[it] != posicoesDepois[it] }
    }

    private fun contagensComuns(antes: List<Bloco>, depois: List<Bloco>): Map<String, Int> {
        val contagensAntes = mutableMapOf<String, Int>()
        val contagensDepois = mutableMapOf<String, Int>()
        for (bloco in antes) contagensAntes.merge(bloco.hashNormalizado, 1, Int::plus)
        for (bloco in depois) contagensDepois.merge(bloco.hashNormalizado, 1, Int::plus)

        val comuns = sortedMapOf<String, Int>()
        for ((hash, quantasAntes) in contagensAntes) {
            val quantasDepois = contagensDepois[hash] ?: continue
            comuns[hash] = minOf(quantasAntes, quantasDepois)
        }
        return comuns
    }

    private fun sequenciaDeIdsComuns(
        antes: List<Bloco>,
        ordenados: List<Bloco>,
        comuns: Map<String, Int>,
    ): List<String> {
        val idsPorHash = mutableMapOf<String, ArrayDeque<String>>()
        val restantesParaIds = comuns.toMutableMap()
        for (bloco in antes) {
            val restante = restantesParaIds[bloco.hashNormalizado] ?: continue
            if (restante > 0) {
                idsPorHash.getOrPut(bloco.hashNormalizado) { ArrayDeque() } += bloco.id
                restantesParaIds[bloco.hashNormalizado] = restante - 1
            }
        }

        val restantes = comuns.toMutableMap()
        val sequencia = mutableListOf<String>()
        for (bloco in ordenados) {
            val restante = restantes[bloco.hashNormalizado] ?: continue
            if (restante > 0) {
                idsPorHash[bloco.hashNormalizado]?.removeFirstOrNull()?.let { sequencia += it }
                restantes[bloco.hashNormalizado] = restante - 1
            }
        }
        return sequencia
    }
}
