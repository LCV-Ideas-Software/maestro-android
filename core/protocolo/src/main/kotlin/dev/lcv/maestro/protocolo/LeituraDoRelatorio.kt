package dev.lcv.maestro.protocolo

/**
 * Leitura da seção `changed_blocks` do `maestro_revision_report`.
 *
 * O relatório do agente não é JSON garantido: vem em formato solto, às vezes
 * com aspas, às vezes sem, às vezes em YAML aproximado. Por isso o canônico não
 * usa parser de JSON — varre o texto procurando a chave do campo em posição
 * plausível, e é esse comportamento que este arquivo reproduz.
 *
 * Porte de `maestro-app/src-tauri/src/editorial_content_lock.rs`.
 *
 * **Sobre as expressões regulares:** todas levam `(?isU)`. O `U` é
 * `UNICODE_CHARACTER_CLASS`, e sem ele o `\s` do Java é ASCII puro e o `\d` só
 * pega `0-9` — enquanto no Rust `\s` é Unicode White_Space e `\d` é `\p{Nd}`.
 * Medido no JDK 17.0.20.1+1 em 21/09/2026: sem `U`, um `block_id` separado
 * do `:` por um no-break space não casa, e `B` seguido de dígitos devanágari
 * não casa; com `U`, os dois casam, como no canônico.
 */
internal object LeituraDoRelatorio {

    private val CAMPO_BLOCK_ID =
        Regex("""(?isU)["']?block_id["']?\s*[:=]\s*["']?(B\d{4})\b""")

    private val CHAVE_PROTOCOL_BASIS =
        Regex("""(?isU)["']?protocol_basis["']?\s*[:=]\s*""")

    private val CHAVES_DE_INICIO = listOf("changed_blocks", "changes")

    private val CHAVES_DE_FIM = listOf(
        "operator_evidence_required",
        "out_of_scope",
        "quality_preservation",
        "unchanged_approved_blocks",
        "custody",
    )

    /**
     * A fatia do relatório que vai do começo da seção de blocos alterados até o
     * próximo campo conhecido — ou até o fim, se não houver próximo.
     *
     * O texto rebaixado só troca A–Z, então as posições achadas nele valem no
     * texto original.
     */
    fun extrairSecaoChangedBlocks(relatorio: String): String? {
        val rebaixado = EspacoUnicode.caixaBaixaAscii(relatorio)
        val inicio = acharPrimeiraChave(rebaixado, CHAVES_DE_INICIO, 0) ?: return null
        val fim = acharPrimeiraChave(rebaixado, CHAVES_DE_FIM, inicio + 1) ?: relatorio.length
        return relatorio.substring(inicio, fim)
    }

    /** As declarações por `block_id`, somando os sinais quando houver repetição. */
    fun extrairDeclaracoes(secao: String): Map<String, TravaDeConteudo.Declaracao> {
        val declaracoes = sortedMapOf<String, TravaDeConteudo.Declaracao>()
        for (fragmento in fragmentosDeEntrada(secao)) {
            val id = CAMPO_BLOCK_ID.find(fragmento)?.groupValues?.get(1) ?: continue
            val nova = TravaDeConteudo.Declaracao(
                temBaseDeProtocolo = temBaseDeProtocoloNaoVazia(fragmento),
                permiteCrescimento = declaraCrescimento(fragmento),
                permiteReordenacao = declaraReordenacao(fragmento),
            )
            val anterior = declaracoes[id]
            declaracoes[id] = if (anterior == null) {
                nova
            } else {
                TravaDeConteudo.Declaracao(
                    temBaseDeProtocolo = anterior.temBaseDeProtocolo || nova.temBaseDeProtocolo,
                    permiteCrescimento = anterior.permiteCrescimento || nova.permiteCrescimento,
                    permiteReordenacao = anterior.permiteReordenacao || nova.permiteReordenacao,
                )
            }
        }
        return declaracoes
    }

