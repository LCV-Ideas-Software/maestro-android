package dev.lcv.maestro.protocolo

/**
 * Lê uma entrada do relatório como pares **campo → valor**.
 *
 * Esta é a peça que substitui a decisão por substring. A versão anterior
 * perguntava "a palavra `addition` aparece em algum lugar deste fragmento?"; a
 * pergunta certa é "qual é o valor do campo `change_type`?", e as duas dão
 * respostas diferentes exatamente nos casos que importam:
 *
 * ```
 * { "block_id": "B0002", "change_type": "edit",
 *   "reason": "considerei a addition de um parágrafo e desisti" }
 * ```
 *
 * Aqui `change_type` vale `edit` e não autoriza nada. A leitura por substring
 * autorizava criar blocos novos.
 *
 * O varredor é ciente de aspas e de escape, e ignora tudo o que estiver dentro
 * de um valor: chave, dois-pontos, vírgula e nome de campo dentro de uma
 * justificativa são texto, não estrutura.
 *
 * Não é um parser de JSON, e não pretende ser: o relatório do agente nem sempre
 * é JSON válido — vem com prosa em volta, cerca de markdown, YAML aproximado.
 * Um parser estrito falharia na maioria dos relatórios reais e empurraria a
 * decisão de volta para o caminho tolerante, que é onde a segurança mora. O que
 * este varredor garante é mais modesto e suficiente: **valor é valor, texto é
 * texto**.
 */
internal class CamposDaEntrada private constructor(
    private val pares: List<Pair<String, String>>,
) {

    /** Todos os valores declarados para [campo], na ordem em que aparecem. */
    fun valoresDe(campo: String): List<String> {
        val procurado = EspacoUnicode.caixaBaixaAscii(campo)
        return pares.filter { it.first == procurado }.map { it.second }
    }

    internal companion object {

        fun ler(fragmento: String): CamposDaEntrada {
            val pares = mutableListOf<Pair<String, String>>()
            var indice = 0
            while (indice < fragmento.length) {
                val chave = proximaChave(fragmento, indice) ?: break
                val (nome, depoisDoNome) = chave
                val separador = posicaoDaAtribuicao(fragmento, depoisDoNome)
                if (separador == null) {
                    indice = depoisDoNome
                    continue
                }
                val (valor, depoisDoValor) = leValor(fragmento, separador + 1)
                pares += EspacoUnicode.caixaBaixaAscii(nome) to valor
                indice = depoisDoValor
            }
            return CamposDaEntrada(pares)
        }

        /**
         * O próximo nome de campo em posição de estrutura, com a posição logo
         * após ele. Aspas em volta do nome são aceitas e descartadas.
         */
        private fun proximaChave(texto: String, de: Int): Pair<String, Int>? {
            var indice = de
            while (indice < texto.length) {
                val caractere = texto[indice]
                if (caractere == '"' || caractere == '\'') {
                    val fechamento = texto.indexOf(caractere, indice + 1)
                    if (fechamento < 0) return null
                    val nome = texto.substring(indice + 1, fechamento)
                    if (ehNomeDeCampo(nome)) return nome to fechamento + 1
                    // Não é nome de campo: é um valor citado. Pular inteiro,
                    // para que o que estiver dentro dele nunca seja lido como
                    // estrutura.
                    indice = fechamento + 1
                    continue
                }
                if (ehInicioDeNome(caractere)) {
                    var fim = indice
                    while (fim < texto.length && ehCorpoDeNome(texto[fim])) fim++
                    val nome = texto.substring(indice, fim)
                    if (ehNomeDeCampo(nome) && posicaoDaAtribuicao(texto, fim) != null) {
                        return nome to fim
                    }
                    indice = if (fim > indice) fim else indice + 1
                    continue
                }
                indice++
            }
            return null
        }

        private fun ehInicioDeNome(caractere: Char): Boolean =
            caractere in 'a'..'z' || caractere in 'A'..'Z' || caractere == '_'

        private fun ehCorpoDeNome(caractere: Char): Boolean =
            ehInicioDeNome(caractere) || caractere in '0'..'9'

        /** Só os campos que a trava conhece contam como estrutura. */
        private fun ehNomeDeCampo(nome: String): Boolean =
            EspacoUnicode.caixaBaixaAscii(nome) in CAMPOS_CONHECIDOS

        private val CAMPOS_CONHECIDOS = setOf(
            "block_id",
            "protocol_basis",
            "change_type",
            "reason",
            "excerpt",
            "kind",
        )

        /** A posição de `:` ou `=`, se for o próximo não-branco. */
        private fun posicaoDaAtribuicao(texto: String, de: Int): Int? {
            for (indice in de until texto.length) {
                val caractere = texto[indice]
                if (EspacoUnicode.ehEspacoAscii(caractere)) continue
                return if (caractere == ':' || caractere == '=') indice else null
            }
            return null
        }

        /**
         * O valor que começa em [de], e a posição logo depois dele. Valor
         * citado respeita escape; valor em lista ou objeto é lido equilibrado;
         * valor nu termina na vírgula, no fechamento ou na quebra de linha.
         */
        private fun leValor(texto: String, de: Int): Pair<String, Int> {
            var indice = de
            while (indice < texto.length && EspacoUnicode.ehEspacoAscii(texto[indice])) indice++
            if (indice >= texto.length) return "" to indice

            return when (val primeiro = texto[indice]) {
                '"', '\'' -> leCitado(texto, indice + 1, primeiro)
                '[' -> leEquilibrado(texto, indice, '[', ']')
                '{' -> leEquilibrado(texto, indice, '{', '}')
                else -> leNu(texto, indice)
            }
        }

        private fun leCitado(texto: String, de: Int, aspa: Char): Pair<String, Int> {
            val construtor = StringBuilder()
            var indice = de
            var escapado = false
            while (indice < texto.length) {
                val caractere = texto[indice]
                when {
                    escapado -> {
                        construtor.append(caractere)
                        escapado = false
                    }

                    caractere == '\\' -> escapado = true
                    caractere == aspa -> return construtor.toString() to indice + 1
                    else -> construtor.append(caractere)
                }
                indice++
            }
            // Aspa sem fechamento: devolve o que veio, e a posição final.
            return construtor.toString() to indice
        }

        private fun leEquilibrado(
            texto: String,
            de: Int,
            abre: Char,
            fecha: Char,
        ): Pair<String, Int> {
            var profundidade = 0
            var aspaAberta: Char? = null
            var escapado = false
            var indice = de
            while (indice < texto.length) {
                val caractere = texto[indice]
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
                    '"', '\'' -> aspaAberta = caractere
                    abre -> profundidade++
                    fecha -> {
                        profundidade--
                        if (profundidade == 0) {
                            return texto.substring(de + 1, indice) to indice + 1
                        }
                    }
                }
                indice++
            }
            return texto.substring(de) to indice
        }

        private fun leNu(texto: String, de: Int): Pair<String, Int> {
            var indice = de
            while (indice < texto.length) {
                val caractere = texto[indice]
                if (caractere == ',' || caractere == '}' || caractere == ']' ||
                    caractere == '\n' || caractere == '\r'
                ) {
                    break
                }
                indice++
            }
            return EspacoUnicode.aparar(texto.substring(de, indice)) to indice
        }
    }
}
