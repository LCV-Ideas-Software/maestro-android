package dev.lcv.maestro.protocolo

/**
 * Leitura da seção `changed_blocks` do `maestro_revision_report`.
 *
 * O relatório do agente não é JSON garantido: vem em formato solto, às vezes
 * com aspas, às vezes sem, às vezes em YAML aproximado, às vezes cercado por
 * prosa. Mesmo assim, **autorização nunca se decide por busca de substring**.
 *
 * ## Por que este arquivo foi reescrito
 *
 * A primeira versão portava o canônico literalmente, e o canônico decide
 * autorização com `contains`. Medido em 22/09/2026, num portão cujo adversário
 * é um modelo de linguagem escrevendo prosa:
 *
 * | Texto na justificativa         | Autorizava reordenar | Autorizava criar bloco |
 * | ------------------------------ | -------------------- | ---------------------- |
 * | `removed duplicate punctuation`| **sim** (`re-move-d`)| não                    |
 * | `I have not moved anything`    | **sim**              | não                    |
 * | `unmoved`                      | **sim**              | não                    |
 * | `no addition was made`         | não                  | **sim**                |
 *
 * A palavra mais comum de um relatório editorial — "removed" — contém `move`,
 * e uma negação explícita contava como autorização. Buraco que só precisa de
 * prosa comum para disparar dispara no uso normal, não no uso hostil.
 *
 * ## O que este arquivo faz em vez disso
 *
 * Quebra cada entrada em pares **campo → valor**, com um varredor ciente de
 * aspas e de escape, e decide sobre o **valor**:
 *
 * - `change_type` é comparado como **token inteiro** contra um conjunto
 *   fechado. `"edit"` não autoriza nada, mesmo que a palavra "addition"
 *   apareça na justificativa ao lado.
 * - `protocol_basis` só conta quando é **campo da entrada**, não quando as
 *   duas palavras aparecem dentro do valor de outro campo.
 * - Entrada com dois `block_id`, ou duas entradas para o mesmo bloco, é
 *   **recusada** em vez de ter as permissões somadas: registro ambíguo num
 *   portão de integridade resolve-se fechando, não unindo.
 *
 * Porte de `maestro-app/src-tauri/src/editorial_content_lock.rs`, com os
 * afastamentos acima declarados. O canônico tem os mesmos buracos e está
 * registrado no rastreador dele.
 */
internal object LeituraDoRelatorio {

    /** Valores de `change_type` que autorizam crescer o número de blocos. */
    private val VALORES_DE_CRESCIMENTO =
        setOf("split", "addition", "added", "new_block", "new block", "insert", "inserted")

    /** Valores de `change_type` que autorizam reordenar. */
    private val VALORES_DE_REORDENACAO =
        setOf("reorder", "reordered", "move", "moved", "reposition", "repositioned")

    private val CHAVES_DE_INICIO = listOf("changed_blocks", "changes")

    private val CHAVES_DE_FIM = listOf(
        "operator_evidence_required",
        "out_of_scope",
        "quality_preservation",
        "unchanged_approved_blocks",
        "custody",
    )

    /** O que uma entrada de `changed_blocks` declara, depois de lida. */
    internal data class Entrada(
        val id: String,
        val temBaseDeProtocolo: Boolean,
        val permiteCrescimento: Boolean,
        val permiteReordenacao: Boolean,
    )

    /** Resultado da leitura da seção. */
    internal sealed interface Leitura {
        /** Não há seção de blocos alterados no relatório. */
        data object SemSecao : Leitura

        /** A seção foi lida e produziu estas entradas, uma por bloco. */
        data class Entradas(val porBloco: Map<String, Entrada>) : Leitura

        /**
         * A seção existe mas é ambígua — dois `block_id` na mesma entrada, ou
         * duas entradas para o mesmo bloco. Num portão de integridade isso
         * **não** se resolve escolhendo uma nem somando as duas.
         */
        data class Ambigua(val motivo: String) : Leitura
    }

