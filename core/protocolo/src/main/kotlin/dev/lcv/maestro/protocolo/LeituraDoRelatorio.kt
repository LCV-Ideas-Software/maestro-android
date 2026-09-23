package dev.lcv.maestro.protocolo

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper

/**
 * Leitura do `maestro_revision_report`: a seção `changed_blocks` e o registro de
 * procedência `revised_block_origins`.
 *
 * ## Por que este arquivo foi reescrito uma segunda vez
 *
 * A versão anterior varria o relatório com um leitor escrito à mão — ciente de
 * aspas, de escape, de colchete equilibrado, de item de lista YAML, de escalar
 * de bloco, de comentário. Setecentas e setenta e seis linhas. Em quatro
 * rodadas de revisão ela recebeu 9, 7, 5 e 6 achados, e os da última rodada
 * reduziam-se todos a uma frase: **um varredor escrito à mão não é um parser**.
 * Indentação de escalar de bloco, aspa simples no varredor externo, item de
 * lista aninhado, `"\u0020"` chegando como o texto `u0020`, campos duplicados
 * unidos em vez de recusados. Esse conjunto não é uma lista de buracos: é tudo
 * o que JSON e YAML conseguem expressar, e enumerar isso à mão não converge.
 *
 * ## O que o contrato realmente diz
 *
 * Medido no canônico, não recordado:
 *
 * - `editorial_prompts.rs:487` — o agente emite `<maestro_revision_report>`
 *   com *"en_US **JSON-like** audit data"*.
 * - `editorial_prompts.rs:503` — *"An incomplete tag, missing closing tag,
 *   reproduced protocol text, or **truncated JSON/report is a contract
 *   violation** and will not count as READY."*
 * - `session_orchestration.rs:2546` — `extract_tagged_block` recorta a tag e
 *   `require_balanced_tag` confere o balanceamento **antes** da trava rodar.
 * - `session_orchestration.rs:2595` — a trava recebe o **conteúdo da tag**, já
 *   isolado. Nunca prosa livre.
 * - 19 de 19 fixtures de relatório da suíte canônica abrem com `{`. Nenhuma
 *   linha de YAML em lugar nenhum.
 *
 * Ou seja: a varredura de prosa resolvia um problema que o contrato não tem, e
 * o caminho YAML implementava um formato que o contrato nunca prometeu. As
 * duas coisas eram superfície inventada, e era nelas que os achados moravam.
 *
 * ## O que este arquivo faz agora
 *
 * O relatório é JSON e é lido com parser de verdade, com **detecção estrita de
 * chave duplicada ligada** — `StreamReadFeature.STRICT_DUPLICATE_DETECTION`,
 * que a FasterXML documenta como desligada por padrão e que lança
 * `JsonParseException` quando um objeto repete um nome de campo. Isso fecha,
 * sem uma linha de lógica minha, a entrada com dois `block_id`, dois
 * `protocol_basis` ou dois `change_type`.
 *
 * Relatório que não parseia é **violação de contrato**, não motivo para cair
 * num caminho tolerante — o caminho tolerante é onde a insegurança morava, e o
 * próprio prompt canônico já declara essa violação.
 *
 * A seção é lida do **nível de topo** do documento. Uma ocorrência aninhada de
 * `changed_blocks` dentro de `metadata` deixa de ser alcançável por
 * construção, em vez de ser recusada por uma conferência extra.
 *
 * Porte de `maestro-app/src-tauri/src/editorial_content_lock.rs`, com dois
 * afastamentos declarados. O canônico varre o relatório à mão e tem os mesmos
 * buracos, registrados no rastreador dele (MAESTRO-30). E o registro de
 * procedência é exigência deste repositório, não do canônico — ver
 * [TravaDeConteudo] e a Discussion #41.
 */
internal object LeituraDoRelatorio {

    /**
     * Leitor único e imutável. `ObjectMapper` é documentado como seguro para
     * uso concorrente depois de configurado, e reconfigurá-lo por chamada é o
     * que a FasterXML desaconselha.
     *
     * Visível no módulo porque [TurnoSerial] lê o mesmo relatório. Dois leitores
     * com configurações diferentes poderiam aceitar num caminho o relatório que
     * o outro recusa.
     */
    internal val LEITOR: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        // Sem isto, o leitor casa o primeiro valor e **ignora o que vier
        // depois**: `{"changed_blocks":[...]} {"changed_blocks":[]}` passaria
        // pela primeira ocorrência e a segunda sumiria sem aviso. A FasterXML
        // documenta a opção como desligada por retrocompatibilidade.
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** Valores de `change_type` que autorizam crescer o número de blocos. */
    private val VALORES_DE_CRESCIMENTO =
        setOf("split", "addition", "added", "new_block", "new block", "insert", "inserted")

