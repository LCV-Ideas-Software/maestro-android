package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Suíte canônica da trava de conteúdo, portada caso a caso de
 * `maestro-app/src-tauri/src/editorial_content_lock.rs`, a partir da linha 608.
 *
 * Portar em vez de inventar é deliberado: os casos do canônico já exercem o
 * comportamento que o desktop exerce em produção, com valores conhecidos. Caso
 * inventado por mim provaria que o Kotlin faz o que eu achei que ele devia
 * fazer, e não o que o produto faz.
 *
 * Os 19 casos do canônico estão aqui. **Catorze estão como no canônico.**
 * Cinco ganharam uma seção que o canônico não tem, `revised_block_origins`,
 * porque este repositório passou a exigi-la quando o texto revisado tem bloco
 * que não é cópia intacta de um recebido (Discussion #41): sem ela, os cinco
 * seriam recusados. Os cinco são os de aprovação com edição ou acréscimo —
 * `bloco alterado declarado com protocol_basis e aceito`, `acrescimo declarado
 * nao faz os blocos seguintes parecerem alterados`, `o fim da secao casa com
 * campo, nao com texto de valor`, `o fim da secao ignora aspas escapadas dentro
 * do valor` e `bloco declarado pode ser dividido sem palavra extra de
 * acrescimo`. Nenhum veredito de recusa do canônico mudou.
 */
class TravaDeConteudoTest {

    private fun motivoDaViolacao(antes: String, depois: String, relatorio: String): String =
        when (val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)) {
            is TravaDeConteudo.Veredito.Violada -> veredito.motivo
            TravaDeConteudo.Veredito.Aprovada ->
                fail("esperava violação da trava, e a revisão foi aprovada")
        }

    private fun exigirAprovacao(antes: String, depois: String, relatorio: String) {
        val veredito = TravaDeConteudo.validarRevisao(antes, depois, relatorio)
        if (veredito is TravaDeConteudo.Veredito.Violada) {
            fail("esperava aprovação, e a trava recusou: ${veredito.motivo}")
        }
    }

    @Test
    fun `bloco alterado sem declaracao em changed_blocks e recusado`() {
        val antes =
            "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois = "# Titulo\n\nParagrafo encurtado.\n\nReferencia removida."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "B0002")
    }

    @Test
    fun `bloco alterado declarado com protocol_basis e aceito`() {
        val antes =
            "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois = "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia removida."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
              ],
              "revised_block_origins": [
                {"prefix": "# Titulo", "origin": "B0001"},
                {"prefix": "Paragrafo aprovado e denso.", "origin": "B0002"},
                {"prefix": "Referencia removida.", "origin": "B0003"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `declaracao de bloco alterado exige protocol_basis`() {
        val antes = "# Titulo\n\nParagrafo aprovado.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois = "# Titulo\n\nParagrafo aprovado.\n\nReferencia removida."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0003", "reason": "removed reference"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "protocol_basis")
    }

    @Test
    fun `protocol_basis estruturado e vazio e recusado`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo encurtado."
        val comArranjo = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "protocol_basis": []}
              ],
              "custody": "revised"
            }
        """.trimIndent()
        val comObjeto = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "protocol_basis": {}}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, comArranjo), "protocol_basis")
        assertContains(motivoDaViolacao(antes, depois, comObjeto), "protocol_basis")
    }

    @Test
    fun `cada bloco alterado exige o proprio protocol_basis`() {
        val antes =
            "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois = "# Titulo\n\nParagrafo encurtado.\n\nReferencia removida."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "reason": "shortened"},
                {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        val motivo = motivoDaViolacao(antes, depois, relatorio)
        assertContains(motivo, "B0002")
        assertContains(motivo, "protocol_basis")
    }

    @Test
    fun `citar um block_id na justificativa nao autoriza aquele bloco`() {
        val antes =
            "# Titulo\n\nParagrafo aprovado e denso.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois = "# Titulo\n\nParagrafo encurtado.\n\nReferencia removida."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0003",
                  "reason": "removed reference and mentioned B0002 only as surrounding context",
                  "protocol_basis": "bibliographic integrity"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        val motivo = motivoDaViolacao(antes, depois, relatorio)
        assertContains(motivo, "B0002")
        assertContains(motivo, "without matching changed_blocks declaration")
    }

    @Test
    fun `acrescimo furtivo e recusado mesmo com outro bloco declarado`() {
        val antes = "# Titulo\n\nParagrafo aprovado.\n\nReferencia pendente [EVIDENCIA_PENDENTE]."
        val depois =
            "# Titulo\n\nParagrafo aprovado.\n\nReferencia removida.\n\nNovo argumento indevido."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0003", "protocol_basis": "bibliographic integrity"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "added new blocks")
    }

    @Test
    fun `acrescimo declarado nao faz os blocos seguintes parecerem alterados`() {
        val antes = "# Titulo\n\nParagrafo aprovado.\n\nConclusao aprovada."
        val depois =
            "# Titulo\n\nNovo contexto necessario.\n\nParagrafo aprovado.\n\nConclusao aprovada."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "addition", "protocol_basis": "required context"}
              ],
              "revised_block_origins": [
                {"prefix": "# Titulo", "origin": "B0001"},
                {"prefix": "Novo contexto necessario.", "origin": "addition", "protocol_basis": "required context"},
                {"prefix": "Paragrafo aprovado.", "origin": "B0002"},
                {"prefix": "Conclusao aprovada.", "origin": "B0003"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `reordenacao silenciosa de blocos recebidos e recusada`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado."
        val depois = "# Titulo\n\nSegundo bloco aprovado.\n\nPrimeiro bloco aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "protocol_basis": "style preference"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "reordered received blocks")
    }

    @Test
    fun `reordenacao silenciosa sem secao changed_blocks e recusada`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado."
        val depois = "# Titulo\n\nSegundo bloco aprovado.\n\nPrimeiro bloco aprovado."
        val relatorio = """
            {
              "reviewer": "gemini",
              "status": "READY",
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "reordered received blocks")
    }

    @Test
    fun `declaracao de reordenacao tem de nomear os blocos que se moveram`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado."
        val depois = "# Titulo\n\nSegundo bloco aprovado.\n\nPrimeiro bloco aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0001",
                  "change_type": "reorder",
                  "protocol_basis": "structure"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        val motivo = motivoDaViolacao(antes, depois, relatorio)
        assertContains(motivo, "B0002")
        assertContains(motivo, "B0003")
        assertContains(motivo, "reorder")
    }

    @Test
    fun `reordenacao declarada para cada bloco movido e aceita`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado."
        val depois = "# Titulo\n\nSegundo bloco aprovado.\n\nPrimeiro bloco aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "change_type": "reorder",
                  "protocol_basis": "structure"
                },
                {
                  "block_id": "B0003",
                  "change_type": "reorder",
                  "protocol_basis": "structure"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `blocos identicos repetidos nao escondem exigencias distintas de reordenacao`() {
        val antes = "# Titulo\n\nParagrafo repetido aprovado.\n\nBloco medio aprovado.\n\n" +
            "Paragrafo repetido aprovado.\n\nConclusao aprovada."
        val depois = "# Titulo\n\nBloco medio aprovado.\n\nParagrafo repetido aprovado.\n\n" +
            "Paragrafo repetido aprovado.\n\nConclusao aprovada."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0003",
                  "change_type": "reorder",
                  "protocol_basis": "structure"
                }
              ],
              "custody": "revised"
            }
        """.trimIndent()

        val motivo = motivoDaViolacao(antes, depois, relatorio)
        assertContains(motivo, "B0002")
        assertContains(motivo, "reorder")
    }

    @Test
    fun `o fim da secao casa com campo, nao com texto de valor`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "reason": "clarifies custody transfer without changing scope",
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

    @Test
    fun `o fim da secao ignora aspas escapadas dentro do valor`() {
        val antes = "# Titulo\n\nParagrafo aprovado."
        val depois = "# Titulo\n\nParagrafo corrigido."
        val relatorio = """
            {
              "changed_blocks": [
                {
                  "block_id": "B0002",
                  "reason": "clarifies \"custody\" transfer without changing scope",
                  "protocol_basis": "editorial precision with escaped \"custody\" text"
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

    @Test
    fun `reordenacao com edicao declarada e recusada sem declarar reordenacao`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado.\n\n" +
            "Terceiro bloco aprovado."
        val depois = "# Titulo\n\nTerceiro bloco aprovado.\n\nPrimeiro bloco editado.\n\n" +
            "Segundo bloco aprovado."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "protocol_basis": "editorial correction"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        assertContains(motivoDaViolacao(antes, depois, relatorio), "reordered received blocks")
    }

    @Test
    fun `bloco movido e editado ainda exige protocol_basis em changed_blocks`() {
        val antes = "# Titulo\n\nPrimeiro bloco aprovado.\n\nSegundo bloco aprovado.\n\n" +
            "Terceiro bloco aprovado."
        val depois = "# Titulo\n\nTerceiro bloco editado.\n\nPrimeiro bloco aprovado.\n\n" +
            "Segundo bloco aprovado."
        val relatorio = """
            {
              "reviewer": "grok",
              "status": "READY",
              "custody": "revised"
            }
        """.trimIndent()

        val motivo = motivoDaViolacao(antes, depois, relatorio)
        assertContains(motivo, "B0004")
        assertContains(motivo, "changed_blocks")
    }

    @Test
    fun `bloco declarado pode ser dividido sem palavra extra de acrescimo`() {
        val antes = "# Titulo\n\nParagrafo longo com uma referencia pendente [EVIDENCIA_PENDENTE]."
        val depois =
            "# Titulo\n\nParagrafo longo sem a referencia pendente.\n\nNota editorial preservada."
        val relatorio = """
            {
              "changed_blocks": [
                {"block_id": "B0002", "change_type": "split", "protocol_basis": "bibliographic integrity"}
              ],
              "revised_block_origins": [
                {"prefix": "# Titulo", "origin": "B0001"},
                {"prefix": "Paragrafo longo sem a referencia pendente.", "origin": "B0002"},
                {"prefix": "Nota editorial preservada.", "origin": "B0002", "protocol_basis": "bibliographic integrity"}
              ],
              "custody": "revised"
            }
        """.trimIndent()

        exigirAprovacao(antes, depois, relatorio)
    }

    @Test
    fun `o manifesto do prompt expoe identificadores estaveis de bloco`() {
        val texto = "# Titulo\n\nParagrafo aprovado.\n\n- item"

        val blocos = TravaDeConteudo.segmentarBlocos(texto)
        val manifesto = TravaDeConteudo.formatarManifestoParaPrompt(texto)

        assertEquals(3, blocos.size)
        assertEquals("B0001", blocos[0].id)
        assertEquals("B0002", blocos[1].id)
        assertEquals("list", blocos[2].tipo)
        assertContains(manifesto, "| B0001 | heading |")
        assertContains(manifesto, "| B0002 | paragraph |")
        assertContains(manifesto, "locked_by_default")
    }
}