    fun ler(relatorio: String): Leitura {
        val secao = extrairSecaoChangedBlocks(relatorio) ?: return Leitura.SemSecao
        val porBloco = linkedMapOf<String, Entrada>()
        for (fragmento in fragmentosDeEntrada(secao)) {
            val campos = CamposDaEntrada.ler(fragmento)
            val ids = campos.valoresDe("block_id").mapNotNull { idDeBloco(it) }.distinct()
            when {
                ids.isEmpty() -> continue

                ids.size > 1 -> return Leitura.Ambigua(
                    "changed_blocks entry declares more than one block_id " +
                        "(${ids.joinToString(", ")})",
                )
            }
            val id = ids.single()
            if (porBloco.containsKey(id)) {
                return Leitura.Ambigua(
                    "changed_blocks declares $id more than once",
                )
            }
            porBloco[id] = Entrada(
                id = id,
                temBaseDeProtocolo = campos.valoresDe("protocol_basis").any { temConteudo(it) },
                permiteCrescimento = campos.valoresDe("change_type")
                    .any { autoriza(it, VALORES_DE_CRESCIMENTO) },
                permiteReordenacao = campos.valoresDe("change_type")
                    .any { autoriza(it, VALORES_DE_REORDENACAO) },
            )
        }
        return Leitura.Entradas(porBloco)
    }

    /**
     * Identificador de bloco, com **quatro ou mais** dígitos: o segmentador
     * emite `B10000` no bloco dez mil, e aceitar só quatro dígitos tornaria
     * aquele bloco impossível de declarar.
     */
    private val ID_DE_BLOCO = Regex("""^B(\d{4,})$""")

    private fun idDeBloco(valor: String): String? {
        val limpo = EspacoUnicode.aparar(valor)
        val caixaAlta = limpo.uppercase()
        return if (ID_DE_BLOCO.matches(caixaAlta)) caixaAlta else null
    }

    /**
     * Um `change_type` autoriza quando **o valor inteiro** é um dos termos, ou
     * quando o valor é uma lista cujos itens são termos. Nunca quando o termo
     * aparece como pedaço de outra palavra.
     */
    private fun autoriza(valor: String, termos: Set<String>): Boolean =
        valor.split(',', ';', '|', '/')
            .map { EspacoUnicode.caixaBaixaAscii(EspacoUnicode.aparar(it)) }
            .any { it in termos }

    /**
     * Um valor conta como preenchido quando tem conteúdo de verdade. `null`,
     * `[]`, `{}`, `none`, `n/a` e `-` são declaração vazia com aparência de
     * declaração, e é exatamente o que a trava existe para recusar.
     */
    private fun temConteudo(valor: String): Boolean {
        val limpo = EspacoUnicode.aparar(valor)
        if (limpo.isEmpty()) return false
        val rebaixado = EspacoUnicode.caixaBaixaAscii(limpo)
        return rebaixado !in setOf("null", "none", "n/a", "na", "-", "[]", "{}", "\"\"", "''")
    }

    /**
     * A fatia do relatório que vai do começo da seção de blocos alterados até o
     * próximo campo conhecido — ou até o fim, se não houver próximo.
     *
     * O texto rebaixado só troca A–Z, então as posições achadas nele valem no
     * texto original.
     */
    /**
     * A seção é o **valor** do campo `changed_blocks`, delimitado por estrutura.
     *
     * A versão anterior ia da chave até o próximo terminador de uma **lista
     * fechada** de campos conhecidos. Um campo que o agente inventasse não
     * estava na lista, então a seção o engolia e a trava lia declaração de
     * dentro dele:
     *
     * ```json
     * {"changed_blocks": [], "notes": {"block_id": "B0001", "protocol_basis": "x"}}
     * ```
     *
     * `changed_blocks` está vazio, mas o `notes` entrava na seção e autorizava
     * uma mudança que ninguém declarou. Lista fechada de terminadores é a mesma
     * doença da decisão por substring: depende de enumerar o que o modelo pode
     * escrever, e ele sempre escreve mais.
     *
     * Agora o valor é lido pela própria estrutura — `[...]` ou `{...}`
     * equilibrado, ciente de aspas —, e só se cai na lista de terminadores
     * quando o valor não abre delimitador nenhum (relatório em YAML solto).
     */
    internal fun extrairSecaoChangedBlocks(relatorio: String): String? {
        val rebaixado = EspacoUnicode.caixaBaixaAscii(relatorio)
        val inicio = acharChave(rebaixado, CHAVES_DE_INICIO, 0) ?: return null
        // Retomar DEPOIS da chave inteira, e não em inicio+1, que cairia dentro
        // dela: o varredor trataria a aspa de fechamento da chave como aspa de
        // abertura e perderia a estrutura seguinte.
        val depoisDaChave = fimDaChave(rebaixado, inicio, CHAVES_DE_INICIO)
        val depoisDoSeparador = pularSeparador(rebaixado, depoisDaChave)

        delimitadorDoValor(rebaixado, depoisDoSeparador)?.let { fim ->
            return relatorio.substring(depoisDoSeparador, fim)
        }

        // Valor sem delimitador: vale a lista de terminadores conhecidos, que é
        // o melhor que se consegue num YAML solto — e por isso a leitura de
        // entradas exige `block_id` em posição de campo para contar qualquer
        // coisa.
        val fim = acharChave(rebaixado, CHAVES_DE_FIM, depoisDoSeparador) ?: relatorio.length
        return relatorio.substring(inicio, fim)
    }

