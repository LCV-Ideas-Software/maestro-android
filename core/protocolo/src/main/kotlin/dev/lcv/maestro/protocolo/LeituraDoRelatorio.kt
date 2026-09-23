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
 *
 * ## O que este arquivo faz agora
 *
 * O relatório é JSON e é lido com parser de verdade, com **detecção estrita de
 * chave duplicada ligada** — `StreamReadFeature.STRICT_DUPLICATE_DETECTION`,
 * que a FasterXML documenta como desligada por padrão e que lança
 * `JsonParseException` quando um objeto repete um nome de campo.
 *
 * Relatório que não parseia é **violação de contrato**, não motivo para cair
 * num caminho tolerante.
 *
 * ## A forma, igual à do canônico v00.05.65
 *
 * Desde a `maestro-app#395` o canônico lê o relatório com `serde` tipado, e
 * este leitor segue a mesma forma (MAEANDR-17):
 *
 * - o relatório é **um objeto JSON**, sempre; relatório vazio também é recusado;
 * - os nomes de campo são **exatos**: `Changed_Blocks` não é `changed_blocks`,
 *   é só um campo que ninguém lê;
 * - cada entrada de `changed_blocks` é um objeto com `block_id` em texto, no
 *   formato exato `B` seguido de quatro ou mais dígitos;
 * - `change_type` é um texto ou uma lista não vazia de textos distintos, e só
 *   os tokens exatos `addition`, `split` e `reorder` autorizam alguma coisa;
 * - `protocol_basis` só conta quando tem texto não vazio em alguma folha;
 *   número e booleano não são justificativa;
 * - `block_id` repetido entre entradas é recusado.
 *
 * Que o `block_id` exista no manifesto recebido, quem confere é a trava, que
 * conhece os blocos ([TravaDeConteudo]).
 *
 * **O que não se adota do canônico, e por quê.** `new_block_count` e a regra
 * de fonte local do crescimento existem lá porque o canônico não sabe de onde
 * veio um bloco a mais. Aqui o registro de procedência declara cada bloco
 * revisado, e cada acréscimo leva a própria `protocol_basis`; `new_block_count`
 * é ignorado. O resíduo — a declaração pode mentir — é o mesmo aceito na
 * Discussion #41.
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

    /** Os únicos tokens de `change_type` que autorizam algo, como no canônico. */
    private const val ACRESCIMO = "addition"
    private const val DIVISAO = "split"
    private const val REORDENACAO = "reorder"

    /**
     * Nome da seção. O canônico antigo também aceitava `changes`, mas desde a
     * v00.05.65 `changes` é outra lista: a dos trechos alterados, que
     * [TurnoSerial] lê.
     */
    private const val NOME_DA_SECAO = "changed_blocks"

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
         * Duas entradas declaram o mesmo bloco. Num portão de integridade isso
         * **não** se resolve escolhendo uma nem somando as duas.
         */
        data class Ambigua(val motivo: String) : Leitura

        /**
         * O relatório não tem a forma do contrato. Distinto de [Ambigua]
         * porque a correção que o agente precisa fazer é outra: reemitir o
         * relatório, não desfazer uma declaração conflitante.
         */
        data class Invalida(val motivo: String) : Leitura
    }

    private const val OBJETO_ESTRITO = "maestro_revision_report must be one strict JSON object"

    fun ler(relatorio: String): Leitura {
        val raiz = try {
            LEITOR.readTree(relatorio)
        } catch (erro: JacksonException) {
            return Leitura.Invalida("$OBJETO_ESTRITO: ${primeiraLinha(erro)}")
        }
        // Vazio, `null` ou outra coisa que não objeto: o canônico recusa em
        // todo turno, até no que não muda nada.
        if (raiz == null || !raiz.isObject) return Leitura.Invalida(OBJETO_ESTRITO)

        val procedencias = when (val lido = lerRegistro(raiz.get(NOME_DO_REGISTRO))) {
            is Registro.Falha -> return lido.leitura
            is Registro.Lido -> lido.itens
        }

        // Sem `changed_blocks` mas com registro: lê-se como seção vazia, e a
        // trava decide. Toda mudança que exige declaração continua exigindo a
        // entrada em `changed_blocks`.
        val entradas = raiz.get(NOME_DA_SECAO)
            ?: return if (procedencias == null) {
                Leitura.SemSecao
            } else {
                Leitura.Entradas(emptyMap(), procedencias)
            }

        // Objeto, escalar ou `null` no lugar da lista é relatório malformado.
        // O `#[serde(default)]` do canônico cobre o campo ausente, não o nulo.
        if (!entradas.isArray) {
            return Leitura.Invalida("maestro_revision_report $NOME_DA_SECAO must be a JSON array of declarations")
        }

        val porBloco = linkedMapOf<String, Entrada>()
        for ((indice, entrada) in entradas.withIndex()) {
            val numero = indice + 1
            if (!entrada.isObject) {
                return Leitura.Invalida("$NOME_DA_SECAO entry $numero must be a JSON object")
            }
            val bruto = entrada.get("block_id")
            if (bruto == null || !bruto.isTextual) {
                return Leitura.Invalida("$NOME_DA_SECAO entry $numero must declare block_id as a string")
            }
            val id = bruto.textValue()
            if (!ehIdDeBloco(id)) {
                return Leitura.Invalida("invalid $NOME_DA_SECAO block_id $id")
            }
            if (porBloco.containsKey(id)) {
                return Leitura.Ambigua("duplicate $NOME_DA_SECAO declaration for $id")
            }
            val tipos = when (val lido = lerTipos(id, entrada.get("change_type"))) {
                is Tipos.Falha -> return lido.leitura
                is Tipos.Lidos -> lido.tokens
            }
            porBloco[id] = Entrada(
                id = id,
                temBaseDeProtocolo = temConteudo(entrada.get("protocol_basis")),
                permiteCrescimento = DIVISAO in tipos || ACRESCIMO in tipos,
                permiteDivisao = DIVISAO in tipos,
                permiteAcrescimo = ACRESCIMO in tipos,
                permiteReordenacao = REORDENACAO in tipos,
            )
        }
        return Leitura.Entradas(porBloco, procedencias)
    }

    // -- change_type ---------------------------------------------------------

    private sealed interface Tipos {
        data class Lidos(val tokens: Set<String>) : Tipos
        data class Falha(val leitura: Leitura) : Tipos
    }

    /**
     * `change_type`: ausente ou nulo não autoriza nada; texto é um token; lista
     * é não vazia e de textos distintos. Os tokens são comparados **inteiros e
     * exatos** — sem sinônimo, sem caixa, sem dividir `"edit, reorder"` —,
     * como no canônico: `removed` contém `move`, e sinônimo é o caminho por
     * onde a prosa volta a autorizar.
     */
    private fun lerTipos(id: String, no: JsonNode?): Tipos {
        if (no == null || no.isNull) return Tipos.Lidos(emptySet())
        if (no.isTextual) return Tipos.Lidos(setOf(no.textValue()))
        val formato = Tipos.Falha(
            Leitura.Invalida("change_type for $id must be a string or a list of strings"),
        )
        if (!no.isArray) return formato
        if (no.isEmpty) return Tipos.Falha(Leitura.Invalida("empty change_type for $id"))
        val tokens = linkedSetOf<String>()
        for (item in no) {
            if (!item.isTextual) return formato
            if (!tokens.add(item.textValue())) {
                return Tipos.Falha(Leitura.Invalida("duplicate change_type for $id"))
            }
        }
        return Tipos.Lidos(tokens)
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
    private fun lerRegistro(lista: JsonNode?): Registro {
        if (lista == null) return Registro.Lido(null)
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
            // Exato, como o `block_id` de `changed_blocks`: o agente copia o
            // identificador do manifesto, não o reescreve.
            val textoDaOrigem = origem.textValue()
            val id = when {
                textoDaOrigem == ORIGEM_ACRESCIMO -> null
                ehIdDeBloco(textoDaOrigem) -> textoDaOrigem
                else -> return Registro.Falha(
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

    // -- Campos ------------------------------------------------------------

    /**
     * Identificador de bloco, com **quatro ou mais** dígitos ASCII: o
     * segmentador emite `B10000` no bloco dez mil, e aceitar só quatro dígitos
     * tornaria aquele bloco impossível de declarar.
     */
    private val ID_DE_BLOCO = Regex("""^B[0-9]{4,}$""")

    private fun ehIdDeBloco(valor: String): Boolean = ID_DE_BLOCO.matches(valor)

    /**
     * Um valor conta como justificativa quando tem **texto** de verdade em
     * alguma folha, como no `has_substantive_protocol_basis` do canônico.
     * Número e booleano não são justificativa.
     *
     * Além do canônico, `none`, `n/a`, `na`, `null` e `-` também contam como
     * vazios: são declaração vazia com aparência de declaração.
     *
     * Escape já vem resolvido pelo parser: `" "` chega como um espaço e
     * cai no vazio.
     */
    private fun temConteudo(no: JsonNode?): Boolean {
        if (no == null) return false
        if (no.isContainerNode) return no.any { temConteudo(it) }
        if (!no.isTextual) return false
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
