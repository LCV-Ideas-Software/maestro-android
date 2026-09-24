package dev.lcv.maestro.protocolo

import java.time.Instant
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** O manifesto e o texto verificados, também usados por [AuditoriaFinalTest]. */
internal fun manifestoVerificado(): ManifestoDeCitacoes = ManifestoDeCitacoes(
    versaoDoEsquema = AuditoriaAbnt.ESQUEMA_DO_MANIFESTO,
    hashDoProtocolo = "protocol-sha256",
    citacoes = listOf(
        Citacao(
            versaoDoEsquema = AuditoriaAbnt.ESQUEMA_DA_CITACAO,
            claimId = "claim-1",
            tipo = TipoDeCitacao.CITACAO_DIRETA,
            autorExibido = "Silva, Maria",
            chaveDoAutor = "SILVA",
            ano = "2026",
            localizador = "p. 12",
            fonteId = "source-1",
            acesso = AcessoAFonte.DOCUMENTO_INTEGRAL_ABERTO,
            verificacao = StatusDeVerificacao.VERIFICADA,
            riscoSeErrada = RiscoSeErrada.MEDIO,
            textoOriginal = "(Silva, 2026, p. 12)",
            textoNormalizado = null,
            notaNormalizada = null,
        ),
    ),
    fontes = listOf(
        Fonte(
            fonteId = "source-1",
            tipo = TipoDeFonte.LIVRO,
            autores = listOf(AutorDaFonte("Silva, Maria", "SILVA")),
            titulo = "Obra",
            subtitulo = null,
            edicao = null,
            local = "Sao Paulo",
            editora = "Editora",
            ano = "2026",
            tituloDoConjunto = null,
            volume = null,
            numero = null,
            paginas = null,
            url = null,
            doi = null,
            acessadoEm = null,
            sha256DaVerificacao = "a".repeat(64),
            verificacao = StatusDeVerificacao.VERIFICADA,
            proibida = false,
            motivoDaQuarentena = null,
        ),
    ),
)

internal val textoVerificado =
    "“Trecho direto com mais de quatro palavras” (Silva, 2026, p. 12).\n\n## Referencias\n" +
        "SILVA, Maria. Obra. Sao Paulo: Editora, 2026."

/**
 * A suíte canônica de `abnt_citation.rs` (linhas 1599–1769 em `68528f9`),
 * portada caso a caso, e depois os casos que ela não cobre porque é toda ASCII
 * e só usa `\n`: são as diferenças entre o Rust e a JVM/Android que um porte
 * ingênuo deixaria passar verdes.
 */
class AuditoriaAbntTest {

    private val agora: Instant = Instant.parse("2026-09-24T12:00:00Z")

    private fun auditar(
        texto: String,
        hash: String? = null,
        manifesto: ManifestoDeCitacoes? = null,
    ): ResultadoAbnt {
        val saida = AuditoriaAbnt.auditar(texto, hash, manifesto, null, agora)
        return (saida as AuditoriaAbnt.Saida.Concluida).resultado
    }

    private fun ResultadoAbnt.temBloqueio(codigo: String) = bloqueios.any { it.codigo == codigo }

    // ── a suíte canônica ─────────────────────────────────────────────────────

    @Test
    fun `texto sem aparato bibliografico esta pronto`() {
        val resultado = auditar("Texto autoral sem citacao.")
        assertEquals(StatusDoParMaestro.PRONTO, resultado.statusDoParMaestro)
        assertTrue(resultado.bloqueios.isEmpty())
    }

    @Test
    fun `citacao direta sem localizador exige evidencia`() {
        val resultado = auditar(
            "“Esta e uma citacao direta suficientemente longa” (Silva, 2020).\n\n## Referencias\n" +
                "SILVA, Ana. Obra completa. Sao Paulo: Editora, 2020.",
        )
        assertEquals(StatusDoParMaestro.EXIGE_EVIDENCIA, resultado.statusDoParMaestro)
        assertTrue(resultado.temBloqueio("direct_quote_locator_missing"))
    }