    /**
     * Valor de `change_type` que autoriza um recebido a continuar em mais de um
     * bloco revisado. Só `split`: `addition` também autoriza crescer, mas
     * declara bloco novo, e não a continuação de um recebido.
     */
    private val VALORES_DE_DIVISAO = setOf("split")

    /** Valores de `change_type` que declaram bloco novo: os de crescimento, menos `split`. */
    private val VALORES_DE_ACRESCIMO = VALORES_DE_CRESCIMENTO - VALORES_DE_DIVISAO

    /** Valores de `change_type` que autorizam reordenar. */
    private val VALORES_DE_REORDENACAO =
        setOf("reorder", "reordered", "move", "moved", "reposition", "repositioned")

    /**
     * Nome da seção. O canônico antigo também aceitava `changes`
     * (`find_first_report_field_key(&lower, &["changed_blocks", "changes"])`),
     * mas desde a v00.05.65 `changes` é outra lista: a dos trechos alterados,
     * que [TurnoSerial] lê. Com o apelido, a mesma lista valeria pelas duas
     * coisas.
     */
    private val NOMES_DA_SECAO = listOf("changed_blocks")

    /** Nome da seção que declara a procedência dos blocos revisados. */
    internal const val NOME_DO_REGISTRO = "revised_block_origins"

    /** Valor de `origin` que marca bloco novo. */
    private const val ORIGEM_ACRESCIMO = "addition"

    /** O que uma entrada de `changed_blocks` declara, depois de lida. */
    internal data class Entrada(
        val id: String,
        val temBaseDeProtocolo: Boolean,
        val permiteCrescimento: Boolean,
        val permiteDivisao: Boolean,
        val permiteAcrescimo: Boolean,
        val permiteReordenacao: Boolean,
    )

    /**
     * Uma entrada do registro de procedência: **um bloco do texto revisado**,
     * na mesma ordem do texto.
     *
     * [prefixo] é o começo do bloco, copiado — conferência do bloco daquela
     * ordem, não localizador. [origem] é o `block_id` recebido de onde o bloco
     * vem, ou `null` quando ele é novo. [temBaseDeProtocolo] vale para a
     * justificativa escrita **na própria entrada**, que todo bloco extra tem
     * de trazer: acréscimo, e todo pedaço de divisão depois do primeiro.
     */
    internal data class Procedencia(
        val prefixo: String,
        val origem: String?,
        val temBaseDeProtocolo: Boolean,
    )

    /** Resultado da leitura da seção. */
    internal sealed interface Leitura {
        /** O relatório não traz nem `changed_blocks` nem registro de procedência. */
        data object SemSecao : Leitura

        /**
         * As declarações lidas: [porBloco], de `changed_blocks`, e
         * [procedencias], o registro. [procedencias] é `null` quando o registro
         * **não veio** — distinto de vir vazio, porque ausência e conteúdo
         * errado pedem correções diferentes ao agente.
         */
        data class Entradas(
            val porBloco: Map<String, Entrada>,
            val procedencias: List<Procedencia>?,
        ) : Leitura

        /**
         * A seção existe mas é ambígua — duas entradas para o mesmo bloco, ou
         * dois nomes de topo que valem pela mesma seção. Num portão de
         * integridade isso **não** se resolve escolhendo uma nem somando as
         * duas.
         */
        data class Ambigua(val motivo: String) : Leitura

        /**
         * O relatório não é JSON válido. Distinto de [Ambigua] porque a
         * correção que o agente precisa fazer é outra: reemitir o relatório,
         * não desfazer uma declaração conflitante.
         */
        data class Invalida(val motivo: String) : Leitura
    }

