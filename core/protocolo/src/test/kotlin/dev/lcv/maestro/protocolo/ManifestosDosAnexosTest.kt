package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A escolha do anexo e a leitura tipada do manifesto, com as regras do `serde`
 * que o canônico usa (`abnt_citation.rs`, linhas 1387–1442 em `68528f9`). O
 * caso positivo é o exemplo oficial do canônico,
 * `docs/examples/citation-manifest.example.json`, copiado byte a byte.
 */
class ManifestosDosAnexosTest {

    private val exemplo = """
        {
          "schema_version": "citation_manifest.v1",
          "protocol_hash": "0000000000000000000000000000000000000000000000000000000000000000",
          "citations": [
            {
              "schema_version": "citation.v1",
              "claim_id": "claim-001",
              "citation_type": "direct_quote",
              "author_display": "Silva, Maria",
              "author_key": "SILVA",
              "year": "2026",
              "locator": "p. 12",
              "source_id": "source-001",
              "source_access": "full_document_opened",
              "verification_status": "verified",
              "risk_if_wrong": "medium",
              "original_text": "Trecho literal comprovado pelo operador."
            }
          ],
          "sources": [
            {
              "source_id": "source-001",
              "source_type": "book",
              "authors": [
                {
                  "author_display": "Silva, Maria",
                  "author_key": "SILVA"
                }
              ],
              "title": "Obra de exemplo",
              "place": "Sao Paulo",
              "publisher": "Editora Exemplo",
              "year": "2026",
              "verification_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "verification_status": "verified",
              "prohibited": false
            }
          ]
        }
    """.trimIndent()

    private fun anexo(nome: String, conteudo: String, tipo: String = "application/octet-stream") =
        ManifestosDosAnexos.Anexo(nome, tipo) { conteudo.toByteArray(Charsets.UTF_8) }

    private fun lidos(vararg anexos: ManifestosDosAnexos.Anexo): ManifestosDosAnexos.Manifestos =
        when (val saida = ManifestosDosAnexos.extrair(anexos.toList())) {
            is ManifestosDosAnexos.Saida.Lidos -> saida.manifestos
            is ManifestosDosAnexos.Saida.Recusados -> fail("recusado: ${saida.motivo}")
        }

    private fun recusa(vararg anexos: ManifestosDosAnexos.Anexo): String =
        when (val saida = ManifestosDosAnexos.extrair(anexos.toList())) {
            is ManifestosDosAnexos.Saida.Lidos -> fail("deveria recusar, leu ${saida.manifestos}")
            is ManifestosDosAnexos.Saida.Recusados -> saida.motivo
        }

    /** O exemplo com uma troca literal, que tem de acontecer uma vez só. */
    private fun exemploCom(de: String, para: String): String {
        assertEquals(1, exemplo.split(de).size - 1, "âncora ambígua ou ausente: $de")
        return exemplo.replace(de, para)
    }

    @Test
    fun `o exemplo oficial do canonico e lido inteiro`() {
        val manifesto = lidos(anexo("citation-manifest.json", exemplo)).atual ?: fail("sem manifesto")
        assertEquals("citation_manifest.v1", manifesto.versaoDoEsquema)
        val citacao = manifesto.citacoes.single()
        assertEquals(TipoDeCitacao.CITACAO_DIRETA, citacao.tipo)
        assertEquals(AcessoAFonte.DOCUMENTO_INTEGRAL_ABERTO, citacao.acesso)
        assertEquals("p. 12", citacao.localizador)
        assertNull(citacao.textoNormalizado)
        val fonte = manifesto.fontes.single()
        assertEquals(TipoDeFonte.LIVRO, fonte.tipo)
        assertEquals(listOf(AutorDaFonte("Silva, Maria", "SILVA")), fonte.autores)
        assertNull(fonte.subtitulo)
        assertEquals(false, fonte.proibida)
    }

    @Test
    fun `anexo sem cara de JSON e ignorado`() {
        val manifestos = lidos(anexo("notas.txt", exemplo, "text/plain"))
        assertNull(manifestos.atual)
        assertNull(manifestos.anterior)
    }

    @Test
    fun `tipo de midia JSON basta mesmo sem extensao`() {
        assertTrue(lidos(anexo("dados", exemplo, "Application/JSON")).atual != null)
    }

    @Test
    fun `JSON de outro esquema com nome comum e ignorado`() {
        assertNull(lidos(anexo("dados.json", """{"schema_version":"outro"}""")).atual)
    }

