package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Dns

/**
 * O motor do `:core:protocolo` com o parser e a coleta reais: o que os
 * testes de lá deixaram para cá (`IntegridadeDeLinksTest.kt`,
 * `AuditoriaFinalTest.kt`) — `url_parser_normalization`, o tipo divergente
 * com um `.pdf` de verdade, `mailto:` não coletado, o bloqueio do canônico
 * (`link_audit_reports_blocked_links_with_invalidity`, `lib.rs:1376`) e a
 * recusa de esquemas, credenciais e parâmetros de credencial com o parser
 * real.
 */
class IntegridadeComRedeTest {

    private val servidor = RedeDeTeste.servidorHttps()

    @AfterTest
    fun descer() = servidor.close()

    private val registro = object : IntegridadeDeLinks.RegistroDeLinks {
        val linhas = LinkedHashMap<String, LinhaDeLink>()
        override fun <T> emTransacao(bloco: () -> T): T = synchronized(this) { bloco() }
        override fun carregar(linkId: String) = linhas[linkId]
        override fun salvar(linha: LinhaDeLink) {
            linhas[linha.linkId] = linha
        }
        override fun anotar(tipo: String, linha: LinhaDeLink) = Unit
        override fun todos() = linhas.values.toList()
    }

    private fun auditar(texto: String, politica: UrlPublica.PoliticaDeRede = RedeDeTeste.politica()) =
        IntegridadeDeLinks.auditar(
            texto,
            AnalisadorDeUrlOkHttp,
            ColetorHttp(RedeDeTeste.cliente(), Dns.SYSTEM, politica, RedeDeTeste.agente, null, { RedeDeTeste.agora }),
            registro,
        ) { RedeDeTeste.agora }

    private fun robotsEPagina(tipo: String = "text/html", corpo: String = "<html><title>Fonte</title></html>") {
        servidor.enqueue(RedeDeTeste.resposta(404, "", "Content-Type" to "text/plain"))
        servidor.enqueue(RedeDeTeste.resposta(200, corpo, "Content-Type" to tipo))
    }

    @Test
    fun `a normalizacao do parser real e registrada e o link coletado passa`() {
        robotsEPagina()
        val gritada = "HTTPS://LOCALHOST:${servidor.port}/A"
        val linha = auditar("Ver $gritada agora.").linhas.single()
        assertEquals(gritada, linha.urlOriginal)
        assertEquals("https://localhost:${servidor.port}/A", linha.urlNormalizada)
        assertTrue("url_parser_normalization" in linha.mudancasDaNormalizacao, linha.mudancasDaNormalizacao.toString())
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, linha.classificacao)
        assertEquals("warn", linha.tom)
        assertEquals("HTTP 200", linha.status)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `um caminho pdf servido como HTML e tipo divergente`() {
        robotsEPagina()
        val linha = auditar("Ver ${servidor.url("/relatorio.pdf")} agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.TIPO_DE_CONTEUDO_DIVERGENTE, linha.classificacao)
        assertEquals("error", linha.tom)
        // Controle: o mesmo caminho servido como PDF passa.
        robotsEPagina("application/pdf", "%PDF-1.7\n")
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, auditar("Ver ${servidor.url("/outro.pdf")} agora.").linhas.single().classificacao)
    }

    @Test
    fun `links bloqueados pela regra publica e mailto nao geram requisicao`() {
        val resultado = auditar("Veja http://localhost:8787/x e http://127.0.0.1/test.", RedeDeTeste.politicaReal())
        assertEquals(2, resultado.urlsEncontradas)
        // `checked` conta as linhas http (`link_integrity.rs:671`); o `checked == 0` do teste
        // legado era do `run_link_audit` antigo, que barrava antes de contar.
        assertEquals(2, resultado.verificadas)
        assertEquals(2, resultado.falhas)
        assertEquals(2, resultado.bloqueadas)
        val local = resultado.linhas.single { it.url == "http://localhost:8787/x" }
        assertEquals("blocked", local.tom)
        assertEquals(ClassificacaoDoLink.EM_QUARENTENA, local.classificacao)
        assertEquals("endereco local bloqueado por seguranca", local.invalidade)
        assertTrue(resultado.linhas.all { it.invalidade.isNotBlank() })

        val correio = auditar("Escreva para mailto:editor@example.com hoje.").linhas.single()
        assertEquals("warn", correio.tom)
        val alvos = (0 until servidor.requestCount).map { servidor.takeRequest().target }
        assertTrue(alvos.isEmpty(), alvos.toString())
    }