    fun ler(relatorio: String): Leitura {
        val raiz = try {
            LEITOR.readTree(relatorio)
        } catch (erro: JacksonException) {
            return Leitura.Invalida(
                "maestro_revision_report is not valid JSON: ${primeiraLinha(erro)}",
            )
        }
        // Relatório vazio não é relatório inválido: é relatório sem seção, e o
        // portão já fecha sozinho quando houver mudança sem declaração.
        if (raiz == null || raiz.isMissingNode || raiz.isNull) return Leitura.SemSecao
        if (!raiz.isObject) {
            return Leitura.Invalida(
                "maestro_revision_report must be a JSON object at the top level",
            )
        }

        val procedencias = when (val lido = lerRegistro(raiz)) {
            is Registro.Falha -> return lido.leitura
            is Registro.Lido -> lido.itens
        }

        // Sem `changed_blocks` mas com registro: lê-se como seção vazia, e a
        // trava decide. Toda mudança que exige declaração — edição,
        // reordenação, bloco a mais — continua exigindo a entrada em
        // `changed_blocks`, como o prompt canônico manda.
        val secao = acharSecao(raiz)
            ?: return if (procedencias == null) {
                Leitura.SemSecao
            } else {
                Leitura.Entradas(emptyMap(), procedencias)
            }
        if (secao is Achado.Conflito) return Leitura.Ambigua(secao.motivo)
        val entradas = (secao as Achado.Secao).valor

        // A seção precisa ser a lista que o contrato descreve. Objeto ou
        // escalar no lugar dela é relatório malformado, e adivinhar o que o
        // agente quis dizer é a doença que esta reescrita cura.
        if (!entradas.isArray) {
            return Leitura.Invalida(
                "maestro_revision_report ${secao.nome} must be a JSON array of declarations",
            )
        }

        val porBloco = linkedMapOf<String, Entrada>()
        for (entrada in entradas) {
            // Item que não é objeto não declara nada. Não é erro do agente
            // declarar `[]`, e a trava já recusa mudança não declarada.
            if (!entrada.isObject) continue
            val id = idDeBloco(entrada.get("block_id")) ?: continue
            if (porBloco.containsKey(id)) {
                return Leitura.Ambigua("changed_blocks declares $id more than once")
            }
            porBloco[id] = Entrada(
                id = id,
                temBaseDeProtocolo = temConteudo(entrada.get("protocol_basis")),
                permiteCrescimento = autoriza(entrada.get("change_type"), VALORES_DE_CRESCIMENTO),
                permiteDivisao = autoriza(entrada.get("change_type"), VALORES_DE_DIVISAO),
                permiteAcrescimo = autoriza(entrada.get("change_type"), VALORES_DE_ACRESCIMO),
                permiteReordenacao = autoriza(entrada.get("change_type"), VALORES_DE_REORDENACAO),
            )
        }
        return Leitura.Entradas(porBloco, procedencias)
    }

    // -- Registro de procedência -------------------------------------------

    private sealed interface Registro {
        /** [itens] é `null` quando o registro não veio. */
        data class Lido(val itens: List<Procedencia>?) : Registro
        data class Falha(val leitura: Leitura) : Registro
    }

    /**
     * Lê `revised_block_origins`, **só a forma**. Se o registro cobre o texto
     * revisado e se confere com ele, quem decide é a trava — este objeto não
     * conhece os blocos.
     *
     * ```json
     * "revised_block_origins": [
     *   {"prefix": "The committee met on Tuesday", "origin": "B0001"},
     *   {"prefix": "However, the vote was", "origin": "addition",
     *    "protocol_basis": "..."}
     * ]
     * ```
     */
    private fun lerRegistro(raiz: JsonNode): Registro {
        val nomes = raiz.properties().asSequence().map { it.key }
            .filter { EspacoUnicode.caixaBaixaAscii(it) == NOME_DO_REGISTRO }
            .toList()
        if (nomes.isEmpty()) return Registro.Lido(null)
        if (nomes.size > 1) {
            return Registro.Falha(
                Leitura.Ambigua(
                    "maestro_revision_report declares more than one $NOME_DO_REGISTRO " +
                        "section (${nomes.joinToString(", ")})",
                ),
            )
        }
        val lista = raiz.get(nomes.single())
        if (!lista.isArray) {
            return Registro.Falha(
                Leitura.Invalida("maestro_revision_report $NOME_DO_REGISTRO must be a JSON array"),
            )
        }
        val itens = mutableListOf<Procedencia>()
        for ((indice, item) in lista.withIndex()) {
            val numero = indice + 1
            if (!item.isObject) {
                return Registro.Falha(
                    Leitura.Invalida("$NOME_DO_REGISTRO entry $numero must be a JSON object"),
                )
            }
            val prefixo = item.get("prefix")
            if (prefixo == null || !prefixo.isTextual || EspacoUnicode.soEspaco(prefixo.textValue())) {
                return Registro.Falha(
                    Leitura.Invalida(
                        "$NOME_DO_REGISTRO entry $numero must declare prefix as the opening " +
                            "text of revised block $numero",
                    ),
                )
            }
            val origem = item.get("origin")
            if (origem == null || !origem.isTextual) {
                return Registro.Falha(
                    Leitura.Invalida(
                        "$NOME_DO_REGISTRO entry $numero must declare origin as a received " +
                            "block ID or \"$ORIGEM_ACRESCIMO\"",
                    ),
                )
            }
            val textoDaOrigem = EspacoUnicode.aparar(origem.textValue())
            val id = if (EspacoUnicode.caixaBaixaAscii(textoDaOrigem) == ORIGEM_ACRESCIMO) {
                null
            } else {
                idDeBlocoDeTexto(textoDaOrigem) ?: return Registro.Falha(
                    Leitura.Invalida(
                        "$NOME_DO_REGISTRO entry $numero declares origin \"$textoDaOrigem\", " +
                            "which is neither a block ID nor \"$ORIGEM_ACRESCIMO\"",
                    ),
                )
            }
            itens += Procedencia(
                prefixo = prefixo.textValue(),
                origem = id,
                temBaseDeProtocolo = temConteudo(item.get("protocol_basis")),
            )
        }
        return Registro.Lido(itens)
    }