    @Test
    fun `nome explicito com JSON invalido falha fechado`() {
        assertEquals("citation manifest attachment is not valid JSON", recusa(anexo("manifesto-citacoes.json", "{")))
    }

    @Test
    fun `nome explicito com outro esquema falha fechado`() {
        assertEquals(
            "citation manifest attachment must use citation_manifest.v1",
            recusa(anexo("citation_manifest.json", """{"schema_version":"outro"}""")),
        )
    }

    @Test
    fun `JSON invalido com nome comum e ignorado`() {
        assertNull(lidos(anexo("dados.json", "{")).atual)
    }

    @Test
    fun `marca BOM e recusada como no serde_json`() {
        assertEquals(
            "citation manifest attachment is not valid JSON",
            recusa(anexo("citation-manifest.json", "\uFEFF" + exemplo)),
        )
    }

    @Test
    fun `so UTF-8 valido e lido, como no serde_json`() {
        fun bruto(nome: String, conteudo: ByteArray) = ManifestosDosAnexos.Anexo(nome, "application/json") { conteudo }
        val invalido = "citation manifest attachment is not valid JSON"
        // UTF-16 e UTF-32 sem BOM: o Jackson, lendo bytes, os detectaria e leria.
        for (charset in listOf(Charsets.UTF_16LE, Charsets.UTF_16BE, Charsets.UTF_32LE, Charsets.UTF_32BE)) {
            assertEquals(invalido, recusa(bruto("citation-manifest.json", exemplo.toByteArray(charset))), charset.name())
        }
        // Sequ\u00EAncias que o UTF-8 pro\u00EDbe, dentro de um texto do manifesto:
        // surrogate codificado (CESU-8), forma longa demais e byte de
        // continua\u00E7\u00E3o solto.
        val (antes, depois) = exemplo.split("Obra de exemplo").map { it.toByteArray(Charsets.UTF_8) }
        for (sequencia in listOf(bytes(0xED, 0xA0, 0x80), bytes(0xC0, 0xAF), bytes(0x80))) {
            assertEquals(invalido, recusa(bruto("citation-manifest.json", antes + sequencia + depois)))
        }
        // Com nome comum, o anexo ileg\u00EDvel \u00E9 ignorado, como o JSON inv\u00E1lido.
        assertNull(lidos(bruto("dados.json", exemplo.toByteArray(Charsets.UTF_16LE))).atual)
        // Controle: os mesmos bytes em UTF-8 s\u00E3o lidos.
        assertEquals("Obra de exemplo", lidos(bruto("citation-manifest.json", exemplo.toByteArray())).atual?.fontes?.single()?.titulo)
    }

    private fun bytes(vararg valores: Int) = ByteArray(valores.size) { valores[it].toByte() }

    @Test
    fun `escape de surrogate sem par e recusado como no serde_json`() {
        // O Jackson aceitaria `\uD800` e devolveria um texto que o UTF-8 não
        // representa; o `serde_json` recusa o JSON.
        val invalido = "citation manifest attachment is not valid JSON"
        for (escape in listOf("\\uD800", "\\uDC00", "\\uDE00\\uD83D", "\\uD83Dx")) {
            assertEquals(invalido, recusa(anexo("citation-manifest.json", exemploCom("Obra de exemplo", "Obra $escape"))), escape)
        }
        // Também numa chave de objeto, que o manifesto ignoraria.
        assertEquals(
            invalido,
            recusa(anexo("citation-manifest.json", exemploCom("\"prohibited\": false", "\"prohibited\": false, \"\\uD800\": 1"))),
        )
        // Controle: o par completo é lido.
        val par = lidos(anexo("citation-manifest.json", exemploCom("Obra de exemplo", "Obra \\uD83D\\uDE00")))
        assertEquals("Obra \uD83D\uDE00", par.atual?.fontes?.single()?.titulo)
    }

    @Test
    fun `texto depois do valor e JSON invalido`() {
        assertEquals(
            "citation manifest attachment is not valid JSON",
            recusa(anexo("citation-manifest.json", "$exemplo {}")),
        )
    }

    @Test
    fun `manifesto anterior vai para o outro lugar`() {
        val manifestos = lidos(
            anexo("citation-manifest.json", exemplo),
            anexo("citation-manifest-previous.json", exemplo),
        )
        assertTrue(manifestos.atual != null && manifestos.anterior != null)
    }

