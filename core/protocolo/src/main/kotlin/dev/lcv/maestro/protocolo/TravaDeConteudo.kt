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
 * **Afastamento de contrato, declarado.** Quando o texto revisado tem bloco que
 * não é cópia intacta de um recebido, o relatório também traz
 * `revised_block_origins`: de onde vem cada bloco revisado, na ordem do texto.
 * Sem isso, um bloco editado e movido é indistinguível de um acréscimo, e a
 * trava não tem como saber se ele moveu. É exigência deste repositório, não do
 * canônico — decisão do operador de 22/09/2026, registrada na Discussion #41 —,
 * e a instrução que a pede ao agente está em [InstrucaoDoRegistro].
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
     * As mensagens herdadas do canônico ficam palavra por palavra: voltam ao
     * agente como instrução de correção, e traduzi-las mudaria o estímulo que
     * ele recebe. As do registro de procedência são novas, no mesmo formato.
     */
    public fun validarRevisao(antes: String, depois: String, relatorio: String): Veredito {
        val blocosAntes = segmentarBlocos(antes)
        val blocosDepois = segmentarBlocos(depois)
        val diferenca = compararCustodias(blocosAntes, blocosDepois)
        val leitura = LeituraDoRelatorio.ler(relatorio)

        // Atribuição ambígua entre blocos idênticos: sem registro, fecha, não
        // escolhe. Com registro, cada bloco revisado diz de onde vem, e a
        // atribuição sai dele — ver `validarComRegistro`.
        val registroLido = (leitura as? LeituraDoRelatorio.Leitura.Entradas)?.procedencias
        if (diferenca.ambiguo != null && registroLido == null) {
            return Veredito.Violada(
                "approved-content lock violation: ${diferenca.ambiguo}",
            )
        }

        // Relatório que não parseia é violação de contrato, e não motivo para
        // adivinhar o que ele queria dizer. O próprio prompt canônico já
        // declara isso: "truncated JSON/report is a contract violation".
        if (leitura is LeituraDoRelatorio.Leitura.Invalida) {
            return Veredito.Violada(
                "approved-content lock violation: ${leitura.motivo}",
            )
        }

        if (leitura is LeituraDoRelatorio.Leitura.Ambigua) {
            return Veredito.Violada(
                "approved-content lock violation: ${leitura.motivo}",
            )
        }

        if (leitura is LeituraDoRelatorio.Leitura.SemSecao) {
            return when {
                diferenca.alterados.isEmpty() &&
                    blocosDepois.size <= blocosAntes.size &&
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

        val lidas = leitura as LeituraDoRelatorio.Leitura.Entradas
        val entradas = lidas.porBloco
        val registro = lidas.procedencias

        // Com registro, os blocos que mudaram são os recebidos que nenhuma cópia
        // intacta nomeia. Cópia intacta é o bloco igual ao recebido que ele
        // nomeia — ver `intactosDoRegistro` e as guardas do texto.
        val intactos = registro?.let { intactosDoRegistro(blocosAntes, blocosDepois, it) }
        val alterados = if (intactos == null) {
            diferenca.alterados
        } else {
            val sobreviventes = intactos.values.toSet()
            blocosAntes.map { it.id }.filter { it !in sobreviventes }
        }

        val naoDeclarados = alterados.filter { it !in entradas }
        if (naoDeclarados.isNotEmpty()) {
            return Veredito.Violada(
                "approved-content lock violation: changed received blocks " +
                    "${naoDeclarados.joinToString(", ")} without matching " +
                    "changed_blocks declaration",
            )
        }

        return if (registro == null || intactos == null) {
            validarSemRegistro(blocosAntes, blocosDepois, diferenca, entradas)
        } else {
            validarComRegistro(blocosAntes, blocosDepois, alterados, intactos, entradas, registro)
        }
    }

    /**
     * O caminho canônico, sem registro de procedência. As conferências e as
     * mensagens são as do canônico, na ordem do canônico; só no fim, se sobrou
     * bloco revisado que não é cópia intacta de um recebido, o registro passa
     * a ser exigido.
     *
     * A exigência vem **depois** das conferências canônicas de propósito: uma
     * revisão que o canônico recusa continua recusada com a mesma mensagem, e
     * só as que ele aprovaria mudam de veredito.
     */
    private fun validarSemRegistro(
        blocosAntes: List<Bloco>,
        blocosDepois: List<Bloco>,
        diferenca: Diferenca,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): Veredito {
        val blocosNovos = blocosDepois.size - blocosAntes.size

        val idsQueExigemBase = diferenca.alterados.toMutableList()
        for (id in diferenca.reordenados) if (id !in idsQueExigemBase) idsQueExigemBase += id
        violacaoDeBase(idsQueExigemBase, entradas)?.let { return it }

        // Crescimento é autorizado POR BLOCO, não por bandeira global: cada bloco
        // acrescentado consome uma autorização, que nomeia um bloco que existe e
        // traz a própria base de protocolo. É mais estrito que o canônico, que
        // aceita qualquer uma (`.any(|declaration|
        // declaration.allows_block_count_growth)`), e de propósito: sem registro,
        // nada mais justifica cada bloco a mais.
        if (blocosNovos > 0) {
            val autorizamCrescimento = autorizacoesDeCrescimento(blocosAntes, blocosDepois, entradas)
            violacaoDeCrescimentoSemBase(autorizamCrescimento)?.let { return it }
            if (autorizamCrescimento.size < blocosNovos) {
                // A frase canônica é preservada palavra por palavra, porque volta
                // ao agente como instrução de correção; os números vão depois.
                return Veredito.Violada(
                    "approved-content lock violation: revised custody added new blocks without " +
                        "declaring change_type split/addition in changed_blocks " +
                        "($blocosNovos added, ${autorizamCrescimento.size} declared)",
                )
            }
        }

        violacaoDeReordenacao(diferenca.reordenados, entradas)?.let { return it }

        val semPar = blocosDepois.size - atribuicaoExata(blocosAntes, blocosDepois).size
        if (semPar > 0) { // guarda:registro-exigido
            return Veredito.Violada(
                "approved-content lock violation: revised custody has $semPar " +
                    (if (semPar == 1) "block that is not an unchanged copy" else "blocks that are not unchanged copies") +
                    " of received blocks, but maestro_revision_report has " +
                    "no ${LeituraDoRelatorio.NOME_DO_REGISTRO} section declaring where each " +
                    "revised block comes from",
            )
        }
        return Veredito.Aprovada
    }

    /**
     * O caminho com registro de procedência.
     *
     * Cada guarda está numa linha própria, marcada `// guarda:`, para que a
     * matriz de desarme possa desligar **uma de cada vez** e mostrar que o
     * controle daquela guarda cai sozinho.
     */
    private fun validarComRegistro(
        blocosAntes: List<Bloco>,
        blocosDepois: List<Bloco>,
        alterados: List<String>,
        intactos: Map<Int, String>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
        registro: List<LeituraDoRelatorio.Procedencia>,
    ): Veredito {
        violacaoDeCobertura(registro, blocosDepois)?.let { return it } // guarda:cobertura
        val pares = registro.zip(blocosDepois)
        violacaoDePrefixo(pares)?.let { return it } // guarda:prefixo
        violacaoDeOrigemInexistente(pares, blocosAntes)?.let { return it } // guarda:origem-existe
        violacaoDeIntactoMalNomeado(pares, intactos, blocosAntes)?.let { return it } // guarda:hash-vence
        violacaoDeNomeRepetido(pares, intactos, blocosAntes)?.let { return it } // guarda:nome-repetido

        val identidades = identidadesDoRegistro(pares)
        violacaoDeDivisaoSemSplit(identidades, entradas)?.let { return it } // guarda:divisao
        violacaoDePedacoSemBase(pares, identidades)?.let { return it } // guarda:pedaco-com-base
        violacaoDeAcrescimoSemBase(pares)?.let { return it } // guarda:acrescimo-com-base

        // A ordem é a do registro. Entre cópias de texto idêntico, qual é qual
        // não é fato do texto, e três rodadas de revisão derrubaram, uma por
        // vez, cada regra que tentou inferir isso. O registro não infere: os
        // IDs que o agente dá às cópias são o relato dele de qual foi para
        // onde, e o que se moveu nesse relato tem de ser declarado — a
        // instrução diz isso ao agente. Blocos que não são cópia idêntica de
        // outro são acusados igual em qualquer nomeação das cópias: a posição
        // deles na sequência não depende do nome que as cópias recebem.
        val porPosicao = reordenadosPorPosicao(blocosAntes, identidades) // guarda:reordem-posicao
        val pedacosFora = reordenadosPorPedacoFora(blocosAntes, identidades) // guarda:reordem-pedaco
        val reordenados = naOrdemDaCustodia(blocosAntes, porPosicao + pedacosFora)

        val exigemBase = alterados + reordenados.filter { it !in alterados } // guarda:base-da-reordem
        violacaoDeBase(exigemBase, entradas)?.let { return it }

        // A entrada que autoriza o crescimento traz a própria base também
        // quando o texto não cresceu no saldo: um acréscimo que substitui uma
        // remoção continua sendo acréscimo declarado (Codex, rodada 8).
        if (blocosDepois.size > blocosAntes.size || pares.any { it.first.origem == null }) {
            violacaoDeCrescimentoSemBase(
                entradas.values.filter { it.permiteCrescimento },
            )?.let { return it }
        }

        // O prompt canônico manda declarar bloco a mais em changed_blocks
        // ("Extra blocks require change_type split or addition"). O registro dá
        // a base de cada bloco, mas não dispensa a declaração, e a declaração
        // tem de ser do tipo certo: a continuação já exige `split` na guarda de
        // divisão; o bloco novo exige uma entrada de acréscimo. Um `split` num
        // bloco que não se dividiu não cobre acréscimo nenhum.
        if (pares.any { it.first.origem == null } && acrescimosDeclarados(blocosAntes, entradas).isEmpty()) { // guarda:acrescimo-declarado
            return Veredito.Violada(
                "approved-content lock violation: revised custody added new blocks without " +
                    "declaring change_type split/addition in changed_blocks; $REGISTRO declares " +
                    "additions, so a changed_blocks entry must declare change_type addition",
            )
        }

        violacaoDeReordenacao(reordenados, entradas)?.let { return it }
        return Veredito.Aprovada
    }

    /**
     * Entradas de changed_blocks que declaram acréscimo sob o ID de um bloco
     * **recebido**, como a instrução pede. Um ID que só existe no texto
     * revisado não é o de nenhum bloco que o agente recebeu (DeepSeek,
     * rodada 7).
     */
    private fun acrescimosDeclarados(
        blocosAntes: List<Bloco>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): List<LeituraDoRelatorio.Entrada> {
        val recebidos = blocosAntes.map { it.id }.toSet()
        return entradas.values.filter { it.permiteAcrescimo && it.id in recebidos }
    }

    /** Entradas de changed_blocks que autorizam crescimento e nomeiam um bloco que existe. */
    private fun autorizacoesDeCrescimento(
        blocosAntes: List<Bloco>,
        blocosDepois: List<Bloco>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): List<LeituraDoRelatorio.Entrada> {
        val idsReais = (blocosAntes.map { it.id } + blocosDepois.map { it.id }).toSet()
        return entradas.values.filter { it.permiteCrescimento && it.id in idsReais }
    }

    // -- Guardas compartilhadas, com as mensagens canônicas ----------------

    private fun violacaoDeBase(
        ids: List<String>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): Veredito? {
        val semBase = ids.filter { id -> entradas[id]?.let { !it.temBaseDeProtocolo } ?: false }
        if (semBase.isEmpty()) return null
        return Veredito.Violada(
            "approved-content lock violation: changed_blocks entries for " +
                "${semBase.joinToString(", ")} must include protocol_basis",
        )
    }

    private fun violacaoDeCrescimentoSemBase(
        autorizam: List<LeituraDoRelatorio.Entrada>,
    ): Veredito? {
        val semBase = autorizam.filter { !it.temBaseDeProtocolo }
        if (semBase.isEmpty()) return null
        return Veredito.Violada(
            "approved-content lock violation: changed_blocks entries for " +
                "${semBase.joinToString(", ") { it.id }} declare change_type " +
                "split/addition and must include protocol_basis",
        )
    }

    private fun violacaoDeReordenacao(
        reordenados: List<String>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): Veredito? {
        val semReordenacao = reordenados.filter { id ->
            entradas[id]?.let { !it.permiteReordenacao } ?: true
        }
        if (semReordenacao.isEmpty()) return null
        return Veredito.Violada(
            "approved-content lock violation: reordered received blocks " +
                "${semReordenacao.joinToString(", ")} must each declare change_type " +
                "reorder in changed_blocks",
        )
    }

    // -- Guardas do registro de procedência --------------------------------

    private const val REGISTRO = LeituraDoRelatorio.NOME_DO_REGISTRO

    /** Quantos pontos de código do começo do bloco, como está no texto, o prefixo tem de cobrir. */
    private const val PREFIXO_MINIMO = 20

    private fun violacaoDeCobertura(
        registro: List<LeituraDoRelatorio.Procedencia>,
        blocosDepois: List<Bloco>,
    ): Veredito? {
        if (registro.size == blocosDepois.size) return null
        return Veredito.Violada(
            "approved-content lock violation: $REGISTRO lists ${registro.size} entries but " +
                "the revised custody has ${blocosDepois.size} blocks; list every block of the " +
                "revised text, once, in the order of the text",
        )
    }

    /**
     * O prefixo **confere** o bloco da mesma ordem; não localiza nada.
     *
     * Os dois lados passam pela mesma normalização do hash — quebra de linha e
     * sequência de espaços viram um espaço —, para que diferença de
     * espaçamento não recuse ninguém.
     *
     * O mínimo sai **do bloco**, e não do prefixo: é o tamanho, depois de
     * normalizados, dos primeiros [PREFIXO_MINIMO] pontos de código do bloco
     * como ele está no texto. Assim a cópia literal dos 20 primeiros
     * caracteres passa sempre, mesmo com espaço duplo ou quebra de linha
     * dentro dela — medir o prefixo normalizado recusava essa cópia —, e um
     * prefixo recheado de espaços, com 20 caracteres e pouco texto, não passa.
     */
    private fun violacaoDePrefixo(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
    ): Veredito? {
        for ((indice, par) in pares.withIndex()) {
            val (procedencia, bloco) = par
            val prefixo = normalizarTextoDoBloco(procedencia.prefixo)
            val texto = normalizarTextoDoBloco(bloco.texto)
            val numero = indice + 1
            if (!texto.startsWith(prefixo)) {
                // Sem citar o texto: a violação volta ao histórico da sessão, e
                // texto em custódia não vai para lá (ver `primeiraLinha`).
                return Veredito.Violada(
                    "approved-content lock violation: $REGISTRO entry $numero prefix does " +
                        "not match the opening of revised block $numero; entries must follow " +
                        "the order of the revised text, one per block",
                )
            }
            val exigido = EspacoUnicode.contarPontosDeCodigo(
                normalizarTextoDoBloco(EspacoUnicode.primeirosPontosDeCodigo(bloco.texto, PREFIXO_MINIMO)),
            )
            if (EspacoUnicode.contarPontosDeCodigo(prefixo) < exigido) {
                return Veredito.Violada(
                    "approved-content lock violation: $REGISTRO entry $numero prefix must copy " +
                        "at least $PREFIXO_MINIMO characters of revised block $numero, or the " +
                        "whole block if it is shorter",
                )
            }
        }
        return null
    }

    private fun violacaoDeOrigemInexistente(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
        blocosAntes: List<Bloco>,
    ): Veredito? {
        val recebidos = blocosAntes.map { it.id }.toSet()
        for ((indice, par) in pares.withIndex()) {
            val origem = par.first.origem ?: continue
            if (origem !in recebidos) {
                return Veredito.Violada(
                    "approved-content lock violation: $REGISTRO entry ${indice + 1} names " +
                        "$origem, which is not a received block",
                )
            }
        }
        return null
    }

    /**
     * O hash vence. Bloco igual a um recebido é cópia desse texto, como no
     * canônico, qualquer que seja o rótulo que o registro lhe dê: enquanto
     * houver recebido com aquele texto que nenhuma cópia intacta nomeia, ele
     * tem de nomeá-lo. Sem isto, uma troca pura de lugar passava declarada como
     * duas edições, e o movimento ficava escondido.
     */
    private fun violacaoDeIntactoMalNomeado(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
        intactos: Map<Int, String>,
        blocosAntes: List<Bloco>,
    ): Veredito? {
        val nomeados = intactos.values.toSet()
        for ((indice, par) in pares.withIndex()) {
            val bloco = par.second
            if (indice in intactos) continue
            val livre = blocosAntes.firstOrNull {
                it.hashNormalizado == bloco.hashNormalizado && it.id !in nomeados
            } ?: continue
            return Veredito.Violada(
                "approved-content lock violation: revised block ${indice + 1} is an " +
                    "unchanged copy of received block ${livre.id}; its $REGISTRO entry must " +
                    "name a received block with that exact text",
            )
        }
        return null
    }

    /**
     * Cada recebido é nomeado por **uma** cópia intacta só. Cópia de um texto
     * cujos recebidos já têm todos a sua é cópia a mais, e cópia a mais é
     * acréscimo, como a instrução diz — nem pedaço, nem reescrita de outro
     * bloco. Sem isto, ela passava por pedaço de divisão de um bloco que
     * continua inteiro em outro lugar (Codex, rodada 8). Roda depois de
     * `hash-vence`, que já exigiu o nome enquanto havia recebido livre.
     */
    private fun violacaoDeNomeRepetido(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
        intactos: Map<Int, String>,
        blocosAntes: List<Bloco>,
    ): Veredito? {
        val textosRecebidos = blocosAntes.map { it.hashNormalizado }.toSet()
        for ((indice, par) in pares.withIndex()) {
            if (par.first.origem == null || indice in intactos) continue
            if (par.second.hashNormalizado in textosRecebidos) {
                return Veredito.Violada(
                    "approved-content lock violation: revised block ${indice + 1} is an extra " +
                        "unchanged copy of a received block that another unchanged copy already " +
                        "names; each received block is named by one unchanged copy only, and " +
                        "extra copies are additions",
                )
            }
        }
        return null
    }

    private fun violacaoDeDivisaoSemSplit(
        identidades: List<String?>,
        entradas: Map<String, LeituraDoRelatorio.Entrada>,
    ): Veredito? {
        val ocorrencias = identidades.filterNotNull().groupingBy { it }.eachCount()
        val semSplit = ocorrencias.filter { (id, vezes) ->
            vezes > 1 && entradas[id]?.let { it.permiteDivisao && it.temBaseDeProtocolo } != true
        }.keys
        if (semSplit.isEmpty()) return null
        return Veredito.Violada(
            "approved-content lock violation: received blocks ${semSplit.joinToString(", ")} " +
                "continue in more than one revised block, so each must declare change_type " +
                "split with protocol_basis in changed_blocks",
        )
    }

    /**
     * Todo pedaço de divisão **depois do primeiro** traz a própria base, na
     * própria entrada. Sem isto, uma declaração de split cobria filhos sem
     * limite, e um bloco novo rotulado de pedaço passava sem justificativa — o
     * buraco do crescimento global, reaberto com outro nome.
     */
    private fun violacaoDePedacoSemBase(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
        identidades: List<String?>,
    ): Veredito? {
        val vistos = mutableSetOf<String>()
        for ((indice, id) in identidades.withIndex()) {
            if (id == null) continue
            if (!vistos.add(id) && !pares[indice].first.temBaseDeProtocolo) {
                return Veredito.Violada(
                    "approved-content lock violation: revised block ${indice + 1} is an " +
                        "additional piece of received block $id, so its $REGISTRO entry must " +
                        "include protocol_basis",
                )
            }
        }
        return null
    }

    private fun violacaoDeAcrescimoSemBase(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
    ): Veredito? {
        for ((indice, par) in pares.withIndex()) {
            val procedencia = par.first
            if (procedencia.origem == null && !procedencia.temBaseDeProtocolo) {
                return Veredito.Violada(
                    "approved-content lock violation: revised block ${indice + 1} is declared " +
                        "as an addition, so its $REGISTRO entry must include protocol_basis",
                )
            }
        }
        return null
    }

    // -- Identidade e ordem, pelo registro ---------------------------------

    /**
     * Atribuição exata, um-para-um e primeiro-com-primeiro, de bloco revisado
     * que é cópia intacta a um recebido de mesmo hash — a que o canônico usa
     * para os sobreviventes. Só o caminho sem registro a usa: com registro, a
     * identidade vem do registro ([intactosDoRegistro]).
     */
    internal fun atribuicaoExata(antes: List<Bloco>, depois: List<Bloco>): Map<Int, String> {
        val livres = mutableMapOf<String, ArrayDeque<String>>()
        for (bloco in antes) livres.getOrPut(bloco.hashNormalizado) { ArrayDeque() } += bloco.id
        val atribuidos = mutableMapOf<Int, String>()
        for ((indice, bloco) in depois.withIndex()) {
            val fila = livres[bloco.hashNormalizado] ?: continue
            atribuidos[indice] = fila.removeFirstOrNull() ?: continue
        }
        return atribuidos
    }

    /**
     * As cópias intactas segundo o registro: o bloco revisado cujo texto é
     * igual ao do recebido que ele nomeia, e só a primeira que o nomeia.
     *
     * Cada fonte decide o que só ela sabe. O texto decide que um bloco é cópia:
     * bloco igual a um recebido é cópia daquele texto, como no canônico, e as
     * guardas `hash-vence` e `nome-repetido` o obrigam a nomear um recebido com
     * aquele texto ou, se todos já têm a sua cópia, a ser acréscimo. O registro
     * decide o que o texto não sabe: qual cópia de texto idêntico é qual, e de
     * onde vem todo bloco que não é cópia. Nada aqui atribui por hash contra o
     * registro, que era a origem dos cantos que as rodadas anteriores acharam.
     */
    internal fun intactosDoRegistro(
        antes: List<Bloco>,
        depois: List<Bloco>,
        registro: List<LeituraDoRelatorio.Procedencia>,
    ): Map<Int, String> {
        val hashPorId = antes.associate { it.id to it.hashNormalizado }
        val nomeados = mutableSetOf<String>()
        val intactos = mutableMapOf<Int, String>()
        for ((indice, par) in registro.zip(depois).withIndex()) {
            val origem = par.first.origem ?: continue
            if (hashPorId[origem] == par.second.hashNormalizado && nomeados.add(origem)) {
                intactos[indice] = origem
            }
        }
        return intactos
    }

    /** O recebido de onde vem cada bloco revisado, como o registro declara, ou `null` para bloco novo. */
    private fun identidadesDoRegistro(
        pares: List<Pair<LeituraDoRelatorio.Procedencia, Bloco>>,
    ): List<String?> = pares.map { it.first.origem }

    /**
     * Regra canônica de reordenação, aplicada à **primeira** ocorrência de cada
     * recebido: todo id cuja posição relativa mudou.
     */
    private fun reordenadosPorPosicao(antes: List<Bloco>, identidades: List<String?>): List<String> {
        val sequenciaDepois = identidades.filterNotNull().distinct()
        val presentes = sequenciaDepois.toSet()
        val sequenciaAntes = antes.map { it.id }.filter { it in presentes }
        if (sequenciaAntes.size <= 1 || sequenciaAntes == sequenciaDepois) return emptyList()
        val posicoesAntes = sequenciaAntes.withIndex().associate { (i, id) -> id to i }
        val posicoesDepois = sequenciaDepois.withIndex().associate { (i, id) -> id to i }
        return sequenciaAntes.filter { posicoesAntes[it] != posicoesDepois[it] }
    }

    /**
     * Pedaço de divisão **depois do primeiro** que foi parar depois de um bloco
     * que vinha, na custódia recebida, depois do seu de origem — olhando só os
     * blocos que apareceram **desde o pedaço anterior do mesmo recebido**.
     *
     * Só ocorrências não-primeiras: aplicado a todo bloco, acusava o bloco
     * parado de uma troca de pontas (`A / B / C` → `C / B / A` exigia
     * reordenação de B). E só a janela desde o pedaço anterior: comparado com
     * tudo o que veio antes, acusava a divisão que não saiu do lugar
     * (`A / B / C` → `C / B1 / B2 / A` exigia reordenação de B, porque C veio
     * antes de B1). Um bloco posterior que aparece antes da primeira ocorrência
     * já inverte a ordem das primeiras ocorrências, e [reordenadosPorPosicao]
     * acusa.
     */
    private fun reordenadosPorPedacoFora(antes: List<Bloco>, identidades: List<String?>): List<String> {
        val ordem = antes.withIndex().associate { (i, bloco) -> bloco.id to i }
        val ultimaOcorrencia = mutableMapOf<String, Int>()
        val fora = linkedSetOf<String>()
        for ((indice, id) in identidades.withIndex()) {
            if (id == null) continue
            val posicao = ordem[id] ?: continue
            val anterior = ultimaOcorrencia[id]
            if (anterior != null) {
                val passouDeUmPosterior = (anterior + 1 until indice).any { entre ->
                    identidades[entre]?.let { ordem[it] }?.let { it > posicao } == true
                }
                if (passouDeUmPosterior) fora += id
            }
            ultimaOcorrencia[id] = indice
        }
        return fora.toList()
    }

    private fun naOrdemDaCustodia(antes: List<Bloco>, ids: List<String>): List<String> {
        val conjunto = ids.toSet()
        return antes.map { it.id }.filter { it in conjunto }
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
     * Num portão de integridade isso resolve-se fechando, e é desvio deste
     * porte: o canônico atribui por contagem e acusa o bloco errado. Quando há
     * bloco repetido e a contagem de blocos daquele conteúdo mudou, a trava
     * recusa **se não houver registro de procedência**, e diz o que fazer:
     * declarar de onde vem cada bloco revisado, ou manter as cópias intactas.
     * Com registro, a atribuição sai dele, e a ambiguidade deixa de existir.
     * A mensagem antiga mandava "declarar todos os candidatos", e isso nunca
     * resolveu nada: a recusa vinha antes de ler o relatório (Codex, rodada 6).
     */
    internal fun compararCustodias(antes: List<Bloco>, depois: List<Bloco>): Diferenca {
        val repetidosComMudanca = hashesRepetidosComMudanca(antes, depois)
        if (repetidosComMudanca.isNotEmpty()) {
            val ids = antes.filter { it.hashNormalizado in repetidosComMudanca }.map { it.id }
            return Diferenca(
                alterados = emptyList(),
                reordenados = emptyList(),
                ambiguo = "received blocks ${ids.joinToString(", ")} have identical content, so " +
                    "the change cannot be attributed to one of them; declare " +
                    "${LeituraDoRelatorio.NOME_DO_REGISTRO} naming the received block of every " +
                    "revised block, or keep the duplicated blocks unchanged",
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
     * Blocos que trocaram de lugar entre si — **apenas entre os que
     * sobreviveram sem edição**, cuja identidade o hash estabelece de verdade.
     *
     * ## Por que não há pareamento aqui
     *
     * Um bloco editado some do lado de antes e reaparece do lado de depois com
     * outro hash, indistinguível de um bloco acrescentado. Emparelhar os dois
     * conjuntos é **adivinhar**, e três rodadas de revisão provaram isso uma de
     * cada vez: exigir contagens iguais perdia o movimento sempre que houvesse
     * acréscimo junto; parear por posição ordinal deixava o acréscimo colocado
     * antes roubar o par.
     *
     * O cross-review de 22/09/2026 fechou a questão, e com um fato que eu não
     * tinha: **o ordinal também não identifica quando as contagens são
     * iguais**. Em `A / B / C` → `C' / B / A'` há dois sem par de cada lado, e
     * o ordinal lê as duas edições como feitas no lugar, perdendo o
     * cruzamento. Não existia porto seguro nenhum.
     *
     * A questão não é difícil: é **não identificável**. Os dois textos não
     * carregam a informação, e a declaração também não — ela nomeia
     * `block_id` da custódia *recebida* e não diz qual bloco do texto revisado
     * é o novo. Qualquer heurística escolhe uma leitura sem base, e por isso
     * sempre há um próximo contraexemplo.
     *
     * ## Por que isto basta sem registro, e por que o registro é exigido
     *
     * Esta é a detecção do caminho **sem** registro de procedência. Ela é
     * exata porque só olha blocos cuja identidade o hash estabelece. Quando
     * sobra bloco revisado que não é cópia intacta de um recebido, a trava
     * passa a exigir `revised_block_origins`, e a ordem é decidida pelo que o
     * registro declara — ver `validarComRegistro`.
     *
     * Até 22/09/2026 este ponto era um limite aceito por escrito: o bloco
     * editado e movido não era detectado. Exigir reordenação sempre que a
     * correspondência fosse indeterminável obrigaria o agente a declarar
     * movimento que não houve, e essas declarações são o registro de
     * auditoria. O operador recusou os dois caminhos e decidiu que o relatório
     * passa a declarar a procedência (Discussion #41).
     */
    internal fun idsDeBlocosReordenados(antes: List<Bloco>, depois: List<Bloco>): List<String> {
        val comuns = contagensComuns(antes, depois)
        val sequenciaAntes = sequenciaDeIdsComuns(antes, antes, comuns)
        val sequenciaDepois = sequenciaDeIdsComuns(antes, depois, comuns)

        if (sequenciaAntes.size <= 1) return emptyList()
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