    // -- Seção -------------------------------------------------------------

    private sealed interface Achado {
        data class Secao(val nome: String, val valor: JsonNode) : Achado
        data class Conflito(val motivo: String) : Achado
    }

    /**
     * A seção, procurada **só no nível de topo**.
     *
     * A comparação de nome ignora caixa porque o canônico rebaixa o relatório
     * inteiro antes de procurar a chave. Dois campos de topo que rebaixam para
     * o mesmo nome — `changed_blocks` e `Changed_Blocks` — não são chave
     * duplicada para o JSON, então o parser não os barra: quem decide passaria
     * a ser a ordem no documento, e isso fecha aqui.
     */
    private fun acharSecao(raiz: JsonNode): Achado? {
        for (nome in NOMES_DA_SECAO) {
            val iguais = raiz.properties().asSequence().map { it.key }
                .filter { EspacoUnicode.caixaBaixaAscii(it) == nome }
                .toList()
            when (iguais.size) {
                0 -> continue
                1 -> return Achado.Secao(nome, raiz.get(iguais.single()))
                else -> return Achado.Conflito(
                    "maestro_revision_report declares more than one $nome section " +
                        "(${iguais.joinToString(", ")})",
                )
            }
        }
        return null
    }

    // -- Campos ------------------------------------------------------------

    /**
     * Identificador de bloco, com **quatro ou mais** dígitos: o segmentador
     * emite `B10000` no bloco dez mil, e aceitar só quatro dígitos tornaria
     * aquele bloco impossível de declarar.
     */
    private val ID_DE_BLOCO = Regex("""^B(\d{4,})$""")

    private fun idDeBloco(no: JsonNode?): String? {
        if (no == null || !no.isTextual) return null
        return idDeBlocoDeTexto(no.textValue())
    }

    private fun idDeBlocoDeTexto(valor: String): String? {
        val caixaAlta = EspacoUnicode.aparar(valor).uppercase()
        return if (ID_DE_BLOCO.matches(caixaAlta)) caixaAlta else null
    }

    /**
     * Um `change_type` autoriza quando **o valor inteiro** é um dos termos, ou
     * quando é uma lista cujos itens são termos. Nunca quando o termo aparece
     * como pedaço de outra palavra: `removed` contém `move`, e era a palavra
     * mais comum de um relatório editorial.
     *
     * A divisão por `,;|/` existe porque o canônico aceita `"edit, reorder"`
     * num campo só, e recusar isso barraria declaração correta.
     */
    private fun autoriza(no: JsonNode?, termos: Set<String>): Boolean {
        if (no == null) return false
        if (no.isArray) return no.any { autoriza(it, termos) }
        if (!no.isTextual) return false
        return no.textValue().split(',', ';', '|', '/')
            .map { EspacoUnicode.caixaBaixaAscii(EspacoUnicode.aparar(it)) }
            .any { it in termos }
    }

    /**
     * Um valor conta como preenchido quando tem conteúdo de verdade.
     *
     * `null`, `[]`, `{}`, `""`, `none`, `n/a` e `-` são declaração vazia com
     * aparência de declaração, e é exatamente o que a trava existe para
     * recusar. O canônico tem caso dedicado para `[]` e `{}`.
     *
     * Escape já vem resolvido pelo parser: `"\u0020"` chega como um espaço e
     * cai no vazio, em vez de chegar como o texto `u0020` e contar como base
     * preenchida.
     */
    private fun temConteudo(no: JsonNode?): Boolean {
        if (no == null || no.isNull || no.isMissingNode) return false
        if (no.isContainerNode) return no.any { temConteudo(it) }
        if (!no.isTextual) return true
        val limpo = EspacoUnicode.aparar(no.textValue())
        if (limpo.isEmpty()) return false
        return EspacoUnicode.caixaBaixaAscii(limpo) !in MARCADORES_VAZIOS
    }

    private val MARCADORES_VAZIOS = setOf("null", "none", "n/a", "na", "-")

    /**
     * A primeira linha da mensagem do parser. A mensagem inteira traz local e
     * trecho do documento, e ela volta ao agente dentro da violação — o
     * relatório pode conter o texto em custódia, que não deve vazar para o
     * histórico de erro.
     */
    internal fun primeiraLinha(erro: JacksonException): String {
        val mensagem = erro.originalMessage ?: return "malformed JSON"
        val corte = mensagem.indexOf('\n')
        val linha = if (corte < 0) mensagem else mensagem.substring(0, corte)
        return EspacoUnicode.aparar(linha).ifEmpty { "malformed JSON" }
    }
}