    @Test
    fun `esquemas nao suportados e credenciais sao malformados com o parser real`() {
        val resultado = auditar(
            "Ver javascript:alert(1), ftp://files.example.com/a.zip, tel:+5511999999999, " +
                "https://user:secret@example.com/ e https://:@example.com/ agora.",
        )
        assertEquals(5, resultado.linhas.size)
        for (linha in resultado.linhas) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("blocked", linha.tom, linha.url)
        }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `parametro de credencial e malformado com o parser real, e o segmento seguinte nao conta`() {
        // M9 (#77): `normalize_url` recusa o parâmetro de credencial antes de qualquer coleta (`link_integrity.rs:413`),
        // na query, no fragmento e no segmento do caminho que traz a chave e o valor. O segmento que vem depois de um
        // nome de credencial não é o valor dela (Discussion #94, decisão 4), e a página da `CryptoKey` é coletada.
        val base = "https://localhost:${servidor.port}"
        val recusadas = auditar(
            "Ver $base/a?access_token=secret, $base/b#access_token=secret, $base/c?utm_source=x;access_token=secret, " +
                "$base/api_key=abc/x e mailto:editor@example.com?token=abc agora.",
        ).linhas
        assertEquals(5, recusadas.size)
        for (linha in recusadas) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("parametro de credencial na URL e proibido", linha.invalidade, linha.url)
        }
        assertEquals(0, servidor.requestCount)
        // A única divergência da decodificação nativa: o canônico mantém um `%` sem dois dígitos hexadecimais e
        // aceita estas duas; o `URLDecoder` do JDK o recusa, e a regra trata "não se decodifica" como sensível (do
        // lado fechado). Só com o parser real: o `java.net.URI` dos dublês nem aceita a URL.
        val porcentoSolto = auditar("Ver $base/a?50%=1 e $base/sale/50% agora.").linhas
        assertEquals(2, porcentoSolto.size)
        for (linha in porcentoSolto) {
            assertEquals("parametro de credencial na URL e proibido", linha.invalidade, linha.url)
        }
        assertEquals(0, servidor.requestCount)