    private fun pularSeparador(palheiro: String, de: Int): Int {
        var indice = de
        while (indice < palheiro.length && EspacoUnicode.ehEspacoAscii(palheiro[indice])) indice++
        if (indice < palheiro.length && (palheiro[indice] == ':' || palheiro[indice] == '=')) {
            indice++
        }
        while (indice < palheiro.length && EspacoUnicode.ehEspacoAscii(palheiro[indice])) indice++
        return indice
    }

    /** O fim do valor delimitado que começa em [de], ou nulo se não houver. */
    private fun delimitadorDoValor(palheiro: String, de: Int): Int? {
        val abre = palheiro.getOrNull(de) ?: return null
        val fecha = when (abre) {
            '[' -> ']'
            '{' -> '}'
            else -> return null
        }
        var profundidade = 0
        var aspaAberta: Char? = null
        var escapado = false
        var indice = de
        while (indice < palheiro.length) {
            val caractere = palheiro[indice]
            val aspa = aspaAberta
            if (aspa != null) {
                when {
                    escapado -> escapado = false
                    caractere == '\\' -> escapado = true
                    caractere == aspa -> aspaAberta = null
                }
                indice++
                continue
            }
            when (caractere) {
                '"' -> aspaAberta = caractere
                abre -> profundidade++
                fecha -> {
                    profundidade--
                    if (profundidade == 0) return indice + 1
                }
            }
            indice++
        }
        return null
    }

    /** A posição logo após a chave que começa em [inicio], com aspas se houver. */
    private fun fimDaChave(palheiro: String, inicio: Int, chaves: List<String>): Int {
        val primeiro = palheiro.getOrNull(inicio)
        if (primeiro == '"' || primeiro == '\'') {
            val fechamento = palheiro.indexOf(primeiro, inicio + 1)
            if (fechamento >= 0) return fechamento + 1
        }
        val chave = chaves.firstOrNull { palheiro.startsWith(it, inicio) }
        return inicio + (chave?.length ?: 1)
    }

    /**
     * Procura a primeira ocorrência de uma das chaves em posição de campo, a
     * partir de [de]. O que estiver dentro de aspas é pulado, para que um valor
     * citando o nome de um campo não passe por campo.
     */
    private fun acharChave(palheiro: String, chaves: List<String>, de: Int): Int? {
        var indice = de
        var aspaAberta: Char? = null
        var escapado = false
        while (indice < palheiro.length) {
            val caractere = palheiro[indice]
            val aspa = aspaAberta
            if (aspa != null) {
                when {
                    escapado -> escapado = false
                    caractere == '\\' -> escapado = true
                    caractere == aspa -> aspaAberta = null
                }
                indice++
                continue
            }
            if (caractere == '"' || caractere == '\'') {
                val fechamento = palheiro.indexOf(caractere, indice + 1)
                if (fechamento >= 0) {
                    val candidato = palheiro.substring(indice + 1, fechamento)
                    if (candidato in chaves &&
                        emPosicaoDeCampo(palheiro, indice) &&
                        atribuicaoDepois(palheiro, fechamento + 1)
                    ) {
                        return indice
                    }
                }
                // Só a aspa dupla passa a valer como abertura de string. O
                // apóstrofo é pontuação comum em prosa — "Here's the report:" —
                // e tratá-lo como abertura engolia o resto do relatório como
                // conteúdo de string: a seção sumia e a trava recusava trabalho
                // legítimo. A chave citada com apóstrofo continua reconhecida
                // acima; o que muda é ele não mexer mais no estado do varredor.
                if (caractere == '"') aspaAberta = caractere
                indice++
                continue
            }
            if (emPosicaoDeCampo(palheiro, indice)) {
                for (chave in chaves) {
                    if (palheiro.startsWith(chave, indice) &&
                        atribuicaoDepois(palheiro, indice + chave.length)
                    ) {
                        return indice
                    }
                }
            }
            indice++
        }
        return null
    }