    @Test
    fun `o pareamento entre citacao e referencia vale nos dois sentidos`() {
        val resultado = auditar(
            "Texto indireto (Silva, 2020).\n\n## Referencias\nSOUZA, Bia. Outra obra. Rio: Editora, 2021.",
        )
        assertTrue(resultado.temBloqueio("citation_without_reference"))
        assertTrue(resultado.temBloqueio("reference_without_body_use"))
    }

    @Test
    fun `fonte proibida nao esta pronta`() {
        val resultado = auditar("Texto sem citacao formal. Fonte: https://pt.wikipedia.org/wiki/Teste")
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        assertTrue(resultado.temBloqueio("prohibited_source"))
    }

    @Test
    fun `manifesto verificado gera as saidas normalizadas e o par fica pronto`() {
        val resultado = auditar(textoVerificado, "protocol-sha256", manifestoVerificado())
        assertEquals(StatusDoParMaestro.PRONTO, resultado.statusDoParMaestro, resultado.bloqueios.toString())
        assertEquals("(Silva, 2026, p. 12)", resultado.citacoes[0].textoNormalizado)
        assertTrue(resultado.citacoes[0].notaNormalizada.orEmpty().contains("SILVA, Maria. Obra."))
        assertTrue(resultado.bloqueios.isEmpty())
    }

    @Test
    fun `fonte verificada exige impressao digital de verificacao real`() {
        val manifesto = manifestoVerificado().let { original ->
            original.copy(fontes = listOf(original.fontes[0].copy(sha256DaVerificacao = null)))
        }
        val resultado = auditar(textoVerificado, "protocol-sha256", manifesto)
        assertEquals(StatusDoParMaestro.EXIGE_EVIDENCIA, resultado.statusDoParMaestro)
        assertTrue(resultado.bloqueios.any { it.codigo == "reference_required_fields_missing" && it.exigeEvidencia })
    }

    @Test
    fun `sufixo de ano usado para desambiguar e valido`() {
        assertTrue(AuditoriaAbnt.anoValido("2026a"))
        assertTrue(AuditoriaAbnt.anoValido("2026B"))
        assertFalse(AuditoriaAbnt.anoValido("26a"))
    }

    @Test
    fun `manifesto vazio nao aceita em silencio sinal de citacao em nota`() {
        val resultado = auditar(
            "Texto com nota bibliografica[^1].\n\n[^1]: Fonte consultada.",
            "protocol-sha256",
            AuditoriaAbnt.manifestoVazio("protocol-sha256"),
        )
        assertEquals(StatusDoParMaestro.EXIGE_EVIDENCIA, resultado.statusDoParMaestro)
        assertTrue(resultado.temBloqueio("unstructured_citation_signal"))
    }

    // ── o que a suíte canônica não vê ────────────────────────────────────────

    @Test
    fun `sem manifesto toda citacao detectada bloqueia`() {
        val resultado = auditar(
            "Texto (Silva, 2020).\n\n## Referencias\nSILVA, Ana. Obra completa. Rio: Editora, 2020.",
        )
        assertTrue(resultado.temBloqueio("structured_manifest_missing"))
        assertTrue(AuditoriaAbnt.bloqueiaLiberacao(resultado))
    }

    @Test
    fun `sem manifesto nota, cite e apud bloqueiam mesmo sem citacao autor-data`() {
        // Divergência do canônico: lá estes sinais só eram conferidos com
        // manifesto, e sem ele o texto saía pronto.
        for (texto in listOf(
            "Texto com nota bibliografica[^1].\n\n[^1]: Fonte consultada.",
            "Texto com <cite>Obra</cite> citada.",
            "Como afirma o autor apud outro, a tese vale.",
        )) {
            val resultado = auditar(texto)
            assertTrue(resultado.citacoes.isEmpty(), texto)
            assertTrue(resultado.temBloqueio("unstructured_citation_signal"), texto)
            assertTrue(AuditoriaAbnt.bloqueiaLiberacao(resultado), texto)
        }
        // Controle: o mesmo texto sem o sinal está pronto.
        assertEquals(StatusDoParMaestro.PRONTO, auditar("Como afirma o autor, a tese vale.").statusDoParMaestro)
    }