    /**
     * Procura a primeira ocorrência de uma das chaves em posição de campo:
     * precedida por `{`, `[`, `,` ou quebra de linha, e seguida por `:` ou `=`.
     * O que estiver dentro de aspas é pulado, para que um valor citando o nome
     * de um campo não passe por campo.
     */
    private fun acharPrimeiraChave(palheiro: String, chaves: List<String>, de: Int): Int? {
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
                        delimitadaAntes(palheiro, indice) &&
                        atribuicaoDepois(palheiro, fechamento + 1)
                    ) {
                        return indice
                    }
                }
                aspaAberta = caractere
                indice++
                continue
            }
            if (delimitadaAntes(palheiro, indice)) {
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

    private fun delimitadaAntes(palheiro: String, indice: Int): Boolean {
        if (indice == 0) return true
        for (anterior in indice - 1 downTo 0) {
            val caractere = palheiro[anterior]
            if (EspacoUnicode.ehEspacoAscii(caractere)) continue
            return caractere == '{' || caractere == '[' || caractere == ','
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
     * Os objetos `{...}` equilibrados da seção. Quando não há nenhum — relatório
     * escrito em lista, sem chaves —, cai para as linhas que citam `block_id`.
     */
    private fun fragmentosDeEntrada(secao: String): List<String> {
        val fragmentos = mutableListOf<String>()
        var profundidade = 0
        var inicio = -1
        for (indice in secao.indices) {
            when (secao[indice]) {
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
        if (fragmentos.isEmpty()) {
            fragmentos += secao.lines()
                .filter { EspacoUnicode.caixaBaixaAscii(it).contains("block_id") }
        }
        return fragmentos
    }

    private fun temBaseDeProtocoloNaoVazia(fragmento: String): Boolean {
        val chave = CHAVE_PROTOCOL_BASIS.find(fragmento) ?: return false
        return valorNaoVazio(fragmento.substring(chave.range.last + 1))
    }

    /**
     * Um `protocol_basis` conta como preenchido quando tem conteúdo de verdade.
     * `null`, `[]` e `{}` são declaração vazia com aparência de declaração, e é
     * exatamente o que a trava existe para recusar.
     */
    private fun valorNaoVazio(bruto: String): Boolean {
        val valor = EspacoUnicode.apararInicio(bruto)
        if (valor.isEmpty()) return false
        return when (valor.first()) {
            '"' -> citadoNaoVazio(valor.substring(1), '"')
            '\'' -> citadoNaoVazio(valor.substring(1), '\'')
            '[' -> delimitadoNaoVazio(valor.substring(1), '[', ']')
            '{' -> delimitadoNaoVazio(valor.substring(1), '{', '}')
            else -> {
                val simbolo = EspacoUnicode.aparar(primeiroSimbolo(valor))
                simbolo.isNotEmpty() &&
                    !simbolo.equals("null", ignoreCase = true) &&
                    simbolo != "[]" && simbolo != "{}"
            }
        }
    }

    /** O primeiro símbolo nu, até espaço Unicode ou um dos fechamentos. */
    private fun primeiroSimbolo(valor: String): String {
        var indice = 0
        while (indice < valor.length) {
            val pontoDeCodigo = valor.codePointAt(indice)
            val caractere = valor[indice]
            if (EspacoUnicode.ehEspaco(pontoDeCodigo) ||
                caractere == ',' || caractere == '}' || caractere == ']'
            ) {
                return valor.substring(0, indice)
            }
            indice += Character.charCount(pontoDeCodigo)
        }
        return valor
    }

    private fun citadoNaoVazio(resto: String, aspa: Char): Boolean {
        var escapado = false
        val valor = StringBuilder()
        for (caractere in resto) {
            when {
                escapado -> {
                    valor.append(caractere)
                    escapado = false
                }

                caractere == '\\' -> escapado = true
                caractere == aspa -> return !EspacoUnicode.soEspaco(valor.toString())
                else -> valor.append(caractere)
            }
        }
        return false
    }

    private fun delimitadoNaoVazio(resto: String, abre: Char, fecha: Char): Boolean {
        var profundidade = 1
        val corpo = StringBuilder()
        var aspaAberta: Char? = null
        var escapado = false
        for (caractere in resto) {
            val aspa = aspaAberta
            if (aspa != null) {
                corpo.append(caractere)
                when {
                    escapado -> escapado = false
                    caractere == '\\' -> escapado = true
                    caractere == aspa -> aspaAberta = null
                }
                continue
            }
            when (caractere) {
                '"', '\'' -> {
                    aspaAberta = caractere
                    corpo.append(caractere)
                }

                abre -> {
                    profundidade++
                    corpo.append(caractere)
                }

                fecha -> {
                    profundidade--
                    if (profundidade == 0) return !EspacoUnicode.soEspaco(corpo.toString())
                    corpo.append(caractere)
                }

                else -> corpo.append(caractere)
            }
        }
        return false
    }

    private fun declaraCrescimento(fragmento: String): Boolean {
        val rebaixado = EspacoUnicode.caixaBaixaAscii(fragmento)
        return rebaixado.contains("change_type") && (
            rebaixado.contains("split") || rebaixado.contains("addition") ||
                rebaixado.contains("added") || rebaixado.contains("new_block") ||
                rebaixado.contains("new block")
            )
    }

    private fun declaraReordenacao(fragmento: String): Boolean {
        val rebaixado = EspacoUnicode.caixaBaixaAscii(fragmento)
        return rebaixado.contains("change_type") && (
            rebaixado.contains("reorder") || rebaixado.contains("reordered") ||
                rebaixado.contains("move") || rebaixado.contains("moved")
            )
    }
}
