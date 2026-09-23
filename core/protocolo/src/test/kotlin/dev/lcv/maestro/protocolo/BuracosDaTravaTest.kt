package dev.lcv.maestro.protocolo

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Um caso por buraco fechado.
 *
 * Vieram das rodadas de revisão automática da PR #39 e das sessões de
 * cross-review de 22/09/2026. A maioria é recusa que a trava deixava passar;
 * há também revisões honestas que ela recusava e passou a aprovar, e um teste
 * de **resíduo aceito** — o que o registro de procedência não fecha, escrito
 * como o que é.
 *
 * A ordem começa pelos que disparam com **prosa comum**, que é o que um agente
 * escreve sem nenhuma má intenção.
 *
 * A prova de que as guardas do registro de procedência são independentes está
 * em [MatrizDeDesarmeTest], executada guarda a guarda. As duas opções do
 * parser foram provadas desligando cada uma: `STRICT_DUPLICATE_DETECTION` derruba
 * quatro testes daqui, `FAIL_ON_TRAILING_TOKENS` derruba um.
 */
class BuracosDaTravaTest {

    private fun exigirViolacao(
        antes: String,
        depois: String,
        relatorio: String,
        trecho: String,
    ) {
        when (val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)) {
            is TravaDeConteudo.Veredito.Violada -> assertContains(veredito.motivo, trecho)
            TravaDeConteudo.Veredito.Aprovada ->
                fail("a trava aprovou uma revisão que devia recusar")
        }
    }

    private fun exigirAprovacao(antes: String, depois: String, relatorio: String) {
        val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)
        if (veredito is TravaDeConteudo.Veredito.Violada) {
            fail("a trava recusou uma revisão legítima: ${veredito.motivo}")
        }
    }

    // -- Disparam com prosa comum ------------------------------------------

    @Test
    fun `a palavra removed numa justificativa nao autoriza reordenar`() {
        // "removed" contém "move". Era a palavra mais comum de um relatório
        // editorial, e autorizava reordenação silenciosa.
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado."
        val depois = "# Titulo\n\nSegundo bloco aprovado.\n\nPrimeiro bloco aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "change_type": "edit",
                  "reason": "removed duplicate punctuation",
                  "protocol_basis": "editorial precision"
                },
                {
                  "block_id": "B0003",
                  "change_type": "edit",
                  "reason": "removed a trailing space",
                  "protocol_basis": "editorial precision"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "must each declare change_type")
    }

    @Test
    fun `negar um acrescimo em prosa nao autoriza acrescimo`() {
        // "no addition was made" contém "addition". A trava lia a negação como
        // autorização.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo aprovado.\n\nParagrafo novo indevido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "change_type": "edit",
                  "reason": "no addition was made to this block",
                  "protocol_basis": "editorial precision"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "added new blocks")
    }

    @Test
    fun `change_type edit nao autoriza acrescimo por causa de palavra na justificativa`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo aprovado.\n\nParagrafo novo indevido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "change_type": "edit",
                  "reason": "considerei a addition de um paragrafo e desisti",
                  "protocol_basis": "editorial precision"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "added new blocks")
    }

    @Test
    fun `o nome protocol_basis dentro de uma justificativa nao conta como base`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "reason": "protocol_basis: nao foi fornecida para este bloco"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "must include protocol_basis")
    }

    @Test
    fun `uma declaracao de split nao autoriza muitos blocos novos`() {
        // Uma entrada com change_type split liberava quantos blocos o agente
        // quisesse, nenhum deles declarado.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo aprovado.\n\nNovo um.\n\nNovo dois.\n\nNovo tres."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "change_type": "split",
                  "protocol_basis": "required expansion"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "added new blocks")
    }

    @Test
    fun `uma autorizacao de crescimento exige a propria base de protocolo`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo aprovado.\n\nParagrafo novo."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "addition"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "must include protocol_basis")
    }

    @Test
    fun `bloco repetido com mudanca fecha a trava em vez de atribuir errado`() {
        // Editar o primeiro de dois paragrafos identicos fazia a trava acusar o
        // segundo: declarar o bloco certo era recusado e declarar o errado era
        // aprovado. Paragrafo repetido e conteudo natural.
        val antes = "Alpha aprovado.\n\nAlpha aprovado."
        val depois = "Alpha reescrito.\n\nAlpha aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "protocol_basis": "approved rewrite"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "identical content")
    }

    @Test
    fun `relatorio embrulhado em prosa e recusado como JSON invalido`() {
        // A versao anterior varria prosa procurando a chave `changed_blocks`, e
        // era nessa varredura que os achados moravam. O contrato nao pede isso:
        // `session_orchestration.rs:2546` recorta a tag e
        // `session_orchestration.rs:2595` entrega o CONTEUDO dela a trava.
        // Relatorio que chega com prosa em volta nao e relatorio: e violacao,
        // e `editorial_prompts.rs:503` ja declara isso.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            Nesta rodada eu revisei o texto. Resumo das mudancas:
            {"changed_blocks": [{"block_id": "B0002", "protocol_basis": "editorial precision"}]}
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `relatorio em YAML e recusado como JSON invalido`() {
        // O caminho YAML era superficie que eu inventei: o prompt canonico
        // pede "JSON-like audit data" (editorial_prompts.rs:487) e 19 de 19
        // fixtures da suite canonica abrem com `{`. Aceitar YAML significava
        // reimplementar uma segunda especificacao a mao, que foi de onde
        // vieram metade dos achados.
        val antes = "# Titulo\n\nParagrafo aprovado.\n\nConclusao aprovada."
        val depois = "# Titulo\n\nParagrafo corrigido.\n\nConclusao aprovada."
        val relatorio = """
            changed_blocks:
            - block_id: B0002
              change_type: edit
              protocol_basis: editorial precision
            custody: revised
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `linha em branco com espacos separa blocos`() {
        // Partir so em "\n\n" colava dois paragrafos num bloco, e declarar
        // aquele bloco passava a autorizar mudanca nos dois.
        val comEspacos = "Primeiro paragrafo.\n   \nSegundo paragrafo."
        val blocos = TravaDeConteudo.segmentarBlocos(comEspacos)

        assertEquals(2, blocos.size, "a linha com espacos deveria separar os blocos")
        assertEquals("Primeiro paragrafo.", blocos[0].texto)
        assertEquals("Segundo paragrafo.", blocos[1].texto)
    }

    @Test
    fun `uma chave solta numa justificativa nao parte a entrada`() {
        // A contagem de profundidade contava a chave dentro da string, cortava
        // a entrada antes do protocol_basis e a declaracao era perdida.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "reason": "o texto citava um } solto de exemplo",
                  "protocol_basis": "editorial precision"
                }
              ],
              "revised_block_origins": [
                {"prefix": "# Titulo", "origin": "B0001"},
                {"prefix": "Paragrafo corrigido.", "origin": "B0002"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    // -- Exigem construção deliberada --------------------------------------

    @Test
    fun `duas declaracoes para o mesmo bloco sao recusadas em vez de somadas`() {
        // As permissoes eram unidas por OR, entao a segunda entrada concedia o
        // que a primeira nao concedia.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "reason": "sem base"},
                {"block_id": "B0002", "protocol_basis": "editorial precision"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "more than once")
    }

    @Test
    fun `dois block_id na mesma entrada sao recusados`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "block_id": "B0003",
                  "protocol_basis": "editorial precision"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `o bloco dez mil pode ser declarado`() {
        // A regex aceitava exatamente quatro digitos, e o segmentador emite
        // B10000 no bloco dez mil: aquele bloco era indeclaravel.
        val muitos = (1..10_000).joinToString("\n\n") { "Paragrafo $it." }
        val blocos = TravaDeConteudo.segmentarBlocos(muitos)
        assertEquals("B10000", blocos.last().id)

        val alterado = (1..10_000).joinToString("\n\n") {
            if (it == 10_000) "Paragrafo $it corrigido." else "Paragrafo $it."
        }
        // Registro com uma entrada por bloco revisado, na ordem do texto.
        val registro = (1..10_000).joinToString(",") { n ->
            val prefixo = if (n == 10_000) "Paragrafo $n corrigido." else "Paragrafo $n."
            "{\"prefix\": \"$prefixo\", \"origin\": \"B" + n.toString().padStart(4, '0') + "\"}"
        }
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B10000", "protocol_basis": "editorial precision"}
              ],
              "revised_block_origins": [$registro],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(muitos, alterado, relatorio)
    }

    // -- Independência de ambiente -----------------------------------------

    private var localeOriginal: Locale? = null

    @BeforeTest
    fun guardarLocale() {
        localeOriginal = Locale.getDefault()
    }

    @AfterTest
    fun restaurarLocale() {
        localeOriginal?.let { Locale.setDefault(it) }
    }

    @Test
    fun `o identificador de bloco nao muda com o idioma do aparelho`() {
        // "B%04d".format(1) usa o locale padrao; num aparelho em ar-EG
        // produzia digitos arabico-indicos, e o identificador que vai ao agente
        // deixava de casar com o que ele devolve.
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        val blocos = TravaDeConteudo.segmentarBlocos("Primeiro.\n\nSegundo.")

        assertEquals("B0001", blocos[0].id)
        assertEquals("B0002", blocos[1].id)
    }

    // -- Achados da rodada 3 do cross-review -------------------------------

    @Test
    fun `campo irmao inventado nao entra na secao de blocos alterados`() {
        // A secao ia do `changed_blocks` ate o proximo terminador CONHECIDO.
        // Um campo que o agente invente nao esta na lista, entao a secao o
        // engolia e a trava lia a declaracao de dentro dele.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "changed_blocks": [],
              "notes": {"block_id": "B0001", "protocol_basis": "editorial precision"}
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "without matching")
    }

    @Test
    fun `aspa simples nao e JSON e o relatorio e recusado`() {
        // O varredor anterior tratava aspa simples como delimitador, o que
        // deu dois achados em rodadas diferentes: apostrofo de prosa abrindo
        // string que nunca fechava, e valor entre aspas simples cortando a
        // secao. Aspa simples nao e JSON. O parser recusa, e a mensagem diz ao
        // agente o que reemitir, em vez de a trava adivinhar.
        val antes = "Alpha aprovado."
        val depois = "Alpha corrigido."
        val relatorio =
            """{"changed_blocks": [{"block_id": "B0001", "protocol_basis": 'editorial precision'}]}"""

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    // -- Achados do Codex sobre a reescrita --------------------------------

    @Test
    fun `campo desconhecido nao entrega autorizacao pelo valor aninhado`() {
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0001",
                  "metadata": {"protocol_basis": "nao fornecida"}
                }
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "must include protocol_basis")
    }

    @Test
    fun `base de protocolo escapada que resolve para espaco nao conta`() {
        // O leitor anterior descartava a barra e guardava a letra: `\u0020`
        // chegava como o texto `u0020` e contava como base preenchida. O
        // parser resolve o escape antes, e o valor real e um espaco, que e
        // vazio. Vale igual para `\n` e para `null` escrito com escapes.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio =
            """{"changed_blocks": [{"block_id": "B0001", "protocol_basis": "\u0020"}]}"""

        exigirViolacao(antes, depois, relatorio, "must include protocol_basis")
    }

    @Test
    fun `base de protocolo feita so de nulos e vazios nao conta`() {
        // Codex, revisao da PR #39: o leitor anterior tirava os colchetes e
        // comparava `null,null` com a lista de marcadores vazios. O valor
        // estruturado agora e percorrido inteiro: sem nenhum escalar com
        // conteudo, nao ha base.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        for (base in listOf("[null, null]", "[\"\", null]", "{\"a\": null, \"b\": [\"\"]}")) {
            val relatorio =
                """{"changed_blocks": [{"block_id": "B0001", "protocol_basis": $base}]}"""

            exigirViolacao(antes, depois, relatorio, "must include protocol_basis")
        }
    }

    @Test
    fun `dois protocol_basis na mesma entrada sao recusados`() {
        // `"protocol_basis":"x","protocol_basis":null` tinha as permissoes
        // UNIDAS por `any`, e a entrada passava com base preenchida — enquanto
        // um leitor de JSON comum escolheria o ultimo valor, que e vazio.
        // Fecha no parser, com STRICT_DUPLICATE_DETECTION, sem logica propria.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "protocol_basis": "x", "protocol_basis": null}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `dois change_type na mesma entrada sao recusados`() {
        // Mesma doenca do fio acima, do lado da autorizacao: declarar
        // `addition` e `edit` juntos dava crescimento autorizado pela uniao.
        val antes = "Alpha aprovado."
        val depois = "Alpha aprovado.${'\n'}${'\n'}Bloco novo."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0001",
                  "change_type": "addition",
                  "change_type": "edit",
                  "protocol_basis": "x"
                }
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `id de bloco inexistente nao autoriza acrescimo`() {
        val antes = "Alpha aprovado."
        val depois = "Alpha aprovado.${'\n'}${'\n'}Bloco novo indevido."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B9999", "change_type": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "added new blocks")
    }

    // -- Movimento de bloco editado, pelo registro de procedencia -----------

    @Test
    fun `bloco sobrevivente que se moveu exige declaracao de reordenacao`() {
        // O que a trava detecta com certeza: movimento entre blocos que
        // sobreviveram sem edicao. A identidade deles vem do hash, nao de
        // adivinhacao, e esta deteccao e exata.
        val quebra = "${'\n'}${'\n'}"
        val antes = "Bloco A aprovado.${quebra}Bloco B aprovado.${quebra}Bloco C aprovado."
        val depois = "Bloco B aprovado.${quebra}Bloco A aprovado.${quebra}Bloco C aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "edit", "protocol_basis": "editorial"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "reorder")
    }

    @Test
    fun `bloco editado e movido e detectado pelo registro de procedencia`() {
        // Ate 22/09/2026 isto era um limite aceito por escrito: um bloco editado
        // some de um lado e volta do outro com hash novo, e os dois textos nao
        // dizem se ele moveu. Tres heuristicas de pareamento foram derrubadas
        // em tres rodadas de revisao. O registro de procedencia diz de onde
        // cada bloco revisado vem, e com ele o movimento de A aparece.
        val quebra = "${'\n'}${'\n'}"
        val antes = "Bloco A aprovado.${quebra}Bloco B aprovado.${quebra}Bloco C aprovado."
        val depois = "Bloco B aprovado.${quebra}Bloco A editado.${quebra}Bloco C aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "edit", "protocol_basis": "editorial"}
              ],
              "revised_block_origins": [
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco A editado.", "origin": "B0001"},
                {"prefix": "Bloco C aprovado.", "origin": "B0003"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "reorder")
    }

    @Test
    fun `edicao no lugar com acrescimo passa com registro honesto`() {
        // O falso positivo que fez a outra regra ser recusada: editar um bloco
        // no lugar e acrescentar outro e revisao legitima e comum. Com o
        // registro, a trava sabe que nada moveu, e nao exige reordenacao que
        // seria mentira.
        val quebra = "${'\n'}${'\n'}"
        val antes = "Bloco A aprovado.${quebra}Bloco B aprovado.${quebra}Bloco C aprovado."
        val depois = "Bloco A editado.${quebra}Bloco B aprovado.${quebra}" +
            "Bloco C aprovado.${quebra}Bloco D novo."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["edit", "addition"], "protocol_basis": "editorial"}
              ],
              "revised_block_origins": [
                {"prefix": "Bloco A editado.", "origin": "B0001"},
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco C aprovado.", "origin": "B0003"},
                {"prefix": "Bloco D novo.", "origin": "addition", "protocol_basis": "contexto"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `RESIDUO um registro mentiroso passa, e a mentira fica registrada`() {
        // O que o registro NAO faz, por escrito: descobrir linhagem. A revisao
        // abaixo acrescenta D e move A editado. O registro honesto seria
        // [addition, B0002, B0001, B0003] e exigiria reordenacao. Este registro
        // mente: diz que D continua A e que A editado e acrescimo. A trava
        // aprova, porque os dois textos sao compativeis com essa historia.
        //
        // O ganho sobre o limite anterior: o movimento deixou de ser silencioso.
        // Para esconde-lo, o agente precisou AFIRMAR duas coisas falsas, cada
        // uma com justificativa propria e atribuivel — a edicao de B0001 em
        // `changed_blocks` e a base do acrescimo na entrada do registro.
        val quebra = "${'\n'}${'\n'}"
        val antes = "Bloco A aprovado.${quebra}Bloco B aprovado.${quebra}Bloco C aprovado."
        val depois = "Bloco D novo.${quebra}Bloco B aprovado.${quebra}" +
            "Bloco A editado.${quebra}Bloco C aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["edit", "addition"], "protocol_basis": "editorial"}
              ],
              "revised_block_origins": [
                {"prefix": "Bloco D novo.", "origin": "B0001"},
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco A editado.", "origin": "addition", "protocol_basis": "contexto"},
                {"prefix": "Bloco C aprovado.", "origin": "B0003"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    // -- Achados do Codex sobre a reescrita, continuação ------------------

    @Test
    fun `change_type em lista JSON e aceito com as aspas dos itens`() {
        val antes = "Alpha aprovado."
        val depois = "Alpha aprovado.${'\n'}${'\n'}Bloco novo declarado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["addition"], "protocol_basis": "contexto"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "B0001"},
                {"prefix": "Bloco novo declarado.", "origin": "addition", "protocol_basis": "contexto"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `blocos identicos todos reescritos nao sao ambiguos`() {
        // Se NENHUMA copia sobrevive, cada id e atribuivel sem duvida. Fechar
        // aqui recusaria uma revisao que declarou tudo certo.
        val quebra = "${'\n'}${'\n'}"
        val antes = "Alpha aprovado.${quebra}Alpha aprovado."
        val depois = "Primeiro reescrito.${quebra}Segundo reescrito."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "protocol_basis": "reescrita aprovada"},
                {"block_id": "B0002", "protocol_basis": "reescrita aprovada"}
              ],
              "revised_block_origins": [
                {"prefix": "Primeiro reescrito.", "origin": "B0001"},
                {"prefix": "Segundo reescrito.", "origin": "B0002"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    // -- Achados do Codex sobre os proprios consertos ----------------------

    @Test
    fun `movimento de sobrevivente e detectado mesmo com acrescimo junto`() {
        // Acrescimo na mesma revisao NAO desliga a deteccao entre os blocos
        // que sobreviveram: a identidade deles vem do hash. Foi aqui que a
        // regra de contagens iguais falhava, e este caso continua fechado.
        val q = "${'\n'}${'\n'}"
        val antes = "Bloco A aprovado.${q}Bloco B aprovado.${q}Bloco C aprovado."
        val depois = "Bloco B aprovado.${q}Bloco A aprovado.${q}Bloco C aprovado.${q}Bloco D novo."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "addition", "protocol_basis": "contexto"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "reorder")
    }

    @Test
    fun `lixo depois do primeiro valor JSON e recusado`() {
        // `readTree` casa o primeiro valor e ignora o resto por padrao, entao
        // um segundo documento colado depois sumia sem aviso — e quem decidia
        // voltava a ser a ordem no texto. Achado do cross-review de 22/09.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio =
            """{"changed_blocks": [{"block_id": "B0001", "protocol_basis": "x"}]} {"changed_blocks": []}"""

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }

    @Test
    fun `duas entradas para o mesmo bloco sao recusadas`() {
        // STRICT_DUPLICATE_DETECTION cobre campo repetido DENTRO de um objeto.
        // Nao cobre dois objetos da lista declarando o mesmo bloco: essa e
        // conferencia da trava, e ela fecha em vez de unir as permissoes.
        val antes = "Alpha aprovado."
        val depois = "Alpha aprovado.${'\n'}${'\n'}Bloco novo."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"},
                {"block_id": "B0001", "change_type": "edit", "protocol_basis": "y"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "more than once")
    }

    @Test
    fun `secao changed_blocks que nao e lista e relatorio malformado`() {
        // O contrato descreve `changed_blocks` como lista de declaracoes
        // (editorial_prompts.rs:491). Objeto ou escalar no lugar dela e
        // relatorio malformado, e adivinhar o que o agente quis dizer e
        // exatamente a doenca que esta reescrita cura.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """{"changed_blocks": {"block_id": "B0001", "protocol_basis": "x"}}"""

        exigirViolacao(antes, depois, relatorio, "must be a JSON array")
    }

    @Test
    fun `duas secoes que so diferem na caixa sao recusadas`() {
        // Para o JSON, `changed_blocks` e `Changed_Blocks` sao campos
        // distintos, entao o parser nao os barra como chave duplicada. O
        // canonico rebaixa o relatorio inteiro antes de procurar a chave, o
        // que faria as duas valerem pela mesma secao — e dai quem decidiria
        // seria a ordem no documento.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "Changed_Blocks": [{"block_id": "B0001", "protocol_basis": "x"}],
              "changed_blocks": []
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "more than one changed_blocks")
    }

    @Test
    fun `copia identica acrescentada nao e ambiguidade`() {
        val q = "${'\n'}${'\n'}"
        val antes = "Alpha aprovado.${q}Alpha aprovado."
        val depois = "Alpha aprovado.${q}Alpha aprovado.${q}Alpha aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "addition", "protocol_basis": "repeticao pedida"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "B0001"},
                {"prefix": "Alpha aprovado.", "origin": "B0002"},
                {"prefix": "Alpha aprovado.", "origin": "addition", "protocol_basis": "repeticao pedida"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `secao aninhada em metadata nao autoriza nada`() {
        // Antes, a secao era achada varrendo o documento inteiro pela chave, e
        // a ocorrencia aninhada dentro de `metadata` vinha primeiro: ela
        // autorizava a reescrita e a secao real, vazia, era ignorada. Agora a
        // secao e lida do NIVEL DE TOPO, e a aninhada deixa de ser alcancavel
        // por construcao. B0001 fica sem declaracao, que e a verdade.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "metadata": {"changed_blocks": [{"block_id": "B0001", "protocol_basis": "x"}]},
              "changed_blocks": []
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "without matching")
    }

    @Test
    fun `prefixo recheado de espacos nao conta como 20 caracteres`() {
        // O minimo do prefixo sai do bloco. Contar o prefixo cru aceitaria 20
        // caracteres que, normalizados, sao "Alpha beta" — dez de texto.
        val texto = "Alpha beta gamma delta epsilon."
        val relatorio = """
            {
              "changed_blocks": [],
              "revised_block_origins": [{"prefix": "Alpha           beta", "origin": "B0001"}]
            }
        """.trimIndent()

        exigirViolacao(texto, texto, relatorio, "prefix must copy at least 20 characters")
    }

    @Test
    fun `copia identica que se move exige a propria reordenacao`() {
        // Codex, rodada 5: as duas pontas que se movem continuam exigidas.
        // Tirar a declaracao de qualquer uma delas recusa.
        val q = "${'\n'}${'\n'}"
        val antes = "Echo.${q}Alpha.${q}Echo.${q}Bravo."
        val depois = "Bravo.${q}Alpha.${q}Echo.${q}Echo."
        val registro = """
              "revised_block_origins": [
                {"prefix": "Bravo.", "origin": "B0004"},
                {"prefix": "Alpha.", "origin": "B0002"},
                {"prefix": "Echo.", "origin": "B0003"},
                {"prefix": "Echo.", "origin": "B0001"}
              ]
        """
        val semB0001 = """
            {
              "changed_blocks": [{"block_id": "B0004", "change_type": "reorder", "protocol_basis": "x"}],
              $registro
            }
        """.trimIndent()
        val semB0004 = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"}],
              $registro
            }
        """.trimIndent()

        exigirViolacao(antes, depois, semB0001, "reordered received blocks B0001 must")
        exigirViolacao(antes, depois, semB0004, "reordered received blocks B0004 must")
    }

    @Test
    fun `addition nao autoriza um recebido a continuar em dois blocos`() {
        // Codex, rodada 5: `addition` autoriza crescer, mas declara bloco novo.
        // Um recebido que continua em dois blocos declara `split`, como a
        // instrucao manda.
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Left.", "origin": "B0001"},
                {"prefix": "Right.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao("Alpha.", "Left.${'\n'}${'\n'}Right.", relatorio, "continue in more than one revised block")
    }

    @Test
    fun `RESIDUO um bloco novo rotulado de pedaco passa com a propria base`() {
        // DeepSeek, rodada 5: X e conteudo novo, mas o registro diz que e o
        // terceiro pedaco de Alpha. A trava nao descobre linhagem: aprova. O
        // rotulo custa a X uma base de protocolo propria, atribuivel, e o split
        // de B0001 com base — a mesma classe do registro mentiroso. Contar uma
        // entrada de crescimento por bloco nao fecharia nada: o agente poria
        // mais uma, com mais uma base. O canonico aceita qualquer uma
        // (`editorial_content_lock.rs`: `.any(|declaration|
        // declaration.allows_block_count_growth)`).
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha part one.", "origin": "B0001"},
                {"prefix": "Alpha part two.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "New unapproved block.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(
            "Alpha.${q}Beta.",
            "Alpha part one.${q}Alpha part two.${q}New unapproved block.${q}Beta.",
            relatorio,
        )
    }

    @Test
    fun `copia de outro recebido nao passa por pedaco`() {
        // A / B -> A / B / A, com o segundo A rotulado de pedaco de B. Pelo
        // texto, e copia a mais de B0001, e copia a mais e acrescimo. Fecha o
        // resíduo que a identidade so pelo registro abria.
        val q = "${'\n'}${'\n'}"
        val comoPedaco = """
            {
              "changed_blocks": [{"block_id": "B0002", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "Beta.", "origin": "B0002"},
                {"prefix": "Alpha.", "origin": "B0002", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()
        val comoAcrescimo = """
            {
              "changed_blocks": [{"block_id": "B0002", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "Beta.", "origin": "B0002"},
                {"prefix": "Alpha.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao("Alpha.${q}Beta.", "Alpha.${q}Beta.${q}Alpha.", comoPedaco, "extra copies are additions")
        exigirAprovacao("Alpha.${q}Beta.", "Alpha.${q}Beta.${q}Alpha.", comoAcrescimo)
    }

    @Test
    fun `copia identica editada com registro ainda exige a declaracao da editada`() {
        // Codex, rodada 6: o registro resolve qual copia mudou, e a que mudou
        // continua exigindo a propria declaracao.
        val relatorio = """
            {
              "changed_blocks": [],
              "revised_block_origins": [
                {"prefix": "Echo revised.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0002"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Echo.${'\n'}${'\n'}Echo.",
            "Echo revised.${'\n'}${'\n'}Echo.",
            relatorio,
            "changed received blocks B0001 without matching",
        )
    }

    @Test
    fun `copia identica editada sem registro fecha e pede o registro`() {
        // Sem registro, a atribuicao continua fechando, e a mensagem manda o
        // que resolve: o registro. Declarar os dois candidatos nao resolvia.
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "edit", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "edit", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Echo.${'\n'}${'\n'}Echo.",
            "Echo revised.${'\n'}${'\n'}Echo.",
            relatorio,
            "declare revised_block_origins naming the received block of every revised block",
        )
    }

    @Test
    fun `nomear copias identicas paradas em ordem trocada declara movimento`() {
        // DeepSeek, rodada 2: as copias ficaram paradas, mas o registro diz que
        // trocaram. A instrucao diz que a nomeacao e o relato do agente; o relato
        // declara movimento, e movimento sem reorder e recusado.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0003", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Paragrafo repetido.", "origin": "B0002"},
                {"prefix": "Paragrafo repetido.", "origin": "B0001"},
                {"prefix": "Conclusao reescrita.", "origin": "B0003"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Paragrafo repetido.${q}Paragrafo repetido.${q}Conclusao aprovada.",
            "Paragrafo repetido.${q}Paragrafo repetido.${q}Conclusao reescrita.",
            relatorio,
            "reordered received blocks B0001, B0002 must",
        )
    }

    @Test
    fun `a ordem e conferida contra a nomeacao do registro e nao contra outra`() {
        // O agente nomeia as copias de Echo na ordem do texto; no relato dele,
        // B0001 foi do primeiro para o terceiro lugar. Declarar so B0003 e B0004
        // (o que seria o menor movimento) nao cobre o relato que ele deu.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0003", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0004", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Bravo.", "origin": "B0004"},
                {"prefix": "Alpha.", "origin": "B0002"},
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0003"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Echo.${q}Alpha.${q}Echo.${q}Bravo.",
            "Bravo.${q}Alpha.${q}Echo.${q}Echo.",
            relatorio,
            "reordered received blocks B0001 must",
        )
    }

    @Test
    fun `composicao com um so grupo de copias segue a nomeacao do registro`() {
        // Codex, rodada 7: a mesma composicao, com Foxtrot trocado por Echo, pondo
        // as duas interacoes num grupo so de quatro copias identicas.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "split", "protocol_basis": "x"},
                {"block_id": "B0003", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0004", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0006", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0007", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0003"},
                {"prefix": "Alpha one.", "origin": "B0002"},
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Alpha two.", "origin": "B0002", "protocol_basis": "x"},
                {"prefix": "Bravo.", "origin": "B0007"},
                {"prefix": "Charlie.", "origin": "B0005"},
                {"prefix": "Echo.", "origin": "B0004"},
                {"prefix": "Echo.", "origin": "B0006"}
              ]
            }
        """.trimIndent()

        exigirAprovacao(
            "Echo.${q}Alpha.${q}Echo.${q}Echo.${q}Charlie.${q}Echo.${q}Bravo.",
            "Echo.${q}Alpha one.${q}Echo.${q}Alpha two.${q}Bravo.${q}Charlie.${q}Echo.${q}Echo.",
            relatorio,
        )
    }

    @Test
    fun `acrescimo declarado sob ID que so existe no texto revisado e recusado`() {
        // DeepSeek, rodada 7: a instrucao pede o ID de um bloco recebido. B0003
        // so existe no texto revisado.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0003", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "New block.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Alpha.${q}Beta.",
            "Alpha.${q}New block.${q}Beta.",
            relatorio,
            "so a changed_blocks entry must declare change_type addition",
        )
    }

    @Test
    fun `copia a mais nomeada como pedaco de divisao e recusada`() {
        // Codex, rodada 8: o recebido ja tem a sua copia intacta; a segunda copia
        // exata e copia a mais, e copia a mais e acrescimo. Nomea-la como pedaco
        // de B0001 fazia passar por divisao um bloco que continua inteiro.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao("Echo.", "Echo.${q}Echo.", relatorio, "is an extra unchanged copy")
    }

    @Test
    fun `copia a mais declarada como acrescimo passa`() {
        // O mesmo texto, com o relato que a instrucao pede.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirAprovacao("Echo.", "Echo.${q}Echo.", relatorio)
    }

    @Test
    fun `entrada de acrescimo sem base e recusada mesmo sem crescimento`() {
        // Codex, rodada 8: o acrescimo substitui uma remocao e o texto nao
        // cresce no saldo. A entrada que declara o acrescimo continua exigindo a
        // propria base.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "addition"},
                {"block_id": "B0002", "change_type": "delete", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "New.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao(
            "Alpha.${q}Beta.",
            "Alpha.${q}New.",
            relatorio,
            "B0001 declare change_type split/addition and must include protocol_basis",
        )
    }

    @Test
    fun `entrada de acrescimo com base passa sem crescimento`() {
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "delete", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "New.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirAprovacao("Alpha.${q}Beta.", "Alpha.${q}New.", relatorio)
    }

    @Test
    fun `bloco que repete um texto ja nomeado e acrescimo, nao reescrita`() {
        // DeepSeek, rodada 10, sob a regra do texto: Alpha./Beta. -> Beta./Beta.
        // O segundo Beta e a copia intacta de B0002; o primeiro repete um texto
        // cujos recebidos ja tem a sua copia. Pelo texto, "reescrita de B0001
        // que ficou igual a Beta" e "copia de Beta" nao se distinguem, e a
        // copia a mais e acrescimo, como a instrucao diz.
        val q = "${'\n'}${'\n'}"
        val comoReescrita = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "edit", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Beta.", "origin": "B0001"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
            }
        """.trimIndent()
        val comoAcrescimo = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["delete", "addition"], "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Beta.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
            }
        """.trimIndent()

        exigirViolacao("Alpha.${q}Beta.", "Beta.${q}Beta.", comoReescrita, "extra copies are additions")
        exigirAprovacao("Alpha.${q}Beta.", "Beta.${q}Beta.", comoAcrescimo)
    }

    @Test
    fun `os dois blocos que trocam de lugar declaram reorder, como no canonico`() {
        // Grok, rodada 10: em A/B/C -> D/B/A'/C, B0001 e B0002 trocaram de lugar
        // entre os recebidos mantidos. A regra e a do canonico
        // (reorder_declaration_must_name_moved_received_blocks exige os dois), e a
        // instrucao diz isso ao agente. Sem B0002, recusa; com os dois, aprova.
        val q = "${'\n'}${'\n'}"
        val registro = """
              "revised_block_origins": [
                {"prefix": "Bloco D novo.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco A editado.", "origin": "B0001"},
                {"prefix": "Bloco C aprovado.", "origin": "B0003"}
              ]
        """
        val soB0001 = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["edit", "reorder", "addition"], "protocol_basis": "x"}
              ],
              $registro
            }
        """.trimIndent()
        val osDois = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["edit", "reorder", "addition"], "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "reorder", "protocol_basis": "x"}
              ],
              $registro
            }
        """.trimIndent()
        val antes = "Bloco A aprovado.${q}Bloco B aprovado.${q}Bloco C aprovado."
        val depois = "Bloco D novo.${q}Bloco B aprovado.${q}Bloco A editado.${q}Bloco C aprovado."

        exigirViolacao(antes, depois, soB0001, "reordered received blocks B0002 must")
        exigirAprovacao(antes, depois, osDois)
    }

    @Test
    fun `a posicao conta so entre os recebidos mantidos, como no canonico`() {
        // Codex e Grok, rodada 11: o bloco excluido sai das duas listas
        // (common_block_id_sequence no canonico). Em A/B/C -> C/B, B continua
        // segundo no texto recebido, mas entre os mantidos troca de lugar com C.
        val q = "${'\n'}${'\n'}"
        val registro = """
              "revised_block_origins": [
                {"prefix": "Gamma.", "origin": "B0003"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
        """
        fun relatorio(entradas: String) = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"},
                $entradas
              ],
              $registro
            }
        """.trimIndent()
        val antes = "Alpha.${q}Beta.${q}Gamma."
        val depois = "Gamma.${q}Beta."
        val soC = """{"block_id": "B0003", "change_type": "reorder", "protocol_basis": "x"}"""
        val bEC = """$soC, {"block_id": "B0002", "change_type": "reorder", "protocol_basis": "x"}"""

        exigirViolacao(antes, depois, relatorio(soC), "reordered received blocks B0002 must")
        exigirAprovacao(antes, depois, relatorio(bEC))
    }

    @Test
    fun `rotacao depois de uma exclusao move os tres mantidos`() {
        // Grok, rodada 11: A/B/C/D -> D/A/C. Entre os mantidos, B0001, B0003 e
        // B0004 vao de [1, 3, 4] a [4, 1, 3]: os tres mudam de posicao, embora
        // C continue terceiro no texto recebido.
        val q = "${'\n'}${'\n'}"
        val registro = """
              "revised_block_origins": [
                {"prefix": "Delta.", "origin": "B0004"},
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "Gamma.", "origin": "B0003"}
              ]
        """
        fun relatorio(ids: List<String>) = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "delete", "protocol_basis": "x"},
                ${ids.joinToString(", ") { """{"block_id": "$it", "change_type": "reorder", "protocol_basis": "x"}""" }}
              ],
              $registro
            }
        """.trimIndent()
        val antes = "Alpha.${q}Beta.${q}Gamma.${q}Delta."
        val depois = "Delta.${q}Alpha.${q}Gamma."

        exigirViolacao(antes, depois, relatorio(listOf("B0001", "B0004")), "reordered received blocks B0003 must")
        exigirAprovacao(antes, depois, relatorio(listOf("B0001", "B0003", "B0004")))
    }

    @Test
    fun `exclusao sozinha nao move ninguem`() {
        // Controle da rodada 11: A/B/C -> B/C so declara a exclusao.
        val q = "${'\n'}${'\n'}"
        val relatorio = """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Beta.", "origin": "B0002"},
                {"prefix": "Gamma.", "origin": "B0003"}
              ]
            }
        """.trimIndent()

        exigirAprovacao("Alpha.${q}Beta.${q}Gamma.", "Beta.${q}Gamma.", relatorio)
    }

    @Test
    fun `bloco igual a um recebido livre tem de nomea-lo`() {
        // DeepSeek, rodada 13, sob a regra do texto: A/B/A -> A/A. O primeiro
        // bloco tem o texto de B0001, que nenhuma copia nomeia: e copia de
        // B0001, e nao reescrita de B0002. A descricao que passa e a canonica:
        // B0001 e B0003 intactos, B0002 excluido.
        val q = "${'\n'}${'\n'}"
        val comoReescrita = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "edit", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0002"},
                {"prefix": "Alpha.", "origin": "B0003"}
              ]
            }
        """.trimIndent()
        val canonica = """
            {
              "changed_blocks": [{"block_id": "B0002", "change_type": "delete", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "Alpha.", "origin": "B0003"}
              ]
            }
        """.trimIndent()
        val antes = "Alpha.${q}Beta.${q}Alpha."
        val depois = "Alpha.${q}Alpha."

        exigirViolacao(antes, depois, comoReescrita, "is an unchanged copy of received block B0001")
        exigirAprovacao(antes, depois, canonica)
    }

    @Test
    fun `tres copias de um texto recebido duas vezes contam pelo texto`() {
        // DeepSeek, rodada 14: A/B/A -> A/A/A. Pelo texto, dois dos tres A sao
        // B0001 e B0003, e o terceiro e acrescimo; B0002 foi excluido. Chamar o
        // primeiro A de reescrita de B0002 contraria a regra do texto enquanto
        // B0001 estiver livre.
        val q = "${'\n'}${'\n'}"
        val comoReescrita = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "edit", "protocol_basis": "x"},
                {"block_id": "B0003", "change_type": "addition", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0002", "protocol_basis": "x"},
                {"prefix": "Alpha.", "origin": "B0003"},
                {"prefix": "Alpha.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()
        val canonica = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "delete", "protocol_basis": "x"},
                {"block_id": "B0003", "change_type": "addition", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "Alpha.", "origin": "B0003"},
                {"prefix": "Alpha.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()
        val antes = "Alpha.${q}Beta.${q}Alpha."
        val depois = "Alpha.${q}Alpha.${q}Alpha."

        exigirViolacao(antes, depois, comoReescrita, "is an unchanged copy of received block B0001")
        exigirAprovacao(antes, depois, canonica)
    }

    @Test
    fun `troca de lugar nao se esconde como duas edicoes`() {
        // A/B -> B/A, as duas copias intactas. Chamar cada uma de reescrita da
        // outra escondia o movimento com duas edicoes declaradas. Pelo texto,
        // o primeiro bloco e copia de B0002, e a troca exige reorder dos dois.
        val q = "${'\n'}${'\n'}"
        val comoEdicoes = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "edit", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "edit", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Beta.", "origin": "B0001"},
                {"prefix": "Alpha.", "origin": "B0002"}
              ]
            }
        """.trimIndent()
        val comoTroca = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Beta.", "origin": "B0002"},
                {"prefix": "Alpha.", "origin": "B0001"}
              ]
            }
        """.trimIndent()

        exigirViolacao("Alpha.${q}Beta.", "Beta.${q}Alpha.", comoEdicoes, "is an unchanged copy of received block B0002")
        exigirAprovacao("Alpha.${q}Beta.", "Beta.${q}Alpha.", comoTroca)
    }

    @Test
    fun `dois campos block_id sao recusados mesmo com um invalido`() {
        // `mapNotNull { idDeBloco(it) }.distinct()` rodava antes da contagem, e
        // a entrada passava sempre que so um valor sobrasse valido — enquanto
        // um leitor de JSON comum escolheria o ULTIMO, que e o invalido. Fecha
        // no parser.
        val antes = "Alpha aprovado."
        val depois = "Alpha reescrito."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0001", "block_id": "invalido", "protocol_basis": "x"}
              ]
            }
        """.trimIndent()

        exigirViolacao(antes, depois, relatorio, "is not valid JSON")
    }
}