    @Test
    fun `mais de 500 citacoes, aspas, notas ou referencias reprovam em vez de sumir`() {
        // Divergência do canônico: lá o que passava de 500 era ignorado em
        // silêncio. Cada caso tem o controle em exatamente 500.
        fun citacoes(n: Int) = (1..n).joinToString(" ") { "Frase (Silva, 2020)." }
        fun aspas(n: Int) = (1..n).joinToString("\n") { "\"um trecho com quatro palavras\"" }
        fun notas(n: Int) = (1..n).joinToString(" ") { "nota[^$it]" }
        fun referencias(n: Int) =
            "Texto.\n\n## Referencias\n" + (1..n).joinToString("\n") { "SILVA, Ana. Obra $it. Rio: Editora, 2020." }
        for (gerar in listOf(::citacoes, ::aspas, ::notas, ::referencias)) {
            assertFalse(AuditoriaAbnt.excedeCapacidade(gerar(500)), gerar(1))
            assertTrue(AuditoriaAbnt.excedeCapacidade(gerar(501)), gerar(1))
        }
        val resultado = auditar(citacoes(501), "protocol-sha256", AuditoriaAbnt.manifestoVazio("protocol-sha256"))
        assertTrue(resultado.temBloqueio("citation_capacity_exceeded"))
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
    }

    @Test
    fun `claim_id mede posicao em bytes UTF-8 como o Rust`() {
        // "Ação " tem 5 unidades UTF-16 e 7 bytes UTF-8.
        val citacao = AuditoriaAbnt.citacoesBrutas("Ação (Silva, 2020).").single()
        assertEquals(TextoRust.sha256("7|20|(Silva, 2020)"), citacao.claimId)
        assertNotEquals(TextoRust.sha256("5|18|(Silva, 2020)"), citacao.claimId)
    }

    @Test
    fun `espaco Unicode dentro da citacao casa como no Rust`() {
        // O `\s` do Rust inclui o NBSP; o da JVM sem classe explícita, não.
        val citacao = AuditoriaAbnt.citacoesBrutas("Ver (Silva,\u00A02020).").single()
        assertEquals("2020", citacao.ano)
    }

    @Test
    fun `digito nao ASCII no ano casa como no Rust`() {
        // O `\d` do Rust é `\p{Nd}`: "19٣٣" é detectado como citação, e só
        // depois o ano é recusado por não ser ASCII.
        val citacao = AuditoriaAbnt.citacoesBrutas("Ver (Silva, 19٣٣).").single()
        assertEquals("19٣٣", citacao.ano)
        assertFalse(AuditoriaAbnt.anoValido(citacao.ano))
    }

    @Test
    fun `retorno de carro sozinho nao termina a linha do cabecalho`() {
        // No Rust, `(?m)$` só casa antes de `\n`: sem ele, o cabeçalho não é
        // achado e a seção de referências fica vazia.
        val resultado = auditar("Texto (Silva, 2020).\n\n## Referencias\rSILVA, Ana. Obra. Rio: Ed, 2020.")
        assertTrue(resultado.temBloqueio("reference_section_missing"))
    }

    @Test
    fun `linhas segue o lines do Rust`() {
        assertEquals(listOf("a", "b"), TextoRust.linhas("a\r\nb\n"))
        assertEquals(listOf("a\rb"), TextoRust.linhas("a\rb"))
        assertEquals(listOf("a", ""), TextoRust.linhas("a\n\n"))
        assertEquals(emptyList(), TextoRust.linhas(""))
        assertEquals(listOf("a\r"), TextoRust.linhas("a\r"))
    }

    @Test
    fun `fronteira de palavra segue o Rust`() {
        // O ZWJ (U+200D) é `\p{Join_Control}`, caractere de palavra no `\w` do
        // Rust: "apud" seguido dele não termina em fronteira, e não é sinal de
        // citação. O `\b` do JDK 17 o trata como não palavra.
        for (colado in listOf("‍", "́", "ः")) {
            val resultado = auditar("Texto apud$colado algo.", "h", AuditoriaAbnt.manifestoVazio("h"))
            assertFalse(
                resultado.temBloqueio("unstructured_citation_signal"),
                "U+%04X colado não deveria formar fronteira".format(colado.codePointAt(0)),
            )
        }
        val comFronteira = auditar("Texto apud algo.", "h", AuditoriaAbnt.manifestoVazio("h"))
        assertTrue(comFronteira.temBloqueio("unstructured_citation_signal"))
    }

