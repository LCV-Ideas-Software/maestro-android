package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** O formato gravado das linhas de link e dos registros de evidência: ida e volta, e `null` no que não é válido. */
class FormatoDeLinksTest {

    private val linha = LinhaDeLink(
        versaoDoEsquema = "link_evidence.v1",
        linkId = "lnk-1",
        artefatoDeOrigem = "artifact-1",
        impressaoDaOrigem = "abc",
        textoDaAncora = null,
        textoAoRedor = "a fonte \"diz\"",
        urlOriginal = "https://example.com/a?x=1",
        urlNormalizada = "https://example.com/a?x=1",
        mudancasDaNormalizacao = listOf("lowercase_host"),
        urlFinal = "https://example.com/b",
        cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/b", 301)),
        statusHttp = 200,
        tipoDeConteudo = "text/html",
        sha256 = "0".repeat(64),
        verificadoEm = "2026-09-25T12:00:00+00:00",
        sustentaAfirmacao = null,
        classificacao = ClassificacaoDoLink.REDIRECIONADO_VERIFICADO,
        classificacaoMecanica = ClassificacaoDoLink.REDIRECIONADO_VERIFICADO,
        candidatosDeCorrecao = listOf(
            CandidatoDeCorrecao("cand-1", AcaoDeCorrecao.SUBSTITUIR, "https://example.org/c", "Título", "crossref", null, "ev-9", "porque sim", "2026-09-25T12:00:01+00:00"),
        ),
        statusDaRevisao = StatusDaRevisao.PENDENTE,
        decisaoDeRevisao = DecisaoDeRevisao.QUARENTENA,
        revisadoPor = "operator",
        notaDaRevisao = "nota de revisão",
        revisadoEm = "2026-09-25T12:00:02+00:00",
        evidenciaWebId = "ev-1",
        url = "https://example.com/a?x=1",
        status = "redirected_verified",
        invalidade = "",
        tom = "warning",
    )

    private val registro = RegistroDeEvidencia(
        id = "ev-1", versaoDoEsquema = "web_evidence.v1", estado = EstadoDaEvidencia.PRONTA,
        url = "https://example.com/a", metodo = MetodoHttp.GET, modoDeAcesso = ModoDeAcesso.COLETA_HTTP, status = 200,
        urlFinal = "https://example.com/b", titulo = "Título", tipoDeConteudo = "text/html", sha256 = "1".repeat(64),
        coletadaEm = "2026-09-25T12:00:00+00:00", expiraEm = null, validadeDoCache = "P30D", estadoDoCache = EstadoDoCache.FRESCO,
        estadoDoRobots = EstadoDoRobots.PERMITIDO, estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
        estadoDeInteracao = EstadoDeInteracao.NENHUMA, resolvidaPorPessoa = false, bytes = 10, duracaoMs = 5,
        cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/b", 302)), comandoCurl = null, provedor = null,
        consulta = null, nomeDoArtefato = null, notas = listOf("n1"), criadaEm = "2026-09-25T12:00:00+00:00",
        atualizadaEm = "2026-09-25T12:00:00+00:00",
    )

    @Test
    fun `linha vai e volta com as chaves do canonico`() {
        val texto = FormatoDeLinks.serializarLinha(linha)
        assertTrue(texto.startsWith("{\n  \"anchor_text\": null,"))
        assertTrue("\"cross_review_status\": \"pending\"" in texto)
        assertTrue("\"action\": \"replace\"" in texto)
        assertEquals(linha, FormatoDeLinks.lerLinha(texto))
        assertEquals(listOf(linha, linha), FormatoDeLinks.serializarLinhas(listOf(linha, linha)).let { lista ->
            val no = LeituraDoRelatorio.LEITOR.readTree(lista)
            no.map { FormatoDeLinks.lerLinha(LeituraDoRelatorio.LEITOR.writeValueAsString(it))!! }
        })
        assertEquals("[]", FormatoDeLinks.serializarLinhas(emptyList()))
    }

    @Test
    fun `linha invalida e null, campo a campo`() {
        val texto = FormatoDeLinks.serializarLinha(linha)
        assertNull(FormatoDeLinks.lerLinha(texto.dropLast(1)))
        assertNull(FormatoDeLinks.lerLinha("[]"))
        assertNull(FormatoDeLinks.lerLinha(texto.replace("\"link_id\": \"lnk-1\"", "\"link_id\": 1")))
        assertNull(FormatoDeLinks.lerLinha(texto.replace("\"classification\": \"redirected_verified\"", "\"classification\": \"other\"")))
        assertNull(FormatoDeLinks.lerLinha(texto.replace("\"action\": \"replace\"", "\"action\": \"rename\"")))
        assertNull(FormatoDeLinks.lerLinha(texto.replace("  \"tone\": \"warning\",\n", "")))
        // `200.9` cabe num Int, mas não é inteiro: o status HTTP não pode ser truncado para 200.
        assertNull(FormatoDeLinks.lerLinha(texto.replace("\"http_status\": 200", "\"http_status\": 200.9")))
        assertNull(FormatoDeLinks.lerLinha(texto.replace("\"status\": 301", "\"status\": 301.0")))
    }

    @Test
    fun `registro de evidencia vai e volta e o invalido e null`() {
        val texto = FormatoDeLinks.serializarEvidencia(registro)
        assertTrue("\"access_mode\": \"http_fetch\"" in texto)
        assertTrue("\"human_resolved\": false" in texto)
        assertEquals(registro, FormatoDeLinks.lerEvidencia(texto))
        assertNull(FormatoDeLinks.lerEvidencia(texto.replace("\"state\": \"ready\"", "\"state\": \"done\"")))
        assertNull(FormatoDeLinks.lerEvidencia(texto.replace("\"cache_ttl\": \"P30D\"", "\"cache_ttl\": 30")))
        assertNull(FormatoDeLinks.lerEvidencia("{"))
        assertNull(FormatoDeLinks.lerEvidencia(texto.replace("\"byte_count\": 10", "\"byte_count\": 10.5")))
        assertNull(FormatoDeLinks.lerEvidencia(texto.replace("\"duration_ms\": 5", "\"duration_ms\": 5.0")))
    }
}