    @Test
    fun `dois manifestos atuais sao recusados`() {
        assertEquals(
            "multiple current citation manifests were supplied",
            recusa(anexo("citation-manifest.json", exemplo), anexo("outro.json", exemplo)),
        )
    }

    @Test
    fun `dois manifestos anteriores sao recusados`() {
        assertEquals(
            "multiple previous citation manifests were supplied",
            recusa(anexo("anterior.json", exemplo), anexo("previous.json", exemplo)),
        )
    }

    @Test
    fun `campo obrigatorio ausente e erro`() {
        val motivo = recusa(anexo("citation-manifest.json", exemploCom("\"title\": \"Obra de exemplo\",", "")))
        assertEquals("citation manifest payload is invalid: missing field `title`", motivo)
    }

    @Test
    fun `null em campo obrigatorio e erro`() {
        val motivo = recusa(anexo("citation-manifest.json", exemploCom("\"year\": \"2026\",\n      \"locator\"", "\"year\": null,\n      \"locator\"")))
        assertTrue(motivo.startsWith("citation manifest payload is invalid: invalid type: null"), motivo)
    }

    @Test
    fun `numero no lugar de texto e erro`() {
        val motivo = recusa(anexo("citation-manifest.json", exemploCom("\"claim_id\": \"claim-001\"", "\"claim_id\": 1")))
        assertTrue(motivo.contains("invalid type: number"), motivo)
    }

    @Test
    fun `opcional null vira ausente e opcional de outro tipo e erro`() {
        val comNull = lidos(anexo("citation-manifest.json", exemploCom("\"locator\": \"p. 12\"", "\"locator\": null")))
        assertNull(comNull.atual?.citacoes?.single()?.localizador)
        val motivo = recusa(anexo("citation-manifest.json", exemploCom("\"locator\": \"p. 12\"", "\"locator\": 12")))
        assertTrue(motivo.contains("invalid type: number"), motivo)
    }

    @Test
    fun `lista ausente vira vazia e lista null e erro`() {
        val semAutores = exemploCom(
            "\"authors\": [\n        {\n          \"author_display\": \"Silva, Maria\",\n          \"author_key\": \"SILVA\"\n        }\n      ],",
            "",
        )
        assertEquals(emptyList(), lidos(anexo("citation-manifest.json", semAutores)).atual?.fontes?.single()?.autores)
        val nulo = exemploCom("\"citations\": [", "\"citations\": null, \"x\": [")
        assertTrue(recusa(anexo("citation-manifest.json", nulo)).contains("invalid type: null"))
    }

    @Test
    fun `logico com padrao falso ausente vale falso e null e erro`() {
        val ausente = exemploCom(",\n      \"prohibited\": false", "")
        assertEquals(false, lidos(anexo("citation-manifest.json", ausente)).atual?.fontes?.single()?.proibida)
        val nulo = exemploCom("\"prohibited\": false", "\"prohibited\": null")
        assertTrue(recusa(anexo("citation-manifest.json", nulo)).contains("expected a boolean"))
    }

    @Test
    fun `variante de enum desconhecida e erro`() {
        val motivo = recusa(anexo("citation-manifest.json", exemploCom("\"citation_type\": \"direct_quote\"", "\"citation_type\": \"Direct_Quote\"")))
        assertTrue(motivo.contains("unknown variant `Direct_Quote`"), motivo)
    }

    @Test
    fun `campo desconhecido e ignorado`() {
        val extra = exemploCom("\"claim_id\": \"claim-001\",", "\"claim_id\": \"claim-001\", \"nota\": {\"livre\": true},")
        assertEquals("claim-001", lidos(anexo("citation-manifest.json", extra)).atual?.citacoes?.single()?.claimId)
    }

    @Test
    fun `chave repetida e recusada, mais estrito que o canonico`() {
        // O canônico lê como `Value` antes e fica com a última; aqui é recusa.
        val repetida = exemploCom("\"claim_id\": \"claim-001\",", "\"claim_id\": \"claim-001\", \"claim_id\": \"claim-002\",")
        assertTrue(recusa(anexo("citation-manifest.json", repetida)).startsWith("citation manifest payload is invalid"))
    }

    @Test
    fun `falha ao ler o anexo recusa a sessao`() {
        val quebrado = ManifestosDosAnexos.Anexo("citation-manifest.json", "application/json") {
            throw java.io.IOException("disco")
        }
        assertEquals("failed to read citation manifest attachment: disco", recusa(quebrado))
    }
}