    @Test
    fun `a classe de espaco da regex e a mesma do EspacoUnicode`() {
        val classe = Regex(TextoRust.ESPACO)
        var divergencias = 0
        var pontoDeCodigo = 0
        while (pontoDeCodigo <= Character.MAX_CODE_POINT) {
            if (pontoDeCodigo !in 0xD800..0xDFFF) {
                val casa = classe.matches(String(Character.toChars(pontoDeCodigo)))
                if (casa != EspacoUnicode.ehEspaco(pontoDeCodigo)) divergencias++
            }
            pontoDeCodigo++
        }
        assertEquals(0, divergencias)
    }

    @Test
    fun `a classe de palavra segue o w do Rust`() {
        val palavra = Regex("[${TextoRust.PALAVRA_CLASSE}]")
        for (sim in listOf("a", "ç", "漢", "́", "٣", "_", "‍")) {
            assertTrue(palavra.matches(sim), "deveria ser palavra: U+%04X".format(sim.codePointAt(0)))
        }
        for (nao in listOf(".", " ", "-", "\u00A0", "’")) {
            assertFalse(palavra.matches(nao), "não deveria ser palavra: U+%04X".format(nao.codePointAt(0)))
        }
    }

    @Test
    fun `saneamento troca controle por espaco e corta em pontos de codigo`() {
        assertEquals("real line INJECTED forged line ", Saneamento.texto("real line\nINJECTED forged\tline\r", 200))
        assertEquals("Authorization: Bearer <redacted>", Saneamento.texto("Authorization: Bearer pplx-test-secret-value", 200))
        // Um emoji é um ponto de código e duas unidades UTF-16.
        assertEquals("😀a", Saneamento.texto("😀ab", 2))
        assertEquals("ab-c", Saneamento.curto("a b-ç<c", 20))
    }

    @Test
    fun `JSON escrito com o escape do serde_json`() {
        val json = JsonRust().objeto {
            campo("a", "x\"y\\z\n\t\u0001/ç\u007F")
            campo("b", null as String?)
            campo("c", true)
        }.toString()
        assertEquals("{\"a\":\"x\\\"y\\\\z\\n\\t\\u0001/ç\u007F\",\"b\":null,\"c\":true}", json)
    }

    @Test
    fun `ordem de texto segue a do BTreeSet do Rust`() {
        val conjunto = TreeSet(OrdemRust)
        conjunto += "～"
        conjunto += "😀"
        // Em ponto de código U+FF5E vem antes de U+1F600; em UTF-16, depois.
        assertEquals(listOf("～", "😀"), conjunto.toList())
    }

    @Test
    fun `data no formato rfc3339 do chrono`() {
        assertEquals("2026-09-24T12:00:00+00:00", TextoRust.rfc3339(Instant.parse("2026-09-24T12:00:00Z")))
        assertEquals("2026-09-24T12:00:00.120+00:00", TextoRust.rfc3339(Instant.parse("2026-09-24T12:00:00.12Z")))
        assertEquals(
            "2026-09-24T12:00:00.000001+00:00",
            TextoRust.rfc3339(Instant.parse("2026-09-24T12:00:00.000001Z")),
        )
    }

    @Test
    fun `audit_id depende do texto do hash e do manifesto`() {
        val semManifesto = auditar("Texto.", "h")
        val comManifesto = auditar("Texto.", "h", AuditoriaAbnt.manifestoVazio("h"))
        assertEquals(TextoRust.sha256("Texto.h"), semManifesto.auditId)
        assertNotEquals(semManifesto.auditId, comManifesto.auditId)
    }

    @Test
    fun `texto acima do limite e recusado sem auditar`() {
        val saida = AuditoriaAbnt.auditar("a".repeat(2_000_001), null, null, null, agora)
        assertEquals(
            AuditoriaAbnt.Saida.Recusada("citation audit input exceeds the safe text limit"),
            saida,
        )
    }
}