        robotsEPagina()
        val documentacao = auditar("Ver $base/docs/CryptoKey/type agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, documentacao.classificacao)
        assertEquals(2, servidor.requestCount)
        // A coleta segue a mesma regra: a palavra comum em `key` que a normalização aceita não é bloqueada
        // na rede (o canônico coleta `?monkey=1`, `web_evidence.rs:4093-4098`).
        robotsEPagina()
        val macaco = auditar("Ver $base/zoo?monkey=1 agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, macaco.classificacao, macaco.invalidade)
        assertEquals(4, servidor.requestCount)
    }

    @Test
    fun `a credencial em grafia de autoridade sem barras duplas e recusada e redigida com o parser real`() {
        // Achado do Codex no cross-review da #77 (05/10/2026): `https:/:senha@host` parseia no HttpUrl com a senha, e
        // a URL nem é coletada; a linha sai redigida, só com a origem.
        val linhas = auditar("Ver [x](https:/:review-placeholder@example.org/) e [y](https:\\\\user:pw@example.org/a).").linhas
        assertEquals(2, linhas.size)
        for (linha in linhas) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("credenciais embutidas na URL sao proibidas", linha.invalidade, linha.url)
            assertEquals("https://example.org/", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
            assertFalse(linha.urlNormalizada.contains("review-placeholder") || linha.urlNormalizada.contains("pw@"), linha.urlNormalizada)
        }
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `a credencial em grafia sem barras duplas e mascarada da ancora, do codigo e da definicao do vizinho`() {
        // Achado do Codex e do Grok no cross-review da #77 (06/10/2026): o padrão solto do canônico só casa `://`, e o
        // literal `https:/u:p@h`, que o HttpUrl lê com senha, ficava na âncora e no contexto do link vizinho.
        val base = "https://localhost:${servidor.port}"
        // Na âncora: o literal vai até o espaço e leva o `](…)` do link junto; a máscara derruba o link, e o contexto
        // cai para o redigido, com âncora nula, como em `safe_context_link`.
        robotsEPagina()
        val naAncora = auditar("[see https:/:review-placeholder@example.org/]($base/public) agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, naAncora.classificacao, naAncora.invalidade)
        assertNull(naAncora.textoDaAncora)
        assertEquals("<redacted context>", naAncora.textoAoRedor)
        // No código e na definição de referência: o vizinho guarda a âncora e o contexto, sem a credencial.
        robotsEPagina()
        val texto = "Ver [x]($base/public) e `https:\\\\u:pw@example.org/` e [r][d] agora.\n\n[d]: https:\\\\u:pw@example.org/q"
        val linhas = auditar(texto).linhas
        assertEquals(2, linhas.size)
        val publica = linhas.single { it.urlNormalizada == "$base/public" }
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, publica.classificacao, publica.invalidade)
        assertEquals("x", publica.textoDaAncora)
        assertTrue(publica.textoAoRedor.contains("Ver"), publica.textoAoRedor)
        assertFalse(publica.textoAoRedor.contains("pw@"), publica.textoAoRedor)
        val definicao = linhas.single { it.urlNormalizada != "$base/public" }
        assertEquals(ClassificacaoDoLink.MALFORMADO, definicao.classificacao)
        assertEquals("credenciais embutidas na URL sao proibidas", definicao.invalidade)
        assertEquals("https://example.org/", definicao.urlOriginal)
        assertEquals("<redacted context>", definicao.textoAoRedor)
        assertEquals(4, servidor.requestCount)
    }

    @Test
    fun `a credencial de outro esquema, de outra grafia ou de parametro recusado antes e redigida com o parser real`() {
        // Rodada 5 do cross-review da #77 (06/10/2026): `mailto://u:p@h/` (Codex), `ftp:/u:p@h` (DeepSeek) e o parâmetro
        // de credencial num esquema, ou numa URL, recusada antes por outro motivo (Grok). O `HttpUrl` lê a URL com o
        // caractere de controle codificado, e o registro guarda só a origem.
        val base = "https://localhost:${servidor.port}"
        robotsEPagina()
        val linhas = auditar(
            "[m](mailto://reader:review-placeholder@example.org/) [x](ftp:/u:p@h/z) [a](ftp://files.example.com/?access_token=valor-sintetico) " +
                "$base/api_key=valor-sintetico/x\u0001 [d]($base/public) agora.",
        ).linhas
        assertEquals(5, linhas.size)
        for (linha in linhas.take(4)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        assertEquals("credenciais embutidas na URL sao proibidas", linhas[0].invalidade)
        assertEquals("$base/", linhas[3].urlOriginal)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        for (segredo in listOf("review-placeholder", "u:p@", "valor-sintetico")) assertFalse(gravado.contains(segredo), gravado)
        val publica = linhas[4]
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, publica.classificacao, publica.invalidade)
        assertEquals("d", publica.textoDaAncora)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `a credencial que so o texto mostra e recusada e redigida com o parser real`() {
        // Rodada 6 do cross-review da #77 (06/10/2026): autoridade que o `java.net.URI` lê como "registry-based" (Codex),
        // destino entre menor e maior com espaços à volta (DeepSeek), e o destino que nenhum parser lê, com a credencial
        // depois de um prefixo ou no caminho (Grok); rodada 7: a autoridade com credencial aninhada depois de uma barra
        // (Grok). O `HttpUrl` também não lê nenhum deles.
        val base = "https://localhost:${servidor.port}"
        robotsEPagina()
        val linhas = auditar(
            "[m](mailto://reader:review-placeholder@exa_mple.org/) [x](< ftp:/u:p@h/z >) [w](< ws:\\u:p@h/ >) " +
                "[a](<prefixo https://u:p@h/>) [b](<https://exa mple.com/api_key=valor-sintetico/x>) " +
                "[n](<x https://example.com/r/https://u:p@h/>) [d]($base/public) agora.",
        ).linhas
        assertEquals(7, linhas.size)
        for (linha in linhas.take(6)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        assertEquals("credenciais embutidas na URL sao proibidas", linhas[0].invalidade)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        for (segredo in listOf("review-placeholder", "u:p@", "valor-sintetico")) assertFalse(gravado.contains(segredo), gravado)
        val publica = linhas[6]
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, publica.classificacao, publica.invalidade)
        assertEquals("d", publica.textoDaAncora)
        assertEquals(2, servidor.requestCount)
    }

    @Test
    fun `link com fragmento nao sai redirecionado com a coleta real`() {
        // M10 (#77): a coleta tira o fragmento da URL (`UrlPublica`), e a URL normalizada o mantém. A comparação
        // texto contra texto marcava todo link com `#secao` como redirecionado; a do canônico ignora o fragmento.
        robotsEPagina()
        val linha = auditar("Ver [secao](https://localhost:${servidor.port}/a#secao) agora.").linhas.single()
        assertEquals("https://localhost:${servidor.port}/a#secao", linha.urlNormalizada)
        assertEquals("https://localhost:${servidor.port}/a", linha.urlFinal)
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, linha.classificacaoMecanica)
    }
}