    /**
     * Uma chave está em posição de campo quando vem no começo do texto, no
     * **começo de uma linha**, ou depois de `{`, `[` ou `,`.
     *
     * O começo de linha entra por medição: um relatório que abre com uma frase
     * — `As mudanças desta rodada:` e só então `changed_blocks:` — tinha a
     * seção inteira ignorada pela versão anterior, porque o último caractere
     * não branco antes da chave era o `:` da frase. A trava passava a agir como
     * se não houvesse declaração nenhuma, e recusava trabalho legítimo. Portão
     * que barra trabalho bom é desligado pelo usuário, que é como um portão
     * morre de verdade.
     */
    private fun emPosicaoDeCampo(palheiro: String, indice: Int): Boolean {
        if (indice == 0) return true
        var anterior = indice - 1
        while (anterior >= 0) {
            val caractere = palheiro[anterior]
            if (caractere == '\n' || caractere == '\r') return true
            if (!EspacoUnicode.ehEspacoAscii(caractere)) {
                return caractere == '{' || caractere == '[' || caractere == ',' ||
                    caractere == '-' // item de lista em YAML
            }
            anterior--
        }
        return true
    }

    private fun atribuicaoDepois(palheiro: String, indice: Int): Boolean {
        for (posterior in indice until palheiro.length) {
            val caractere = palheiro[posterior]
            if (EspacoUnicode.ehEspacoAscii(caractere)) continue
            return caractere == ':' || caractere == '='
        }
        return false
    }

    /**
     * As entradas da seção. Primeiro os objetos `{...}` equilibrados, com a
     * contagem de profundidade **ciente de aspas** — sem isso, uma chave solta
     * dentro de uma justificativa parte a entrada no meio e o `protocol_basis`
     * que vem depois é perdido.
     *
     * Quando não há objeto nenhum — relatório em lista YAML —, agrupa as linhas
     * por entrada em vez de guardar só as que contêm `block_id`: num YAML
     * normal, `block_id` e `protocol_basis` estão em linhas irmãs, e filtrar
     * linha a linha jogava fora justamente a justificativa.
     */
    private fun fragmentosDeEntrada(secao: String): List<String> {
        val objetos = objetosEquilibrados(secao)
        if (objetos.isNotEmpty()) return objetos
        return entradasDeLista(secao)
    }

    private fun objetosEquilibrados(secao: String): List<String> {
        val fragmentos = mutableListOf<String>()
        var profundidade = 0
        var inicio = -1
        var aspaAberta: Char? = null
        var escapado = false
        for (indice in secao.indices) {
            val caractere = secao[indice]
            val aspa = aspaAberta
            if (aspa != null) {
                when {
                    escapado -> escapado = false
                    caractere == '\\' -> escapado = true
                    caractere == aspa -> aspaAberta = null
                }
                continue
            }
            when (caractere) {
                '"', '\'' -> aspaAberta = caractere

                '{' -> {
                    if (profundidade == 0) inicio = indice
                    profundidade++
                }

                '}' -> if (profundidade > 0) {
                    profundidade--
                    if (profundidade == 0 && inicio >= 0) {
                        fragmentos += secao.substring(inicio, indice + 1)
                        inicio = -1
                    }
                }
            }
        }
        return fragmentos
    }

    /**
     * Agrupa as linhas da seção em entradas. Uma entrada começa numa linha de
     * item — `- ` — ou numa linha que declara `block_id`, e segue até a próxima
     * linha de item ou até uma linha menos indentada que a de abertura.
     */
    private fun entradasDeLista(secao: String): List<String> {
        val entradas = mutableListOf<String>()
        var atual: MutableList<String>? = null
        var indentacaoDeAbertura = 0
        for (linha in secao.lines()) {
            if (EspacoUnicode.soEspaco(linha)) {
                atual?.add(linha)
                continue
            }
            val indentacao = linha.length - EspacoUnicode.apararInicio(linha).length
            val semIndentacao = EspacoUnicode.apararInicio(linha)
            val abreItem = semIndentacao.startsWith("- ") || semIndentacao.startsWith("-\t")
            val declaraBloco = EspacoUnicode.caixaBaixaAscii(linha).contains("block_id")

            val comecaEntrada = abreItem || (atual == null && declaraBloco)
            val saiuDaEntrada = atual != null && !abreItem && indentacao < indentacaoDeAbertura

            if (comecaEntrada || saiuDaEntrada) {
                atual?.let { if (it.isNotEmpty()) entradas += it.joinToString("\n") }
                atual = if (comecaEntrada) mutableListOf(linha) else null
                indentacaoDeAbertura = indentacao
                continue
            }
            atual?.add(linha)
        }
        atual?.let { if (it.isNotEmpty()) entradas += it.joinToString("\n") }
        return entradas
    }
}
