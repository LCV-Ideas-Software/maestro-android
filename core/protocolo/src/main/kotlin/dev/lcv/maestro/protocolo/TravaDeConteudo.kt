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
        return dividirEmLinhaEmBranco(normalizado)
            .map { EspacoUnicode.aparar(it) }
            .filter { it.isNotEmpty() }
            .mapIndexed { indice, bloco ->
                Bloco(
                    // Formatação independente de locale: `"B%04d".format(1)`
                    // usa o locale padrão, e num aparelho em `ar-EG` produziria
                    // dígitos arábico-índicos em vez de `B0001`. O identificador
                    // vai ao agente e volta dele em texto; ele não pode mudar
                    // com o idioma do telefone.
                    id = "B" + (indice + 1).toString().padStart(4, '0'),
                    tipo = classificarBloco(bloco),
                    texto = bloco,
                    hashNormalizado = sha256Hex(normalizarTextoDoBloco(bloco)),
                    // Pontos de código, como no canônico — `length` contaria 2
                    // num emoji e a contagem vai no manifesto que o agente lê.
                    caracteres = EspacoUnicode.contarPontosDeCodigo(bloco),
                )
            }
    }

    /**
     * Parte o texto em linha em branco, onde **em branco quer dizer sem nada
     * além de espaço**, e não literalmente duas quebras seguidas.
     *
     * Partir só em `"\n\n"` não reconhece a linha que parece vazia mas tem um
     * espaço ou uma tabulação — comum em markdown editado à mão. O efeito era
     * grave: dois parágrafos colavam num bloco só, e declarar aquele bloco
     * passava a autorizar mudança nos dois.
     */
    internal fun dividirEmLinhaEmBranco(texto: String): List<String> {
        val blocos = mutableListOf<String>()
        val atual = mutableListOf<String>()
        for (linha in texto.split("\n")) {
            if (EspacoUnicode.soEspaco(linha)) {
                if (atual.isNotEmpty()) {
                    blocos += atual.joinToString("\n")
                    atual.clear()
                }
            } else {
                atual += linha
            }
        }
        if (atual.isNotEmpty()) blocos += atual.joinToString("\n")
        return blocos
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
        val diferenca = compararCustodias(blocosAntes, blocosDepois)
        val blocosNovos = blocosDepois.size - blocosAntes.size

        // Atribuição ambígua entre blocos idênticos: fecha, não escolhe.
        if (diferenca.ambiguo != null) {
            return Veredito.Violada(
                "approved-content lock violation: ${diferenca.ambiguo}",
            )
        }

        val leitura = LeituraDoRelatorio.ler(relatorio)

        if (leitura is LeituraDoRelatorio.Leitura.Ambigua) {
            return Veredito.Violada(
                "approved-content lock violation: ${leitura.motivo}",
            )
        }

        if (leitura is LeituraDoRelatorio.Leitura.SemSecao) {
            return when {
                diferenca.alterados.isEmpty() &&
                    blocosNovos <= 0 &&
                    diferenca.reordenados.isEmpty() -> Veredito.Aprovada

                diferenca.reordenados.isNotEmpty() -> Veredito.Violada(
                    "approved-content lock violation: revised custody reordered received blocks " +
                        "${diferenca.reordenados.joinToString(", ")} but maestro_revision_report " +
                        "has no changed_blocks section with change_type reorder",
                )

                diferenca.alterados.isNotEmpty() -> Veredito.Violada(
                    "approved-content lock violation: revised custody changed received blocks " +
                        "${diferenca.alterados.joinToString(", ")} but maestro_revision_report " +
                        "has no changed_blocks section with block IDs",
                )

                else -> Veredito.Violada(
                    "approved-content lock violation: revised custody added new blocks without " +
                        "declaring change_type split/addition in changed_blocks",
                )
            }
        }

        val entradas = (leitura as LeituraDoRelatorio.Leitura.Entradas).porBloco

        val naoDeclarados = diferenca.alterados.filter { it !in entradas }
        if (naoDeclarados.isNotEmpty()) {
            return Veredito.Violada(
                "approved-content lock violation: changed received blocks " +
                    "${naoDeclarados.joinToString(", ")} without matching " +
                    "changed_blocks declaration",
            )
        }

        val idsQueExigemBase = diferenca.alterados.toMutableList()
        for (id in diferenca.reordenados) if (id !in idsQueExigemBase) idsQueExigemBase += id

        val semBaseDeProtocolo = idsQueExigemBase.filter { id ->
            entradas[id]?.let { !it.temBaseDeProtocolo } ?: false
        }
        if (semBaseDeProtocolo.isNotEmpty()) {
            return Veredito.Violada(
                "approved-content lock violation: changed_blocks entries for " +
                    "${semBaseDeProtocolo.joinToString(", ")} must include protocol_basis",
            )
        }

        // Crescimento é autorizado POR BLOCO, não por bandeira global. A versão
        // anterior aceitava `declaracoes.values.none { it.permiteCrescimento }`:
        // uma única entrada com `split` em qualquer bloco liberava quantos
        // blocos novos o agente quisesse, nenhum deles declarado. Agora cada
        // bloco acrescentado consome uma autorização, e toda autorização de
        // crescimento precisa da própria base de protocolo.
        if (blocosNovos > 0) {
            // A autorização tem de nomear um bloco que EXISTE. Sem isto,
            // `{"block_id":"B9999","change_type":"addition","protocol_basis":"x"}`
            // — um identificador que nunca foi gerado em custódia nenhuma —
            // contava para a soma e liberava o acréscimo sem nomear bloco real.
            val idsReais = (blocosAntes.map { it.id } + blocosDepois.map { it.id }).toSet()
            val autorizamCrescimento = entradas.values
                .filter { it.permiteCrescimento && it.id in idsReais }
            val semBase = autorizamCrescimento.filter { !it.temBaseDeProtocolo }
            if (semBase.isNotEmpty()) {
                return Veredito.Violada(
                    "approved-content lock violation: changed_blocks entries for " +
                        "${semBase.joinToString(", ") { it.id }} declare change_type " +
                        "split/addition and must include protocol_basis",
                )
            }
            if (autorizamCrescimento.size < blocosNovos) {
                // A frase canônica é preservada palavra por palavra, porque
                // volta ao agente como instrução de correção; os números vão
                // depois dela, para ele saber quantas declarações faltam.
                return Veredito.Violada(
                    "approved-content lock violation: revised custody added new blocks without " +
                        "declaring change_type split/addition in changed_blocks " +
                        "($blocosNovos added, ${autorizamCrescimento.size} declared)",
                )
            }
        }

        if (diferenca.reordenados.isNotEmpty()) {
            val semReordenacao = diferenca.reordenados.filter { id ->
                entradas[id]?.let { !it.permiteReordenacao } ?: true
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

    /** O que mudou entre as duas custódias, ou por que não se sabe. */
    internal data class Diferenca(
        val alterados: List<String>,
        val reordenados: List<String>,
        val ambiguo: String?,
    )

    /**
     * Compara as custódias e, quando a atribuição é ambígua, **diz que é**.
     *
     * A ambiguidade é real e o conteúdo que a produz é banal: com dois blocos
     * de texto idêntico, o casamento por contagem de hash não sabe qual dos
     * dois sobreviveu. Editar o primeiro de dois parágrafos iguais fazia a
     * versão anterior acusar o segundo — então declarar o bloco certo era
     * recusado e declarar o errado era aprovado.
     *
     * Num portão de integridade isso resolve-se fechando. Quando há bloco
     * repetido e a contagem de blocos daquele conteúdo mudou, a trava recusa e
     * diz o que fazer: separar os blocos ou declarar todos os candidatos.
     */
    internal fun compararCustodias(antes: List<Bloco>, depois: List<Bloco>): Diferenca {
        val repetidosComMudanca = hashesRepetidosComMudanca(antes, depois)
        if (repetidosComMudanca.isNotEmpty()) {
            val ids = antes.filter { it.hashNormalizado in repetidosComMudanca }.map { it.id }
            return Diferenca(
                alterados = emptyList(),
                reordenados = emptyList(),
                ambiguo = "received blocks ${ids.joinToString(", ")} have identical content, so " +
                    "the change cannot be attributed to one of them; declare every candidate " +
                    "block in changed_blocks or keep the duplicated blocks unchanged",
            )
        }
        return Diferenca(
            alterados = idsDeBlocosAlterados(antes, depois),
            reordenados = idsDeBlocosReordenados(antes, depois),
            ambiguo = null,
        )
    }

    private fun hashesRepetidosComMudanca(
        antes: List<Bloco>,
        depois: List<Bloco>,
    ): Set<String> {
        val contagemAntes = antes.groupingBy { it.hashNormalizado }.eachCount()
        val contagemDepois = depois.groupingBy { it.hashNormalizado }.eachCount()
        return contagemAntes
            .filter { (hash, quantas) ->
                val sobreviventes = contagemDepois.getOrDefault(hash, 0)
                // Ambíguo é quando ALGUMAS das cópias sobrevivem e não dá para
                // saber quais — daí o `1 until quantas`. Nenhuma sobrevivente
                // significa que todas mudaram, e cada id é atribuível sem
                // dúvida. Mais sobreviventes que o original significa que uma
                // cópia foi acrescentada, e também não há dúvida sobre as que
                // vieram de antes. A versão anterior fechava nos três casos, e
                // o preço de errar para o lado fechado também é alto: recusava
                // revisão que tinha declarado tudo certo.
                quantas > 1 && sobreviventes in 1 until quantas
            }
            .keys
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
     * Blocos que trocaram de lugar entre si. Só faz sentido com dois ou mais
     * blocos em comum: com um só, não há ordem a violar.
     *
     * A comparação usa os blocos que sobreviveram **mais** os que foram
     * substituídos até onde as duas pontas alcançam: o i-ésimo bloco sem par
     * de `antes` corresponde ao i-ésimo sem par de `depois`, e o excedente de
     * um dos lados — acréscimo ou remoção — fica de fora.
     *
     * Sem isso, um bloco **editado e movido** desaparecia da conta: em
     * `A / B / C` → `B / A editado / C`, a sequência comum é `B, C` nos dois
     * lados, nenhuma reordenação era detectada, e um relatório que declarasse
     * só a edição de A era aprovado sem declarar o movimento.
     */
    internal fun idsDeBlocosReordenados(antes: List<Bloco>, depois: List<Bloco>): List<String> {
        val comuns = contagensComuns(antes, depois)
        val sequenciaAntes = sequenciaDeIdsComuns(antes, antes, comuns).toMutableList()
        val sequenciaDepois = sequenciaDeIdsComuns(antes, depois, comuns).toMutableList()

        // Pareia os substituídos pela ordem, até onde as duas pontas alcançam.
        // Exigir quantidades IGUAIS, como a versão anterior fazia, desligava o
        // pareamento inteiro sempre que houvesse acréscimo ou remoção junto:
        // em `A / B / C` → `B / A editado / C / D` sobra um sem par de um lado
        // e dois do outro, e o movimento de A voltava a ficar invisível.
        val semParAntes = blocosSemPar(antes, depois)
        val semParDepois = blocosSemPar(depois, antes)
        val pareados = minOf(semParAntes.size, semParDepois.size)
        if (pareados > 0) {
            val idPorSubstituto = (0 until pareados)
                .associate { semParDepois[it].id to semParAntes[it].id }
            val aIncluirAntes = semParAntes.take(pareados).map { it.id }.toSet()
            inserirNaOrdem(sequenciaAntes, antes, aIncluirAntes) { it }
            inserirNaOrdem(sequenciaDepois, depois, idPorSubstituto.keys) { idPorSubstituto[it]!! }
        }

        if (sequenciaAntes.size <= 1) return emptyList()
        if (sequenciaAntes == sequenciaDepois) return emptyList()

        val posicoesAntes = sequenciaAntes.withIndex().associate { (i, id) -> id to i }
        val posicoesDepois = sequenciaDepois.withIndex().associate { (i, id) -> id to i }
        return sequenciaAntes.filter { posicoesAntes[it] != posicoesDepois[it] }
    }

    /** Blocos de [lado] cujo conteúdo não tem par do [outro] lado. */
    private fun blocosSemPar(lado: List<Bloco>, outro: List<Bloco>): List<Bloco> {
        val restantes = outro.groupingBy { it.hashNormalizado }.eachCount().toMutableMap()
        val semPar = mutableListOf<Bloco>()
        for (bloco in lado) {
            val quantas = restantes.getOrDefault(bloco.hashNormalizado, 0)
            if (quantas > 0) restantes[bloco.hashNormalizado] = quantas - 1 else semPar += bloco
        }
        return semPar
    }

    /**
     * Refaz [sequencia] percorrendo [blocos] na ordem do lado e acrescentando
     * os que estiverem em [aIncluir], traduzidos por [traduzirId] para o
     * identificador do lado `antes`.
     */
    private fun inserirNaOrdem(
        sequencia: MutableList<String>,
        blocos: List<Bloco>,
        aIncluir: Set<String>,
        traduzirId: (String) -> String,
    ) {
        val comuns = sequencia.toMutableList()
        sequencia.clear()
        val idsComuns = comuns.toSet()
        for (bloco in blocos) {
            when {
                bloco.id in aIncluir -> sequencia += traduzirId(bloco.id)
                bloco.id in idsComuns && comuns.isNotEmpty() -> sequencia += comuns.removeAt(0)
                comuns.isNotEmpty() -> sequencia += comuns.removeAt(0)
            }
        }
        sequencia += comuns
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
