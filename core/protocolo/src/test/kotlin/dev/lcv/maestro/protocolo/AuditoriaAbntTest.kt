package dev.lcv.maestro.protocolo

import java.text.Normalizer
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
    fun `o vinculo do manifesto vale na auditoria, na ordem e com as mensagens de validate_manifest`() {
        // As quatro regras que não dependem do texto, que a sessão também confere antes de pagar o
        // rascunho (`bloqueiosDoVinculo`): a auditoria continua a dá-las, e na mesma ordem.
        val texto = "Texto autoral sem citacao."
        val vinculado = auditar(texto, "protocol-sha256", manifestoVerificado().copy(citacoes = emptyList(), fontes = emptyList()))
        assertFalse(vinculado.bloqueios.any { it.codigo.startsWith("manifest_") || it.codigo == "protocol_hash_mismatch" })

        val desvinculado = auditar(texto, "outro-hash", manifestoVerificado().copy(versaoDoEsquema = "outro", hashDoProtocolo = "", citacoes = emptyList(), fontes = emptyList()))
        assertEquals(
            listOf("manifest_schema_invalid", "protocol_hash_mismatch", "manifest_protocol_hash_missing"),
            desvinculado.bloqueios.map { it.codigo }.filter { it.startsWith("manifest_") || it == "protocol_hash_mismatch" },
        )
        assertEquals(
            listOf("manifest_schema_invalid", "protocol_hash_mismatch", "manifest_protocol_hash_missing"),
            AuditoriaAbnt.bloqueiosDoVinculo("outro-hash", manifestoVerificado().copy(versaoDoEsquema = "outro", hashDoProtocolo = "")).map { it.codigo },
        )
        val cheio = manifestoVerificado().let { it.copy(citacoes = List(501) { _ -> it.citacoes.first() }) }
        assertEquals(listOf("manifest_capacity_exceeded"), AuditoriaAbnt.bloqueiosDoVinculo("protocol-sha256", cheio).map { it.codigo })
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
        // O HTML cru também: acima do limite, a capacidade reprova, e o leitor
        // de HTML para em 500 bloqueios como os outros leitores.
        fun html(n: Int) = "Texto " + (1..n).joinToString(" ") { "<br>" }
        for (gerar in listOf(::citacoes, ::aspas, ::notas, ::referencias, ::html)) {
            assertFalse(AuditoriaAbnt.excedeCapacidade(gerar(500)), gerar(1))
            assertTrue(AuditoriaAbnt.excedeCapacidade(gerar(501)), gerar(1))
        }
        val resultado = auditar(citacoes(501), "protocol-sha256", AuditoriaAbnt.manifestoVazio("protocol-sha256"))
        assertTrue(resultado.temBloqueio("citation_capacity_exceeded"))
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        val comHtml = auditar(html(501))
        assertTrue(comHtml.temBloqueio("citation_capacity_exceeded"))
        assertEquals(500, comHtml.bloqueios.count { it.codigo == "raw_html_in_final_text" })
    }

    private fun comOriginal(original: String): ManifestoDeCitacoes = manifestoVerificado().let { base ->
        base.copy(citacoes = listOf(base.citacoes[0].copy(textoOriginal = original)))
    }

    @Test
    fun `texto que dobra para vazio nunca conta como presente`() {
        // Divergência do canônico: lá `contains("")` era verdadeiro para
        // qualquer texto, e o que dobrava para vazio passava por presente.
        val semCitacao = "Texto sem a citacao.\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        // Citação do manifesto ausente do texto, com `original_text` ".".
        assertTrue(auditar(semCitacao, "protocol-sha256", comOriginal(".")).temBloqueio("manifest_citation_absent_from_text"))
        // Misturado com ASCII, o dobrado guardava só a parte ASCII: a citação
        // `(Ωμέγα, 2020)` dobrava para `2020`, e a bibliografia com esse ano
        // a dava por presente. Só a chave canônica vale quando o dobramento
        // descarta alguma letra.
        val referencia2020 = "\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2020."
        assertTrue(
            auditar("Texto sem citacao.$referencia2020", "protocol-sha256", comOriginal("(Ωμέγα, 2020)"))
                .temBloqueio("manifest_citation_absent_from_text"),
        )
        assertFalse(
            auditar("Texto (Ωμέγα, 2020).$referencia2020", "protocol-sha256", comOriginal("(Ωμέγα, 2020)"))
                .temBloqueio("manifest_citation_absent_from_text"),
        )
        // Aspa seguida só de "." não fica ligada à citação de texto ".".
        val aspa = "“Trecho direto com mais de quatro palavras”.\n\n## Referencias\n" +
            "SILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        assertTrue(auditar(aspa, "protocol-sha256", comOriginal(".")).temBloqueio("direct_quote_without_citation"))
        // Nota de rodapé cujo marcador dobra para vazio.
        val comNota = textoVerificado.replace("(Silva, 2026, p. 12).", "(Silva, 2026, p. 12). Nota[^*].")
        assertTrue(auditar(comNota, "protocol-sha256", manifestoVerificado()).temBloqueio("unstructured_citation_signal"))
        // Autor que o dobramento esvazia não casa com a referência de outro
        // autor, e casa com a própria pela chave sem dobrar.
        assertTrue(
            auditar("Texto (Ωμέγα, 2020).\n\n## Referencias\nSILVA, Ana. Obra. Rio: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        assertFalse(
            auditar("Texto (Ωμέγα, 2020).\n\n## Referencias\nΩΜΈΓΑ, Άλφα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // Letra fora do plano básico (Deseret) é um par de surrogates: é
        // letra por ponto de código, e o nome casa com a própria referência.
        assertFalse(
            auditar("Texto (\uD801\uDC00\uD801\uDC29\uD801\uDC32\uD801\uDC34, 2020).\n\n## Referencias\n" +
                "\uD801\uDC00\uD801\uDC29\uD801\uDC32\uD801\uDC34, X. Obra. Salt Lake: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // Vários autores: o primeiro, com quatro letras ou mais, basta para
        // casar, medido na representação em que é comparado. O Rust media o
        // dobrado, e um nome grego tinha comprimento zero.
        assertFalse(
            auditar("Texto (Ωμέγα e Άλφα, 2020).\n\n## Referencias\nΩΜΈΓΑ, Άλφα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // Controles: primeiro autor de três letras não basta, grego ou ASCII,
        // embora a chave inteira, que não tem mínimo, case como no ASCII; e o
        // primeiro autor grego não casa com a referência de outro.
        assertFalse(
            auditar("Texto (Ωμέ, 2020).\n\n## Referencias\nΩΜΈ, Άλφα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        assertTrue(
            auditar("Texto (Ωμέ e Άλφα, 2020).\n\n## Referencias\nΩΜΈ, Άλφα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // A pontuação não vale por letra no mínimo: `Ω-μέ` tem três letras.
        assertTrue(
            auditar("Texto (Ω-μέ e Άλφα, 2020).\n\n## Referencias\nΩ-ΜΈ, Άλφα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        assertTrue(
            auditar("Texto (Sil e Alfa, 2020).\n\n## Referencias\nSIL, Alfa. Obra. Rio: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        assertTrue(
            auditar("Texto (Ωμέγα e Άλφα, 2020).\n\n## Referencias\nΒΉΤΑ, Γάμμα. Obra. Atenas: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // Autor ASCII de outro sobrenome, no mesmo ano, também não casa.
        assertTrue(
            auditar("Texto (Souza, 2020).\n\n## Referencias\nSILVA, Ana. Obra. Rio: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
        // Controles: com a citação no texto, nada disso bloqueia.
        val controle = auditar(textoVerificado, "protocol-sha256", comOriginal("."))
        assertFalse(controle.temBloqueio("manifest_citation_absent_from_text"))
        assertFalse(controle.temBloqueio("direct_quote_without_citation"))
        assertFalse(
            auditar(textoVerificado, "protocol-sha256", manifestoVerificado()).temBloqueio("unstructured_citation_signal"),
        )
        assertFalse(
            auditar("Texto (Silva, 2020).\n\n## Referencias\nSILVA, Ana. Obra. Rio: Editora, 2020.")
                .temBloqueio("citation_without_reference"),
        )
    }

    @Test
    fun `autores diferentes que dobram para vazio nao sao o mesmo autor`() {
        // Dois nomes gregos dobram os dois para vazio; pela regra do texto que
        // dobra para vazio, isso não os torna iguais.
        val texto = "Texto (Ωμέγα, 2026, p. 12).\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        fun comChave(chave: String) = manifestoVerificado().let { base ->
            base.copy(
                citacoes = listOf(
                    base.citacoes[0].copy(
                        autorExibido = "Ωμέγα, Α.",
                        chaveDoAutor = chave,
                        textoOriginal = "(Ωμέγα, 2026, p. 12)",
                    ),
                ),
                fontes = listOf(base.fontes[0].copy(autores = listOf(AutorDaFonte("Ωμέγα, Α.", chave)))),
            )
        }
        val outro = auditar(texto, "protocol-sha256", comChave("ΑΛΦΑ"))
        for (codigo in listOf(
            "body_citation_not_in_manifest",
            "citation_canonical_author_mismatch",
            "canonical_author_display_mismatch",
        )) {
            assertTrue(outro.temBloqueio(codigo), codigo)
        }
        // Misturado com ASCII, o dobrado guarda só a parte ASCII: `Ωμέγα Bo` e
        // `Άλφα Bo` dobram os dois para `bo`, e não são o mesmo autor.
        val misto = "Texto (Ωμέγα Bo, 2026, p. 12).\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        fun comChaveMista(chave: String) = manifestoVerificado().let { base ->
            base.copy(
                citacoes = listOf(
                    base.citacoes[0].copy(
                        autorExibido = "Ωμέγα Bo, Α.",
                        chaveDoAutor = chave,
                        textoOriginal = "(Ωμέγα Bo, 2026, p. 12)",
                    ),
                ),
                fontes = listOf(base.fontes[0].copy(autores = listOf(AutorDaFonte("Ωμέγα Bo, Α.", chave)))),
            )
        }
        assertTrue(auditar(misto, "protocol-sha256", comChaveMista("ΑΛΦΑ BO")).temBloqueio("body_citation_not_in_manifest"))
        // Só pontuação: `.` e `-` dobram os dois para `""` e não são o mesmo
        // localizador; a comparação vai pela chave canônica.
        val pontuacao = "Texto (Silva, 2026, -).\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        fun comLocalizador(localizador: String) = manifestoVerificado().let { base ->
            base.copy(citacoes = listOf(base.citacoes[0].copy(localizador = localizador, textoOriginal = "(Silva, 2026, -)")))
        }
        assertTrue(auditar(pontuacao, "protocol-sha256", comLocalizador(".")).temBloqueio("body_citation_not_in_manifest"))
        assertFalse(auditar(pontuacao, "protocol-sha256", comLocalizador("-")).temBloqueio("body_citation_not_in_manifest"))
        assertFalse(
            auditar(misto, "protocol-sha256", comChaveMista(AuditoriaAbnt.chaveCanonica("Ωμέγα Bo")))
                .temBloqueio("body_citation_not_in_manifest"),
        )
        // Controle: com a chave do próprio autor, as três conferências passam.
        val proprio = auditar(texto, "protocol-sha256", comChave(AuditoriaAbnt.chaveCanonica("Ωμέγα")))
        for (codigo in listOf(
            "body_citation_not_in_manifest",
            "citation_canonical_author_mismatch",
            "canonical_author_display_mismatch",
        )) {
            assertFalse(proprio.temBloqueio(codigo), codigo)
        }
    }

    @Test
    fun `cada ocorrencia no corpo consome uma entrada propria do manifesto`() {
        // Divergência do canônico, por decisão do operador de 24/09/2026: lá
        // uma entrada cobria todas as ocorrências iguais.
        val duasVezes = textoVerificado.replace(
            "(Silva, 2026, p. 12).",
            "(Silva, 2026, p. 12). Outra afirmacao (Silva, 2026, p. 12).",
        )
        assertTrue(auditar(duasVezes, "protocol-sha256", manifestoVerificado()).temBloqueio("body_citation_not_in_manifest"))
        // Controle: com duas entradas, uma para cada afirmação, passa.
        val duasEntradas = manifestoVerificado().let { base ->
            base.copy(citacoes = base.citacoes + base.citacoes[0].copy(claimId = "claim-2"))
        }
        val resultado = auditar(duasVezes, "protocol-sha256", duasEntradas)
        assertFalse(resultado.temBloqueio("body_citation_not_in_manifest"), resultado.bloqueios.toString())
        assertEquals(StatusDoParMaestro.PRONTO, resultado.statusDoParMaestro, resultado.bloqueios.toString())
    }

    @Test
    fun `citacao dentro da secao de referencias nao consome a entrada do corpo`() {
        // Um título com a mesma forma da citação do corpo não pede entrada a
        // mais: só a ocorrência do corpo consome entrada do manifesto.
        val comTitulo = textoVerificado.replace("SILVA, Maria. Obra.", "SILVA, Maria. Obra (Silva, 2026, p. 12).")
        assertFalse(auditar(comTitulo, "protocol-sha256", manifestoVerificado()).temBloqueio("body_citation_not_in_manifest"))
        // Controle: nas referências, citação sem entrada nenhuma continua
        // bloqueando, como no canônico.
        val semEntrada = textoVerificado.replace("SILVA, Maria. Obra.", "SILVA, Maria. Obra (Souza, 2020).")
        assertTrue(auditar(semEntrada, "protocol-sha256", manifestoVerificado()).temBloqueio("body_citation_not_in_manifest"))
        // A seção termina no próximo cabeçalho de nível igual ou menor:
        // citação num apêndice de mesmo nível depois dela é do corpo e consome
        // entrada própria.
        val comApendice = "$textoVerificado\n\n## Apendice\nOutra afirmacao (Silva, 2026, p. 12)."
        assertTrue(auditar(comApendice, "protocol-sha256", manifestoVerificado()).temBloqueio("body_citation_not_in_manifest"))
        val duasEntradas = manifestoVerificado().let { base ->
            base.copy(citacoes = base.citacoes + base.citacoes[0].copy(claimId = "claim-2"))
        }
        assertFalse(auditar(comApendice, "protocol-sha256", duasEntradas).temBloqueio("body_citation_not_in_manifest"))
    }

    /**
     * O manifesto verificado com uma citação indireta sem localizador para cada `original_text` — `claim-1`, `claim-2`
     * e assim por diante, todas da mesma fonte —, a forma da prova da auditoria do Codex de 08/10/2026.
     */
    private fun comCitacoesIndiretas(vararg originais: String?): ManifestoDeCitacoes = manifestoVerificado().let { base ->
        base.copy(
            citacoes = originais.mapIndexed { indice, original ->
                base.citacoes[0].copy(
                    claimId = "claim-${indice + 1}",
                    tipo = TipoDeCitacao.CITACAO_INDIRETA,
                    localizador = null,
                    textoOriginal = original,
                )
            },
        )
    }

    private fun ResultadoAbnt.bloqueiosPorClaim() = bloqueios.map { it.codigo to it.claimId }

    @Test
    fun `original que so existe na bibliografia nao localiza a citacao do manifesto`() {
        // Achado da auditoria do Codex de 08/10/2026, com os casos da prova
        // dele: `SILVA`, que só existe no autor da referência, dava por
        // localizada uma citação que o corpo não faz, e o par saía pronto.
        val referencias = "\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        val semCitacao = "Texto autoral sem citacao.$referencias"
        val soNaBibliografia = auditar(semCitacao, "protocol-sha256", comCitacoesIndiretas("SILVA"))
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), soNaBibliografia.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, soNaBibliografia.statusDoParMaestro)
        // Controles, os outros três casos da prova: a citação no corpo está
        // pronta; o original exato ausente e a fonte sem citação nenhuma
        // continuam recusados.
        val noCorpo = auditar("Texto autoral (Silva, 2026).$referencias", "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)"))
        assertEquals(StatusDoParMaestro.PRONTO, noCorpo.statusDoParMaestro, noCorpo.bloqueios.toString())
        assertEquals(
            listOf("manifest_citation_absent_from_text"),
            auditar(semCitacao, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)")).bloqueios.map { it.codigo },
        )
        assertEquals(
            listOf("reference_without_body_use"),
            auditar(semCitacao, "protocol-sha256", comCitacoesIndiretas()).bloqueios.map { it.codigo },
        )
        // Controle do que fica como estava: sem `original_text`, que é
        // opcional, vale a forma normalizada no corpo.
        val semOriginal = auditar("Texto autoral (Silva, 2026).$referencias", "protocol-sha256", comCitacoesIndiretas(null))
        assertEquals(StatusDoParMaestro.PRONTO, semOriginal.statusDoParMaestro, semOriginal.bloqueios.toString())
        // A forma da citação num título de referência já não a localiza. Este
        // caso saía PRONTO porque a forma normalizada era procurada no texto
        // inteiro, como no canônico e como o plano do Codex mandava manter. Por
        // decisão do operador de 09/10/2026, ela também só vale no corpo, e a
        // citação que só aparece no título é dada por ausente do texto
        // (`manifest_citation_absent_from_text`). O título com forma de
        // citação continua sem consumir entrada do manifesto.
        val noTitulo = comCitacoesIndiretas("Silva (2026)").let { base ->
            base.copy(fontes = listOf(base.fontes[0].copy(titulo = "Obra sobre Silva (2026)")))
        }
        val titulo = auditar(
            "Texto autoral sem citacao.\n\n## Referencias\nSILVA, Maria. Obra sobre Silva (2026). Sao Paulo: Editora, 2026.",
            "protocol-sha256",
            noTitulo,
        )
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), titulo.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, titulo.statusDoParMaestro)
    }

    @Test
    fun `o original vale no corpo dos dois lados da secao de referencias, sem juntar as partes`() {
        // O apêndice de mesmo nível depois da seção é corpo: o original que só
        // está nele localiza a citação, como o que só está antes dela. As duas
        // partes são conferidas cada uma por si: juntas, o fim de uma
        // ("Brasil") e o começo da outra ("Vantagens") dobrariam para
        // `brasilvantagens`, com um `silva` que o texto só tem na bibliografia.
        val texto = "Texto autoral sobre o Brasil.\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.\n\n" +
            "## Vantagens\nA autora descreve a obra como inaugural."
        val resultado = auditar(
            texto,
            "protocol-sha256",
            comCitacoesIndiretas("SILVA", "Texto autoral sobre o Brasil", "descreve a obra como inaugural"),
        )
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), resultado.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        // Controle: sem nenhuma seção do aparato, o texto inteiro é corpo.
        val semSecao = auditar("Texto autoral sobre o Brasil.", "protocol-sha256", comCitacoesIndiretas("sobre o Brasil"))
        assertFalse(semSecao.temBloqueio("manifest_citation_absent_from_text"), semSecao.bloqueios.toString())
    }

    @Test
    fun `a secao de referencias e cortada no texto, antes do dobramento que muda o comprimento`() {
        // Antes do cabeçalho, letras cujo comprimento muda: `İ` vira `i` mais
        // um ponto combinante na caixa baixa do dobramento, `ß` vira `SS` na
        // caixa alta da chave canônica, e o acento decomposto (NFD) é uma
        // unidade que o dobramento descarta. Nem o dobrado nem a chave guardam
        // as posições do texto: a faixa é medida e cortada no texto, e só
        // então cada parte é dobrada. A bibliografia fica de fora, e os
        // originais do corpo, até o último trecho antes do cabeçalho, são
        // achados pelos dois caminhos da comparação.
        val decomposto = Normalizer.normalize("Praça São João da Conceição", Normalizer.Form.NFD)
        val texto = "Campo feito em İzmir e İstanbul, perto da Straße, e na $decomposto.\n\n## Referencias\n" +
            "SILVA, Maria. Obra. Sao Paulo: Editora, 2026."
        val resultado = auditar(
            texto,
            "protocol-sha256",
            comCitacoesIndiretas("SILVA", "İzmir e İstanbul", "perto da Straße", decomposto),
        )
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), resultado.bloqueiosPorClaim())
    }

    @Test
    fun `original que so existe nas leituras complementares nao localiza a citacao do manifesto`() {
        // Decisão do operador de 09/10/2026: as fontes consultáveis online e as
        // leituras complementares saem do corpo, como a seção de referências, e
        // uma obra só recomendada para leitura deixa de valer como citada.
        // `SILVA`, que fora da seção de referências só existe numa leitura
        // complementar, não localiza a citação, e as leituras não são lidas
        // como referências.
        val texto = "Texto autoral sem citacao.\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.\n\n" +
            "## Leituras complementares\nSILVA, Maria. Outra obra. Rio: Editora, 2025."
        val resultado = auditar(texto, "protocol-sha256", comCitacoesIndiretas("SILVA"))
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), resultado.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        // Sem seção de referências, a leitura complementar também fica fora do
        // corpo.
        val semReferencias = auditar(
            "Texto autoral sem citacao.\n\n## Leituras complementares\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.",
            "protocol-sha256",
            comCitacoesIndiretas("SILVA"),
        )
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), semReferencias.bloqueiosPorClaim())
        // Controle: com a citação no corpo, o mesmo texto está pronto.
        val noCorpo = auditar(
            texto.replace("sem citacao.", "(Silva, 2026)."),
            "protocol-sha256",
            comCitacoesIndiretas("(Silva, 2026)"),
        )
        assertEquals(StatusDoParMaestro.PRONTO, noCorpo.statusDoParMaestro, noCorpo.bloqueios.toString())
    }

    @Test
    fun `o cabecalho das fontes online e reconhecido dobrado, em qualquer nivel`() {
        // O nome do cabeçalho é dobrado como na regra de ordem do aparato: o
        // acento, a caixa, o nível, o recuo com espaço Unicode (NBSP) e o
        // fim de linha CRLF não mudam a seção. Sem seção de referências no
        // texto, cada cabeçalho é reconhecido por si, e não como subtítulo
        // dela.
        val textos = listOf(
            "## Fontes consultáveis online",
            "### Fontes consultadas online",
            "# FONTES ONLINE",
            "${Char(0xA0)}###### Fontes Online",
        ).map { "Texto autoral sem citacao.\n\n$it\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026." }
        val esperado = listOf("manifest_citation_absent_from_text" to "claim-1")
        for (texto in textos + textos[0].replace("\n", "\r\n")) {
            val resultado = auditar(texto, "protocol-sha256", comCitacoesIndiretas("SILVA"))
            assertEquals(esperado, resultado.bloqueiosPorClaim(), texto)
        }
    }

    @Test
    fun `apendice de nivel igual ou menor continua corpo, sem juntar as partes`() {
        // Uma seção com outro cabeçalho de nível igual ou menor, como um
        // apêndice, fecha a seção do aparato e é corpo, depois do aparato ou
        // entre as seções dele: o original que só está nela localiza a
        // citação. As partes do corpo são conferidas cada uma por si: juntas,
        // o fim da que vem antes do aparato ("Brasil") e o começo do apêndice
        // ("Vantagens") dobrariam para `brasilvantagens`, com um `silva` que o
        // texto só tem no aparato.
        val referencias = "\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.\n\n"
        val leituras = "## Leituras complementares\nSILVA, Maria. Outra obra. Rio: Editora, 2025.\n\n"
        val manifesto = comCitacoesIndiretas("SILVA", "descreve a obra como inaugural")
        val esperado = listOf("manifest_citation_absent_from_text" to "claim-1")
        for (cabecalho in listOf("## Vantagens", "# Vantagens")) {
            val apendice = "$cabecalho\nA autora descreve a obra como inaugural.\n\n"
            for (texto in listOf(
                "Texto autoral sobre o Brasil.$referencias$leituras$apendice",
                "Texto autoral sobre o Brasil.$referencias$apendice$leituras",
            )) {
                val resultado = auditar(texto, "protocol-sha256", manifesto)
                assertEquals(esperado, resultado.bloqueiosPorClaim(), texto)
                assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro, texto)
            }
        }
        // Controle: a linha de prosa com o nome de uma seção do aparato, sem
        // `#`, não é cabeçalho, e o apêndice continua corpo.
        val prosa = "Texto autoral sem citacao.$referencias" +
            "## Apendice\nLeituras complementares\nA obra de SILVA e inaugural."
        assertEquals(emptyList(), auditar(prosa, "protocol-sha256", comCitacoesIndiretas("SILVA")).bloqueiosPorClaim())
    }

    @Test
    fun `citacao nas leituras complementares nao consome a entrada do corpo`() {
        // A regra de 24/09/2026 usa o mesmo corte do original (decisão do
        // operador de 09/10/2026): a mesma citação no corpo e numa leitura
        // complementar pede uma entrada só, e a das leituras só precisa estar
        // representada.
        val texto = "Texto autoral (Silva, 2026).\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026." +
            "\n\n## Leituras complementares\nSILVA, Maria. Outra obra (Silva, 2026). Rio: Editora, 2025."
        val resultado = auditar(texto, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)"))
        assertEquals(emptyList(), resultado.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.PRONTO, resultado.statusDoParMaestro)
        // Controles: nas leituras, citação sem entrada nenhuma continua
        // bloqueando, como nas referências.
        val semEntrada = texto.replace("Outra obra (Silva, 2026)", "Outra obra (Souza, 2020)")
        val souza = AuditoriaAbnt.citacoesBrutas(semEntrada).single { it.autorExibido == "Souza" }.claimId
        assertEquals(
            listOf("body_citation_not_in_manifest" to souza),
            auditar(semEntrada, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)")).bloqueiosPorClaim(),
        )
        // A seção termina no próximo cabeçalho de nível igual ou menor: a
        // citação num apêndice depois dela é do corpo e consome entrada
        // própria. Qual das duas do corpo fica sem entrada depende da ordem dos
        // `claim_id`, e por isso só o código é conferido.
        val comApendice = "$texto\n\n## Apendice\nOutra afirmacao (Silva, 2026)."
        assertEquals(
            listOf("body_citation_not_in_manifest"),
            auditar(comApendice, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)")).bloqueios.map { it.codigo },
        )
        val duasEntradas = auditar(
            comApendice,
            "protocol-sha256",
            comCitacoesIndiretas("(Silva, 2026)", "(Silva, 2026)"),
        )
        assertEquals(emptyList(), duasEntradas.bloqueiosPorClaim(), duasEntradas.bloqueios.toString())
    }

    @Test
    fun `subtitulo dentro das leituras complementares continua no aparato`() {
        // Decisão do operador de 09/10/2026: cada seção do aparato vai até o
        // próximo cabeçalho de nível igual ou menor, e os subtítulos ficam
        // dentro. A entrada sob `### Livros` continua fora do corpo, para o
        // original e para a regra de 24/09/2026; o apêndice do mesmo nível das
        // leituras fecha a seção e é corpo.
        val texto = "Texto autoral sem citacao.\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.\n\n" +
            "## Leituras complementares\n### Livros\nSILVA, Maria. Outra obra. Rio: Editora, 2025.\n\n" +
            "## Apendice\nA autora descreve a obra como inaugural."
        val manifesto = comCitacoesIndiretas("SILVA", "descreve a obra como inaugural")
        val resultado = auditar(texto, "protocol-sha256", manifesto)
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), resultado.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        // A citação sob o subtítulo só precisa estar representada: com a mesma
        // citação no corpo, uma entrada basta.
        val citado = texto.replace("sem citacao.", "(Silva, 2026).").replace("Outra obra.", "Outra obra (Silva, 2026).")
        val umaEntrada = auditar(citado, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)"))
        assertEquals(emptyList(), umaEntrada.bloqueiosPorClaim(), umaEntrada.bloqueios.toString())
        assertEquals(StatusDoParMaestro.PRONTO, umaEntrada.statusDoParMaestro)
    }

    @Test
    fun `subtitulo dentro das referencias continua no aparato, e a lista de referencias e lida como antes`() {
        // A seção de referências também vai até o próximo cabeçalho de nível
        // igual ou menor, no corte do corpo e na regra de 24/09/2026 (decisão
        // do operador de 09/10/2026): `SILVA`, que fora da lista de
        // referências só existe sob o subtítulo `### Outras obras`, ainda
        // dentro da seção, não localiza a citação. A lista de referências
        // continua lida como no canônico, até o primeiro cabeçalho de qualquer
        // nível: a linha sob o subtítulo não é lida, e por isso não sai
        // `reference_not_in_manifest` por ela.
        val texto = "Texto autoral sem citacao.\n\n## Referencias\nSILVA, Maria. Obra. Sao Paulo: Editora, 2026.\n\n" +
            "### Outras obras\nSILVA, Maria. Outra obra. Rio: Editora, 2025."
        assertEquals(
            listOf("SILVA, Maria. Obra. Sao Paulo: Editora, 2026."),
            AuditoriaAbnt.secaoDeReferencias(texto).map { it.texto },
        )
        val resultado = auditar(texto, "protocol-sha256", comCitacoesIndiretas("SILVA"))
        assertEquals(listOf("manifest_citation_absent_from_text" to "claim-1"), resultado.bloqueiosPorClaim())
        assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro)
        // A citação sob o subtítulo só precisa estar representada: com a mesma
        // citação no corpo, uma entrada basta.
        val citado = texto.replace("sem citacao.", "(Silva, 2026).").replace("Outra obra.", "Outra obra (Silva, 2026).")
        val umaEntrada = auditar(citado, "protocol-sha256", comCitacoesIndiretas("(Silva, 2026)"))
        assertEquals(emptyList(), umaEntrada.bloqueiosPorClaim(), umaEntrada.bloqueios.toString())
        assertEquals(StatusDoParMaestro.PRONTO, umaEntrada.statusDoParMaestro)
    }

    @Test
    fun `forma normalizada que so existe no aparato nao localiza a citacao do manifesto`() {
        // Decisão do operador de 09/10/2026, contra o plano do Codex, que
        // mandava manter a forma normalizada procurada no texto inteiro: ela
        // também só localiza a citação no corpo. Quando o autor é também a
        // editora, a referência traz `IBGE, 2023`, que dobra como a forma
        // `(IBGE, 2023)` da citação e a dava por localizada sem citação
        // nenhuma no corpo, com ou sem `original_text`.
        fun ibge(original: String?) = manifestoVerificado().let { base ->
            base.copy(
                citacoes = listOf(
                    base.citacoes[0].copy(
                        tipo = TipoDeCitacao.CITACAO_INDIRETA,
                        autorExibido = "IBGE",
                        chaveDoAutor = "IBGE",
                        ano = "2023",
                        localizador = null,
                        textoOriginal = original,
                    ),
                ),
                fontes = listOf(
                    base.fontes[0].copy(
                        autores = listOf(AutorDaFonte("IBGE", "IBGE")),
                        titulo = "Censo demográfico 2022",
                        local = "Rio de Janeiro",
                        editora = "IBGE",
                        ano = "2023",
                    ),
                ),
            )
        }
        val referencias = "\n\n## Referencias\nIBGE. Censo demográfico 2022. Rio de Janeiro: IBGE, 2023."
        val esperado = listOf("manifest_citation_absent_from_text" to "claim-1")
        for (original in listOf("(IBGE, 2023)", null)) {
            val resultado = auditar("Texto autoral sem citacao.$referencias", "protocol-sha256", ibge(original))
            assertEquals(esperado, resultado.bloqueiosPorClaim(), original)
            assertEquals(StatusDoParMaestro.NAO_PRONTO, resultado.statusDoParMaestro, original)
        }
        // Controle: sem `original_text`, a forma normalizada no corpo continua
        // a localizar a citação.
        val noCorpo = auditar(
            "Segundo o censo (IBGE, 2023), a populacao cresceu.$referencias",
            "protocol-sha256",
            ibge(null),
        )
        assertEquals(emptyList(), noCorpo.bloqueiosPorClaim(), noCorpo.bloqueios.toString())
        assertEquals(StatusDoParMaestro.PRONTO, noCorpo.statusDoParMaestro)
    }

    @Test
    fun `HTML cru no texto final bloqueia a liberacao`() {
        // Decisão do operador de 25/09/2026: o texto final é Markdown sem HTML.
        // Tudo que a especificação CommonMark reconhece como HTML cru, em
        // linha (6.6) ou em bloco (4.6), é recusado, apontando o trecho.
        val citacao = "\"esta e uma citacao direta suficientemente longa\""
        for (texto in listOf(
            "Veja <a title=$citacao href=\"#x\">isto</a>.",
            "Nota<sup>1</sup> e quebra<br>.",
            "Texto <!-- comentario --> fim.",
            "<div>\n$citacao sem fonte.\n</div>",
            "<a title=$citacao>\nisto</a> e fim.",
            "Texto.\n\n<!--\ncomentario\n\n-->\n\nFim.",
            "Texto.\n\n<script>\nvar x = 1;\n</script>\n\nFim.",
            "<?xml version=\"1.0\"?>\nTexto.",
            "<!DOCTYPE html>\nTexto.",
            "Texto.\n\n   <![CDATA[\nx\n]]>\n\nFim.",
            "Texto </b> solto.",
            "A\r\nB\r\nVeja <b>x</b>.",
        )) {
            val resultado = auditar(texto)
            assertTrue(resultado.temBloqueio("raw_html_in_final_text"), texto)
            assertNotEquals(StatusDoParMaestro.PRONTO, resultado.statusDoParMaestro, texto)
        }
        // O trecho apontado é o HTML, não a prosa em volta.
        val trechos = auditar("Veja <a title=$citacao href=\"#x\">isto</a>.").bloqueios
            .filter { it.codigo == "raw_html_in_final_text" }.map { it.trecho }
        assertEquals(listOf("<a title=$citacao href=\"#x\">", "</a>"), trechos)
        // Controles: o que a especificação não lê como HTML cru segue prosa —
        // `<` solto, link automático, código em linha e bloco de código.
        for (texto in listOf(
            "Sabe-se que 2 < 3 e a < b.",
            "Veja <https://example.org/x> e <mailto:a@b.c>.",
            "Use `<b>` no codigo.",
            "```html\n<div>x</div>\n```",
            "    <div>indentado</div>",
        )) {
            assertFalse(auditar(texto).temBloqueio("raw_html_in_final_text"), texto)
        }
    }

    @Test
    fun `aspa na prosa depois de menor ou de igual e conferida`() {
        // Divergência do canônico: lá um `<` sem `>` depois, ou um `=` logo
        // antes, escondia a aspa do portão.
        for (texto in listOf(
            "Sabe-se que 2 < 3 e \"esta e uma citacao direta suficientemente longa\" sem fonte.",
            "A tese = \"esta e uma citacao direta suficientemente longa\" sem fonte.",
        )) {
            assertTrue(auditar(texto).temBloqueio("direct_quote_without_citation"), texto)
        }
        // Controle: `<` na prosa não é HTML cru.
        assertFalse(
            auditar("Sabe-se que 2 < 3 e \"esta e uma citacao direta suficientemente longa\" sem fonte.")
                .temBloqueio("raw_html_in_final_text"),
        )
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
