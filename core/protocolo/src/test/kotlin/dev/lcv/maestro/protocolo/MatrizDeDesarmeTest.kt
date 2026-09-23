package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.fail

/**
 * Matriz de desarme do registro de procedência.
 *
 * Cada `testemunha` é recusada por **uma guarda só**, e exige a mensagem
 * daquela guarda — para não passar por um motivo errado. A prova de que ela
 * é independente não está aqui: está na execução com a guarda desligada,
 * em que **só** a testemunha dela deve cair, e cair porque a trava aprovou.
 *
 * Cada `controle` é uma revisão honesta que a trava tem de aprovar. Foram os
 * contraexemplos de recusa indevida dados pelos revisores; o autor de cada um
 * está citado no teste.
 */
class MatrizDeDesarmeTest {

    private fun recusa(antes: String, depois: String, relatorio: String, trecho: String) {
        when (val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)) {
            is TravaDeConteudo.Veredito.Violada -> assertContains(veredito.motivo, trecho)
            TravaDeConteudo.Veredito.Aprovada ->
                fail("a trava aprovou uma revisão que devia recusar")
        }
    }

    private fun aprova(antes: String, depois: String, relatorio: String) {
        val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)
        if (veredito is TravaDeConteudo.Veredito.Violada) {
            fail("a trava recusou uma revisão legítima: ${veredito.motivo}")
        }
    }

    private val q = "${'\n'}${'\n'}"

    // -- Testemunhas: uma por guarda ---------------------------------------

    @Test
    fun `testemunha registro-exigido`() {
        recusa(
            "Alpha aprovado.",
            "Alpha reescrito.",
            """{"changed_blocks": [{"block_id": "B0001", "protocol_basis": "editorial"}]}""",
            "no revised_block_origins section",
        )
    }

    @Test
    fun `testemunha cobertura`() {
        // Codex, rodada 2: I1 isolado. Q nao aparece no registro.
        recusa(
            "Alpha aprovado.",
            "Primeira parte reescrita.${q}Segunda parte nova.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [{"prefix": "Primeira parte reescrita.", "origin": "B0001"}]
            }
            """.trimIndent(),
            "lists 1 entries but the revised custody has 2 blocks",
        )
    }

    @Test
    fun `testemunha prefixo`() {
        recusa(
            "Alpha aprovado.",
            "Alpha reescrito por inteiro.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "protocol_basis": "x"}],
              "revised_block_origins": [{"prefix": "Texto que nao confere com nada", "origin": "B0001"}]
            }
            """.trimIndent(),
            "prefix does not match",
        )
    }

    @Test
    fun `testemunha origem-existe`() {
        // Perplexity e Grok, rodada 2: a origem inexistente nao tinha testemunha.
        recusa(
            "Alpha aprovado.${q}Beta aprovado.",
            "Alpha aprovado.${q}Bloco novo qualquer.",
            """
            {
              "changed_blocks": [{"block_id": "B0002", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "B0001"},
                {"prefix": "Bloco novo qualquer.", "origin": "B9999"}
              ]
            }
            """.trimIndent(),
            "which is not a received block",
        )
    }

    @Test
    fun `testemunha hash-vence`() {
        recusa(
            "Alpha aprovado.${q}Beta aprovado.",
            "Alpha aprovado.${q}Beta reescrito.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": ["edit", "addition"], "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Beta reescrito.", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
            "is an unchanged copy of received block",
        )
    }

    @Test
    fun `testemunha nome-repetido`() {
        // Codex, rodada 8: duas copias exatas nomeiam o mesmo recebido, e a
        // segunda se passa por pedaco de divisao, com split e base declarados.
        // Copia a mais e acrescimo, nao pedaco.
        recusa(
            "Echo.",
            "Echo.${q}Echo.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
            "another unchanged copy already names",
        )
    }

    @Test
    fun `testemunha divisao`() {
        recusa(
            "Alpha aprovado.",
            "Primeiro pedaco.${q}Segundo pedaco.${q}Terceiro pedaco.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "edit", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Primeiro pedaco.", "origin": "B0001"},
                {"prefix": "Segundo pedaco.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "Terceiro pedaco.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
            "continue in more than one revised block",
        )
    }

    @Test
    fun `testemunha pedaco-com-base`() {
        // DeepSeek, rodada 2: um split cobria filhos sem limite. Cada pedaco
        // depois do primeiro traz a propria base.
        recusa(
            "Alpha aprovado.",
            "Primeiro pedaco.${q}Segundo pedaco.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Primeiro pedaco.", "origin": "B0001"},
                {"prefix": "Segundo pedaco.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
            "additional piece of received block",
        )
    }

    @Test
    fun `testemunha acrescimo-com-base`() {
        recusa(
            "Alpha aprovado.",
            "Alpha aprovado.${q}Bloco novo.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "B0001"},
                {"prefix": "Bloco novo.", "origin": "addition"}
              ]
            }
            """.trimIndent(),
            "is declared as an addition",
        )
    }

    @Test
    fun `testemunha reordem-posicao`() {
        // O contraexemplo que derrubou tres heuristicas: A editado e movido,
        // com um acrescimo antes dele. Registro honesto, sem reorder.
        recusa(
            "Bloco A aprovado.${q}Bloco B aprovado.${q}Bloco C aprovado.",
            "Bloco D novo.${q}Bloco B aprovado.${q}Bloco A editado.${q}Bloco C aprovado.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": ["edit", "addition"], "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Bloco D novo.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco A editado.", "origin": "B0001"},
                {"prefix": "Bloco C aprovado.", "origin": "B0003"}
              ]
            }
            """.trimIndent(),
            "reordered received blocks",
        )
    }

    @Test
    fun `testemunha reordem-pedaco`() {
        // Codex e Grok, rodada 2: so a regra do pedaco pega este caso — a
        // primeira ocorrencia de cada bloco esta em ordem.
        recusa(
            "Alpha aprovado.${q}Beta aprovado.",
            "Primeiro pedaco de Alpha.${q}Beta aprovado.${q}Segundo pedaco de Alpha.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Primeiro pedaco de Alpha.", "origin": "B0001"},
                {"prefix": "Beta aprovado.", "origin": "B0002"},
                {"prefix": "Segundo pedaco de Alpha.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
            "reordered received blocks B0001",
        )
    }

    @Test
    fun `testemunha base-da-reordem`() {
        // Codex, rodada 2: a base de protocolo tem de ser conferida no conjunto
        // FINAL de reordenados, o que vem do registro. B0002 declara reorder sem
        // base; so o registro revela que ela moveu.
        recusa(
            "Alpha aprovado.${q}Beta aprovado.",
            "Beta aprovado.${q}Alpha reescrito.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["edit", "reorder"], "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "reorder"}
              ],
              "revised_block_origins": [
                {"prefix": "Beta aprovado.", "origin": "B0002"},
                {"prefix": "Alpha reescrito.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
            "changed_blocks entries for B0002 must include protocol_basis",
        )
    }

    @Test
    fun `testemunha acrescimo-declarado`() {
        // DeepSeek, rodadas 3 e 6: o registro da a base do acrescimo, mas a
        // unica declaracao de crescimento e um split num bloco que nao se
        // dividiu. Bloco novo exige entrada de acrescimo em changed_blocks.
        recusa(
            "Alpha.${q}Beta.",
            "Alpha.${q}New block.${q}Beta.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha.", "origin": "B0001"},
                {"prefix": "New block.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Beta.", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
            "so a changed_blocks entry must declare change_type addition",
        )
    }

    // -- Contrato entre a instrução e a trava -----------------------------

    @Test
    fun `contrato a instrucao pede os nomes que a trava le`() {
        // As duas pontas mudam juntas ou o portão exige o que ninguém pede.
        // Renomear a seção ou um campo de um lado só derruba este teste.
        val instrucao = InstrucaoDoRegistro.TEXTO
        for (nome in listOf(LeituraDoRelatorio.NOME_DO_REGISTRO, "prefix", "origin", "protocol_basis")) {
            assertContains(instrucao, "`$nome`")
        }
        assertContains(instrucao, "\"addition\"")
        assertContains(instrucao, "\"split\"")
        assertContains(instrucao, "\"reorder\"")
    }

    // -- Controles: revisões honestas que a trava tem de aprovar -----------

    @Test
    fun `controle troca de pontas nao acusa o bloco parado`() {
        // Codex, rodada 2: A/B/C -> C/B/A. So A e C declaram reorder; B ficou.
        aprova(
            "Bloco A aprovado.${q}Bloco B aprovado.${q}Bloco C aprovado.",
            "Bloco C aprovado.${q}Bloco B aprovado.${q}Bloco A aprovado.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0003", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Bloco C aprovado.", "origin": "B0003"},
                {"prefix": "Bloco B aprovado.", "origin": "B0002"},
                {"prefix": "Bloco A aprovado.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle copias identicas paradas nomeadas na ordem recebida`() {
        // DeepSeek, rodada 2, com a nomeacao que a instrucao pede: as copias
        // paradas levam, cada uma, o ID do bloco que estava ali. Nomea-las em
        // ordem trocada declara movimento (ver o teste da nomeacao trocada).
        aprova(
            "Paragrafo repetido.${q}Paragrafo repetido.${q}Conclusao aprovada.",
            "Paragrafo repetido.${q}Paragrafo repetido.${q}Conclusao reescrita.",
            """
            {
              "changed_blocks": [{"block_id": "B0003", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Paragrafo repetido.", "origin": "B0001"},
                {"prefix": "Paragrafo repetido.", "origin": "B0002"},
                {"prefix": "Conclusao reescrita.", "origin": "B0003"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle divisao em tres pedacos`() {
        // Hoje impossivel de autorizar sem o registro: um bloco admite uma
        // entrada em changed_blocks, e a divisao em tres pedia duas.
        aprova(
            "Alpha aprovado.",
            "Primeiro pedaco.${q}Segundo pedaco.${q}Terceiro pedaco.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "split", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Primeiro pedaco.", "origin": "B0001"},
                {"prefix": "Segundo pedaco.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "Terceiro pedaco.", "origin": "B0001", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle divisao e acrescimo num documento de um bloco`() {
        // Perplexity, rodada 2: nao havia segundo block_id para autorizar o
        // acrescimo. A base mora na propria entrada.
        aprova(
            "Alpha aprovado.",
            "Primeiro pedaco.${q}Segundo pedaco.${q}Bloco novo.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": ["split", "addition"], "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Primeiro pedaco.", "origin": "B0001"},
                {"prefix": "Segundo pedaco.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "Bloco novo.", "origin": "addition", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle prefixo copiado com quebra de linha`() {
        // Grok e Perplexity, rodada 2: o bloco de varias linhas guarda a quebra
        // e o hash a transforma em espaco. Os dois lados sao normalizados.
        val texto = "Alpha paragraph one.${q}Next${'\n'}section continues here with words."
        aprova(
            texto,
            texto,
            """
            {
              "revised_block_origins": [
                {"prefix": "Alpha paragraph one.", "origin": "B0001"},
                {"prefix": "Next\nsection continu", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle divisao parada numa troca de pontas`() {
        // Codex, rodada 3: B foi dividido e ficou no lugar; so A e C moveram.
        // Comparar o segundo pedaco com tudo o que veio antes acusava B, porque
        // C veio antes de B1.
        aprova(
            "Alpha.${q}Bravo.${q}Charlie.",
            "Charlie.${q}Bravo left.${q}Bravo right.${q}Alpha.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "reason": "authorized", "protocol_basis": "approved", "required": true},
                {"block_id": "B0002", "change_type": "split", "reason": "authorized", "protocol_basis": "approved", "required": true},
                {"block_id": "B0003", "change_type": "reorder", "reason": "authorized", "protocol_basis": "approved", "required": true}
              ],
              "revised_block_origins": [
                {"prefix": "Charlie.", "origin": "B0003"},
                {"prefix": "Bravo left.", "origin": "B0002"},
                {"prefix": "Bravo right.", "origin": "B0002", "protocol_basis": "approved second piece"},
                {"prefix": "Alpha.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle pedacos intercalados acusam so o que passou do outro`() {
        // A/B -> A1/B1/A2/B2: o segundo pedaco de A veio depois de B1, que era
        // posterior a A; o segundo de B veio depois de A2, que era anterior a B.
        // So A declara reorder. Uma regra de "pedaco nao contiguo" acusaria B.
        aprova(
            "Alpha aprovado.${q}Beta aprovado.",
            "Alpha parte um.${q}Beta parte um.${q}Alpha parte dois.${q}Beta parte dois.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["split", "reorder"], "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "split", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Alpha parte um.", "origin": "B0001"},
                {"prefix": "Beta parte um.", "origin": "B0002"},
                {"prefix": "Alpha parte dois.", "origin": "B0001", "protocol_basis": "x"},
                {"prefix": "Beta parte dois.", "origin": "B0002", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle a base do pedaco fica onde o agente disse`() {
        // Codex, rodada 3: B0002 sobe e B0001 vira o bloco do meio e o final.
        // A base do pedaco esta na entrada final, que o agente chamou de
        // pedaco; ignorar a escolha entre textos iguais cobrava a do meio.
        aprova(
            "Echo.${q}Echo.",
            "Echo.${q}Explained echo.${q}Echo.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": ["split", "reorder"], "reason": "authorized", "protocol_basis": "approved", "required": true},
                {"block_id": "B0002", "change_type": "reorder", "reason": "authorized", "protocol_basis": "approved", "required": true}
              ],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0002"},
                {"prefix": "Explained echo.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0001", "protocol_basis": "approved second piece"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle a copia a mais pode ser a primeira`() {
        // O agente repete um bloco ANTES do original e chama a primeira copia de
        // acrescimo, como a instrucao manda. Atribuir primeiro-com-primeiro
        // dava o original a primeira copia e recusava o registro.
        aprova(
            "Echo.",
            "Echo.${q}Echo.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "addition", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "addition", "protocol_basis": "x"},
                {"prefix": "Echo.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle prefixo literal de 20 caracteres com espaco duplo`() {
        // Grok, Perplexity e Codex, rodada 3: a copia exata dos 20 primeiros
        // caracteres tem 19 depois de normalizada. O minimo sai do bloco.
        aprova(
            "The plan is this.  It will work now and later.${q}Second block approved.",
            "The plan is this.  It will work now and later.${q}Second block rewritten.",
            """
            {
              "changed_blocks": [{"block_id": "B0002", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "The plan is this.  I", "origin": "B0001"},
                {"prefix": "Second block rewritte", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle copia identica parada enquanto a outra se move`() {
        // Codex, rodada 5: B0003 (Echo) ficou em terceiro; B0001 (Echo) foi para
        // o fim e B0004 subiu. Renomear o grupo na ordem do texto acusava a copia
        // parada. So B0001 e B0004 declaram reorder.
        aprova(
            "Echo.${q}Alpha.${q}Echo.${q}Bravo.",
            "Bravo.${q}Alpha.${q}Echo.${q}Echo.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0004", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Bravo.", "origin": "B0004"},
                {"prefix": "Alpha.", "origin": "B0002"},
                {"prefix": "Echo.", "origin": "B0003"},
                {"prefix": "Echo.", "origin": "B0001"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle a ordem segue a nomeacao do registro`() {
        // Codex, rodada 6: o agente conta que as duas copias de Echo trocaram
        // de lugar e que Alpha se dividiu em volta de uma delas, e declara isso.
        // A ordem e conferida contra esse relato, e so contra ele.
        aprova(
            "Echo.${q}Alpha.${q}Echo.",
            "Echo.${q}Alpha one.${q}Echo.${q}Alpha two.",
            """
            {
              "changed_blocks": [
                {"block_id": "B0001", "change_type": "reorder", "protocol_basis": "x"},
                {"block_id": "B0002", "change_type": "split", "protocol_basis": "x"},
                {"block_id": "B0003", "change_type": "reorder", "protocol_basis": "x"}
              ],
              "revised_block_origins": [
                {"prefix": "Echo.", "origin": "B0003"},
                {"prefix": "Alpha one.", "origin": "B0002"},
                {"prefix": "Echo.", "origin": "B0001"},
                {"prefix": "Alpha two.", "origin": "B0002", "protocol_basis": "x"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle registro resolve a copia identica editada`() {
        // Codex, rodada 6: com duas copias identicas e uma editada, a trava
        // fechava antes de ler o relatorio, e a correcao que a mensagem
        // anunciava nunca funcionou. O registro diz qual copia ficou intacta.
        aprova(
            "Echo.${q}Echo.",
            "Echo revised.${q}Echo.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "edit", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Echo revised.", "origin": "B0001"},
                {"prefix": "Echo.", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle registro resolve a copia identica removida`() {
        // Codex, rodada 6: X/Y/X -> Y/X. O registro diz que sobreviveu B0003 e
        // a remocao declarada e a de B0001.
        aprova(
            "Xis.${q}Ypsilon.${q}Xis.",
            "Ypsilon.${q}Xis.",
            """
            {
              "changed_blocks": [{"block_id": "B0001", "change_type": "delete", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Ypsilon.", "origin": "B0002"},
                {"prefix": "Xis.", "origin": "B0003"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle composicao segue a nomeacao do registro`() {
        // Codex, rodada 7: os dois casos de copias identicas no mesmo texto. Com
        // a ordem do registro, o que se moveu no relato do agente e declarado:
        // B0001 e B0003 trocaram, B0004 e B0006 sairam do lugar, B0007 subiu.
        aprova(
            "Echo.${q}Alpha.${q}Echo.${q}Foxtrot.${q}Charlie.${q}Foxtrot.${q}Bravo.",
            "Echo.${q}Alpha one.${q}Echo.${q}Alpha two.${q}Bravo.${q}Charlie.${q}Foxtrot.${q}Foxtrot.",
            """
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
                {"prefix": "Foxtrot.", "origin": "B0004"},
                {"prefix": "Foxtrot.", "origin": "B0006"}
              ]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `controle declaracao redundante num bloco intacto`() {
        // Perplexity, rodada 1: nomear a origem de um bloco que casou por hash
        // e declaracao honesta, nao conflito.
        aprova(
            "Alpha aprovado.${q}Beta aprovado.",
            "Alpha aprovado.${q}Beta reescrito.",
            """
            {
              "changed_blocks": [{"block_id": "B0002", "protocol_basis": "x"}],
              "revised_block_origins": [
                {"prefix": "Alpha aprovado.", "origin": "B0001"},
                {"prefix": "Beta reescrito.", "origin": "B0002"}
              ]
            }
            """.trimIndent(),
        )
    }
}
