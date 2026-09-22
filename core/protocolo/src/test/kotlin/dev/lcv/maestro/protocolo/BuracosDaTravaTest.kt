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
 * Todos estes cenários **passavam** pela trava antes de 22/09/2026, a maioria
 * deles aprovando revisão que devia recusar. Vieram de duas fontes: nove
 * achados de revisão automática na PR #39 e uma rodada de cross-review com
 * cinco modelos, que achou mais e pior.
 *
 * A ordem aqui é a do risco real: primeiro os que disparam com **prosa comum**,
 * que é o que um agente escreve sem nenhuma má intenção; depois os que exigem
 * construção deliberada.
 *
 * Cada caso foi conferido contra a implementação antiga: reverter o
 * endurecimento correspondente derruba o teste.
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
    fun `um preambulo em prosa nao faz a secao desaparecer`() {
        // Um relatorio que abre com uma frase tinha a secao inteira ignorada,
        // porque o ultimo caractere nao branco antes da chave era o `:` da
        // frase. A trava agia como se nao houvesse declaracao e recusava
        // trabalho legitimo.
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            Nesta rodada eu revisei o texto. Resumo das mudancas:
            changed_blocks:
            - block_id: B0002
              protocol_basis: editorial precision
              reason: ajuste de concordancia
            custody: revised
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `entrada YAML de varias linhas preserva a base de protocolo`() {
        // O fallback guardava so as linhas que continham block_id, jogando fora
        // a linha seguinte com a justificativa.
        val antes = "# Titulo\n\nParagrafo aprovado.\n\nConclusao aprovada."
        val depois = "# Titulo\n\nParagrafo corrigido.\n\nConclusao aprovada."
        val relatorio = """
            changed_blocks:
            - block_id: B0002
              change_type: edit
              protocol_basis: editorial precision
            custody: revised
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
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

        exigirViolacao(antes, depois, relatorio, "more than one block_id")
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
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B10000", "protocol_basis": "editorial precision"}
              ],
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
}
