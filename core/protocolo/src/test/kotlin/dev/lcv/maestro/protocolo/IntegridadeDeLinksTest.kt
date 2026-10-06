package dev.lcv.maestro.protocolo

import java.net.InetAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O motor de integridade de links e a defesa de rede. Os testes de
 * `link_integrity.rs` (linhas 1314–1813 em `16a8cff`, #77; os sete de
 * `68528f9`, linhas 976–1068, estão entre eles) e as faixas de IP do teste
 * `link_audit_blocks_local_and_private_targets` (`lib.rs`, 1341) estão aqui;
 * os que dependem do parser de URL real e da coleta ficam com eles, no
 * `:core:provedores` (`IntegridadeComRedeTest`). As regras aqui correm com
 * dublês.
 */
class IntegridadeDeLinksTest {

    private companion object {
        /** Hashes de conteúdo no formato real: 64 dígitos hexadecimais minúsculos. */
        val HASH_1 = "1".repeat(64)
        val HASH_2 = "2".repeat(64)
    }

    private val agora: Instant = Instant.parse("2026-09-24T12:00:00Z")

    /** Dublê do parser: `java.net.URI`, só para exercitar as regras em volta dele ([urlDeTeste]). */
    private val analisador = IntegridadeDeLinks.AnalisadorDeUrl { url -> urlDeTeste(url) }

    /** Registro em memória, com a mesma exclusão mútua que o do aparelho terá. */
    private class RegistroEmMemoria : IntegridadeDeLinks.RegistroDeLinks {
        val linhas = LinkedHashMap<String, LinhaDeLink>()
        val eventos = mutableListOf<Pair<String, String>>()

        /** As entradas do diário gravadas fora de uma transação: a linha poderia mudar sem elas. */
        val foraDaTransacao = mutableListOf<String>()
        private var profundidade = 0
        override fun <T> emTransacao(bloco: () -> T): T = synchronized(this) {
            profundidade++
            try {
                bloco()
            } finally {
                profundidade--
            }
        }
        override fun carregar(linkId: String): LinhaDeLink? = linhas[linkId]
        override fun salvar(linha: LinhaDeLink) {
            linhas[linha.linkId] = linha
        }
        override fun anotar(tipo: String, linha: LinhaDeLink) {
            if (profundidade == 0) foraDaTransacao += tipo
            eventos += tipo to linha.linkId
        }
        override fun todos(): List<LinhaDeLink> = linhas.values.toList()
    }

    private fun evidencia(
        url: String,
        status: Int? = 200,
        sha: String? = HASH_1,
        tipo: String? = "text/html",
        estado: EstadoDaEvidencia = EstadoDaEvidencia.PRONTA,
        interacao: EstadoDeInteracao = EstadoDeInteracao.NENHUMA,
    ) =
        RegistroDeEvidencia(
            id = "ev-${url.hashCode()}", versaoDoEsquema = "web_evidence.v1", estado = estado,
            url = url, metodo = MetodoHttp.GET, modoDeAcesso = ModoDeAcesso.COLETA_HTTP, status = status,
            urlFinal = url, titulo = null, tipoDeConteudo = tipo, sha256 = sha, coletadaEm = "2026-09-24T12:00:00+00:00",
            expiraEm = null, validadeDoCache = "P30D", estadoDoCache = EstadoDoCache.FRESCO,
            estadoDoRobots = EstadoDoRobots.PERMITIDO, estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
            estadoDeInteracao = interacao, resolvidaPorPessoa = false, bytes = 10, duracaoMs = 5,
            cadeiaDeRedirecionamento = emptyList(), comandoCurl = null, provedor = null, consulta = null,
            nomeDoArtefato = null, notas = emptyList(), criadaEm = "2026-09-24T12:00:00+00:00",
            atualizadaEm = "2026-09-24T12:00:00+00:00",
        )

    private val coletadas = mutableListOf<String>()

    /** Dublê da coleta: 200 para tudo, e a recusa do canônico para `localhost`. */
    private val coletor = IntegridadeDeLinks.ColetorDeEvidencia { url ->
        coletadas += url
        if ("localhost" in url) throw IntegridadeDeLinks.Falha("endereco local bloqueado por seguranca")
        evidencia(url)
    }

    private fun auditar(texto: String, registro: RegistroEmMemoria = RegistroEmMemoria()) =
        IntegridadeDeLinks.auditar(texto, analisador, coletor, registro) { agora }

    // ── a suíte canônica do extrator (`16a8cff.rs:1314-1409`), sobre a commonmark-java ───────────────────────

    @Test
    fun `extracao preserva a ancora do markdown e o contexto`() {
        val links = IntegridadeDeLinks.extrair("A fonte [documento oficial](https://example.com/a) sustenta a frase.")
        assertEquals(1, links.size)
        assertEquals("documento oficial", links[0].textoDaAncora)
        assertTrue(links[0].textoAoRedor.contains("sustenta a frase"))
        val semantico = IntegridadeDeLinks.extrair("Veja [fonte](<https://example.org/article?a=1&b=2>).")
        assertEquals("https://example.org/article?a=1&b=2", semantico.single().urlOriginal)
    }

    @Test
    fun `a ancora e aparada pela classe de espaco do modulo, nao pelo trim do Kotlin`() {
        // Critério da #46: uma definição só de espaço em branco no módulo (Unicode White_Space, a do `trim` do Rust).
        // O `trim()` do Kotlin não tira o U+0085 (NEL): a âncora feita só dele ficava, virava um espaço no saneamento
        // e mudava o id do link; com a classe do módulo a âncora fica vazia e sai nula, como no canônico.
        val linhas = auditar("[\u0085](https://example.com/public) e [\u0085x\u0085](https://example.com/outro)").linhas
        assertEquals(2, linhas.size)
        assertNull(linhas[0].textoDaAncora)
        assertEquals("x", linhas[1].textoDaAncora)
    }

    @Test
    fun `imagem por referencia e imagem dentro de link entram as duas na auditoria`() {
        val referencia = IntegridadeDeLinks.extrair("Veja ![grafico][img].\n\n[img]: https://example.org/chart.png")
        assertEquals(1, referencia.size)
        assertEquals("https://example.org/chart.png", referencia[0].urlOriginal)
        assertEquals("grafico", referencia[0].textoDaAncora)
        val aninhada = IntegridadeDeLinks.extrair("[![grafico](https://example.org/chart.png)](https://example.org/report)")
        assertEquals(2, aninhada.size)
        assertTrue(aninhada.any { it.urlOriginal == "https://example.org/chart.png" })
        assertTrue(aninhada.any { it.urlOriginal == "https://example.org/report" })
    }

    @Test
    fun `exemplos de codigo do markdown nao sao links vivos`() {
        // A parte de comentário HTML do teste do canônico não se porta: HTML cru é recusado antes deste portão.
        val texto = "Example: `https://example.org/token`\n\n```text\nhttps://example.org/inside\n```\n\n" +
            "    https://example.org/indented\n\n[real](https://example.org/live)"
        val links = IntegridadeDeLinks.extrair(texto)
        assertEquals(1, links.size)
        assertEquals("https://example.org/live", links[0].urlOriginal)
    }

    @Test
    fun `a extracao preserva parenteses equilibrados no destino`() {
        for (texto in listOf("[fonte](https://example.org/article(v2))", "Veja https://example.org/article(v2).")) {
            val links = IntegridadeDeLinks.extrair(texto)
            assertEquals(1, links.size, texto)
            assertEquals("https://example.org/article(v2)", links[0].urlOriginal, texto)
        }
    }

    @Test
    fun `destino entre menor e maior guarda o espaco e o parentese sem par`() {
        for (destino in listOf("https://example.org/a b", "https://example.org/a)b")) {
            val texto = "[fonte](<$destino>)"
            val links = IntegridadeDeLinks.extrair(texto)
            assertEquals(1, links.size, texto)
            // O canônico compara `Url::parse` dos dois lados; aqui a URL extraída é o próprio destino, o que é mais forte.
            assertEquals(destino, links[0].urlOriginal, texto)
        }
    }

    @Test
    fun `rabo longo de pontuacao sem par sai sem varreduras repetidas`() {
        val links = IntegridadeDeLinks.extrair("Veja https://example.org/article" + ")".repeat(20_000) + ".")
        assertEquals(1, links.size)
        assertEquals("https://example.org/article", links[0].urlOriginal)
    }

    @Test
    fun `destino malformado do markdown continua um link bloqueado`() {
        val resultado = auditar("[fonte](http://)")
        assertEquals(1, resultado.urlsEncontradas)
        assertEquals(1, resultado.falhas)
    }

    @Test
    fun `link por referencia conta uma vez, no uso`() {
        val links = IntegridadeDeLinks.extrair("[fonte][manual]\n\n[manual]: https://example.org/article(v2)")
        assertEquals(1, links.size)
        assertEquals("https://example.org/article(v2)", links[0].urlOriginal)
    }

    @Test
    fun `as posicoes saem em UTF-16, a unidade da String`() {
        fun posicoes(texto: String) = IntegridadeDeLinks.extrair(texto).map { Triple(it.inicio, it.urlInicio, it.urlFim) }
        assertEquals(listOf(Triple(8, 28, 49)), posicoes("A fonte [documento oficial](https://example.com/a) sustenta a frase."))
        // Na referência, o destino não está no trecho do uso: a URL recua para o trecho inteiro.
        assertEquals(listOf(Triple(0, 0, 15)), posicoes("[fonte][manual]\n\n[manual]: https://example.org/article(v2)"))
        assertEquals(
            listOf(Triple(0, 44, 70), Triple(1, 12, 41)),
            posicoes("[![grafico](https://example.org/chart.png)](https://example.org/report)"),
        )
        // `Ação: ` são 6 unidades e o emoji são 2; em bytes UTF-8 o canônico daria (8, 16, 37) e (44, 44, 65).
        assertEquals(
            listOf(Triple(6, 14, 35), Triple(40, 40, 61)),
            posicoes("Ação: [fonte](https://example.org/a) 😀 https://example.org/b"),
        )
    }

    @Test
    fun `o e-mail em autolink e um link mailto, pendente e aceitavel`() {
        // Especificação CommonMark 0.31.2, autolink de e-mail: a URL é `mailto:` mais o endereço. O canônico o deixa
        // de fora por efeito da representação do pulldown-cmark; aqui ele é conferido como qualquer `mailto:` e pode
        // ser aceito (decisão 1 do operador, 05/10/2026).
        val links = IntegridadeDeLinks.extrair("Escreva para <editor@example.com> hoje.")
        assertEquals(1, links.size)
        assertEquals("mailto:editor@example.com", links[0].urlOriginal)
        assertEquals("editor@example.com", links[0].textoDaAncora)
        assertEquals(Triple(13, 13, 33), Triple(links[0].inicio, links[0].urlInicio, links[0].urlFim))
        val registro = RegistroEmMemoria()
        val linha = auditar("Escreva para <editor@example.com> hoje.", registro).linhas.single()
        assertEquals("warn", linha.tom)
        // O endereço não é credencial: a linha guarda a URL, a âncora e o contexto (achado do Gemini e do Codex no
        // cross-review da #77, 06/10/2026).
        assertEquals("mailto:editor@example.com", linha.urlOriginal)
        assertEquals("editor@example.com", linha.textoDaAncora)
        assertTrue(linha.textoAoRedor.contains("Escreva para"), linha.textoAoRedor)
        assertEquals(StatusDaRevisao.ACEITA, aceitar(linha, registro).statusDaRevisao)
    }

    @Test
    fun `o mailto em caixa alta e o mesmo mailto, sem coleta`() {
        // A crate `url` serializa o esquema em caixa baixa, e o canônico lê `mailto:` da URL normalizada; o
        // `java.net.URI` guarda a caixa do texto (achado do Codex no cross-review da #77, 06/10/2026).
        val linha = auditar("Ver [mail](MAILTO:Reader@Example.org) agora.").linhas.single()
        assertEquals("mailto:Reader@Example.org", linha.urlNormalizada)
        assertEquals(listOf("url_parser_normalization"), linha.mudancasDaNormalizacao)
        assertEquals("mailto sintaticamente valido", linha.status)
        assertEquals("warn", linha.tom)
        assertTrue(coletadas.isEmpty(), coletadas.toString())
    }

    @Test
    fun `a credencial na autoridade de um mailto hierarquico e recusada e redigida`() {
        // `mailto://u:p@host/` tem autoridade, e o `java.net.URI` lê o usuário e a senha, como a crate `url`. O canônico
        // só recusa a credencial em http e https, e a senha iria para a URL normalizada de um link aceitável, embora
        // a autoridade já o redigisse (achado do Codex no cross-review da #77, 06/10/2026).
        val linhas = auditar("Ver [mail](mailto://reader:review-placeholder@example.org/) e [ok](https://example.com/public) agora.").linhas
        assertEquals(2, linhas.size)
        val correio = linhas[0]
        assertEquals(ClassificacaoDoLink.MALFORMADO, correio.classificacao)
        assertEquals("credenciais embutidas na URL sao proibidas", correio.invalidade)
        assertEquals("<blocked URL>", correio.urlNormalizada)
        assertNull(correio.textoDaAncora)
        assertEquals("<redacted context>", correio.textoAoRedor)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("review-placeholder") || gravado.contains("reader:"), gravado)
        assertEquals("ok", linhas[1].textoDaAncora)
        assertEquals(listOf("https://example.com/public"), coletadas)
    }

    @Test
    fun `a credencial numa autoridade que o java net URI nao le como servidor e recusada do mesmo jeito`() {
        // `mailto://reader:…@exa_mple.org/`: o `_` no host faz o `java.net.URI` ler a autoridade como "registry-based",
        // sem usuário nem senha; só o texto mostra a credencial, e a normalização a recusa pelo mesmo cinto da redação,
        // senão a senha iria para a URL normalizada de um link aceitável (achado do Codex no cross-review da #77,
        // rodada 6, 06/10/2026).
        val linhas = auditar("[mail](mailto://reader:review-placeholder@exa_mple.org/) [ok](https://example.org/public)").linhas
        assertEquals(2, linhas.size)
        val correio = linhas[0]
        assertEquals(ClassificacaoDoLink.MALFORMADO, correio.classificacao)
        assertEquals("credenciais embutidas na URL sao proibidas", correio.invalidade)
        assertEquals("<blocked URL>", correio.urlNormalizada)
        assertNull(correio.textoDaAncora)
        assertEquals("<redacted context>", correio.textoAoRedor)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("review-placeholder") || gravado.contains("reader:"), gravado)
        assertEquals("ok", linhas[1].textoDaAncora)
        assertEquals(listOf("https://example.org/public"), coletadas)
    }

    @Test
    fun `o que a biblioteca entrega como ela e, fixado pela especificacao`() {
        // A referência colapsada inclui o `[]` (§6.5): o fim da URL recuada é o do trecho inteiro.
        val colapsada = IntegridadeDeLinks.extrair("[manual][] " + "x".repeat(200) + "\n\n[manual]: https://example.org/a")
        assertEquals(10, colapsada.single().urlFim)
        // A mesma referência definida duas vezes: a primeira vale (§4.7), e a segunda, que nunca se renderiza, fica
        // coberta como definição. O canônico a auditava por acidente do mapa do pulldown-cmark (medição, seção 6.3).
        assertEquals(1, IntegridadeDeLinks.extrair("[a]\n\n[a]: https://example.org/1\n[a]: https://example.org/2").size)
        // A definição sem uso não é link vivo, com URL no título inclusive (como no canônico).
        assertEquals(0, IntegridadeDeLinks.extrair("[x]: https://example.org/u \"https://example.org/t\"").size)
        // Destino relativo e só-fragmento não são auditados; `//host` e `\` são (e caem na normalização).
        assertEquals(0, IntegridadeDeLinks.extrair("[a](/metodologia) [b](#secao) [c](./x)").size)
        assertEquals(listOf("//example.org/a", "a\\b"), IntegridadeDeLinks.extrair("[a](//example.org/a) [b](a\\b)").map { it.urlOriginal })
    }

    @Test
    fun `sentinela do defeito da biblioteca, o titulo de definicao nao fechado reprova o texto ate a correcao`() {
        // Defeito da commonmark-java 0.30.0 (issue aberta no projeto dela): a linha que abre um título e não o fecha
        // fica com a posição de origem dentro da definição, e o parágrafo dela sai sem posição. Uma URL nessa linha
        // ficaria sem conferência; o extrator falha fechado. Quando a biblioteca corrigir, este teste cai: aí a
        // recusa sai e o texto passa a dar 2 links.
        val texto = "[1]: https://example.org/a\n(ver tambem https://example.org/b\n\n[1]"
        val falha = assertFailsWith<IntegridadeDeLinks.Falha> { IntegridadeDeLinks.extrair(texto) }
        assertTrue(falha.message!!.contains("source position of a paragraph"), falha.message)
        assertFailsWith<IntegridadeDeLinks.Falha> { auditar(texto) }
    }

    @Test
    fun `protocolos nao suportados sao extraidos e falham fechado`() {
        val links = IntegridadeDeLinks.extrair(
            "[arquivo](ftp://files.example.com/a.zip), tel:+5511999999999 e javascript:alert(1)",
        )
        assertEquals(3, links.size)
        assertTrue(
            links.all {
                IntegridadeDeLinks.normalizar(it.urlOriginal, analisador) is IntegridadeDeLinks.Normalizacao.Recusada
            },
        )
    }

    @Test
    fun `o codigo em linha entra na ancora, como o texto`() {
        // `16a8cff.rs:271-276` e `:293-297`: só `Text` e `Code` compõem a âncora.
        val links = IntegridadeDeLinks.extrair("ver [a pagina `index.html` do site](https://example.com/x) agora")
        assertEquals("a pagina index.html do site", links.single().textoDaAncora)
    }

    // ── redação e máscara (`16a8cff.rs:1449-1578`), com a decisão 2 do operador ────────────────────────────

    @Test
    fun `o registro do link com credencial nao guarda a credencial`() {
        for (url in listOf(
            "https://user:secret@example.com/a?access_token=secret",
            "https://example.com/a?access_token=secret",
            "https://example.com/a#access_token=secret",
            "https://example.com/a?utm_source=x;access_token=secret",
            // Nem parseia: o usuário na autoridade basta para redigir, e a URL sai como `<blocked URL>`.
            "https://user:secret@[::1",
        )) {
            val linha = auditar("fonte $url agora").linhas.single()
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, url)
            for (campo in listOf(linha.urlOriginal, linha.urlNormalizada, linha.textoAoRedor, linha.textoDaAncora ?: "")) {
                assertFalse(campo.contains("secret") || campo.contains("access_token") || campo.contains("user:"), "$url -> $campo")
            }
            assertEquals("<redacted context>", linha.textoAoRedor, url)
        }
        assertEquals("https://example.com/", auditar("fonte https://user:secret@example.com/a?access_token=secret").linhas.single().urlOriginal)
        assertEquals("<blocked URL>", auditar("fonte https://user:secret@[::1 agora").linhas.single().urlOriginal)
    }

    @Test
    fun `o contexto do link aceito nao carrega a URL recusada vizinha`() {
        val texto = "https://example.com/" + "x".repeat(300) + "?access_token=secret https://example.com/public"
        val publica = auditar(texto).linhas.single { it.urlOriginal == "https://example.com/public" }
        assertFalse(publica.textoAoRedor.contains("secret") || publica.textoAoRedor.contains("access_token"), publica.textoAoRedor)
    }

    @Test
    fun `a ancora do link aceito com URL recusada dentro e mascarada`() {
        // O colchete construído à parte: o redator do servidor do cross-review engole o valor até a aspa, `]` inclusive.
        val linha = auditar("[https://example.com/private?access_token=secret" + "](https://example.com/public)").linhas.single()
        assertEquals("https://example.com/public", linha.urlOriginal)
        // O literal vai até o `)` e a máscara derruba o link: contexto redigido e âncora nula (`safe_context_link`).
        assertNull(linha.textoDaAncora)
        assertEquals("<redacted context>", linha.textoAoRedor)
        assertFalse(linha.textoAoRedor.contains("secret") || linha.textoAoRedor.contains("access_token"), linha.textoAoRedor)
    }

    @Test
    fun `dois links recusados da mesma origem ficam com ids distintos, e o link longo nao e redigido`() {
        val linhas = auditar("a https://user:a@example.com/x e b https://user:b@example.com/y").linhas
        assertEquals(listOf("https://example.com/", "https://example.com/"), linhas.map { it.urlOriginal })
        assertNotEquals(linhas[0].linkId, linhas[1].linkId)
        // Decisão 2: o link recusado só pelo comprimento (M22) aparece por inteiro, cortado em 1.000 pontos de código.
        val longa = "https://example.com/" + "x".repeat(1_001)
        val linha = auditar("ver $longa agora").linhas.single()
        assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao)
        assertTrue(linha.urlOriginal.startsWith("https://example.com/xxx"), linha.urlOriginal)
        assertNotEquals("<redacted context>", linha.textoAoRedor)
    }

    @Test
    fun `o link recusado sem credencial aparece por inteiro, e o padrao de segredo e redigido`() {
        // Decisão 2 do operador: `javascript:`, `ftp://` e o malformado não são redigidos (o canônico redige todos).
        val linhas = auditar("[a](javascript:alert(1)) [b](ftp://files.example.com/a.zip) [c](http://)").linhas
        assertEquals(listOf("javascript:alert(1)", "ftp://files.example.com/a.zip", "http://"), linhas.map { it.urlOriginal })
        assertTrue(linhas.all { it.classificacao == ClassificacaoDoLink.MALFORMADO && it.textoAoRedor != "<redacted context>" })
        // O padrão de segredo conhecido (M22) é redigido.
        val segredo = auditar("ver https://example.com/?k=sk-ant-abcdefghijklmnop agora").linhas.single()
        assertEquals("https://example.com/", segredo.urlOriginal)
        assertEquals("<redacted context>", segredo.textoAoRedor)
    }

    @Test
    fun `a credencial em autoridade sem esquema http e redigida, e nao vaza no contexto do vizinho`() {
        // `//u:p@h` e `ftp://u:p@h` são recusados por outro motivo (malformada; esquema) e, pela decisão 2, sairiam
        // por inteiro; a credencial na autoridade redige mesmo assim, e a definição de referência é mascarada do
        // contexto do vizinho (achados do DeepSeek e do Grok no cross-review da #77, 06/10/2026).
        val texto = "[a](//user:pw@example.com/x) [b](ftp://user:pw@example.com/y) [c][r] [d](https://example.com/public)\n\n" +
            "[r]: //user:pw@example.com/z"
        val linhas = auditar(texto).linhas
        assertEquals(4, linhas.size)
        for (linha in linhas.take(3)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        val publica = linhas[3]
        assertEquals("https://example.com/public", publica.urlOriginal)
        assertFalse(publica.textoAoRedor.contains("pw@") || publica.textoAoRedor.contains("user:"), publica.textoAoRedor)
    }

    @Test
    fun `a credencial em grafia de esquema especial com uma barra ou nenhuma e redigida e mascarada`() {
        // `ftp`, `ws` e `wss` são esquemas especiais no WHATWG, como `http`: a crate `url` lê `ftp:/u:p@h` e `ftp:u:p@h`
        // como `ftp://u:p@h`, com usuário e senha; o `java.net.URI` lê um caminho ou uma parte opaca. A autoridade
        // redige e o literal é mascarado em qualquer grafia (achado do DeepSeek no cross-review da #77, 06/10/2026).
        val texto = "[x](ftp:/u:p@h/z) [y](ftp:u:p@h) [w](ws:\\u:p@h/) `ftp:/u:p@h/q` [d](https://example.com/public)"
        val linhas = auditar(texto).linhas
        assertEquals(4, linhas.size)
        // O `java.net.URI` lê `ftp:/u:p@h/z` como caminho e `ftp:u:p@h` como parte opaca (recusa pelo esquema), e não lê
        // a barra invertida (recusa por malformação); a redação não depende do motivo.
        assertEquals(
            listOf("somente http, https e mailto sao permitidos", "somente http, https e mailto sao permitidos", "URL malformada ou incompleta"),
            linhas.take(3).map { it.invalidade },
        )
        for (linha in linhas.take(3)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        val publica = linhas[3]
        assertEquals("https://example.com/public", publica.urlOriginal)
        assertEquals("d", publica.textoDaAncora)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("u:p@"), gravado)
    }

    @Test
    fun `o parametro de credencial e redigido em qualquer esquema, mesmo recusado antes por outro motivo`() {
        // A normalização recusa o esquema, ou o caractere de controle, antes de olhar o parâmetro; a redação não
        // segue a ordem das recusas: o parâmetro de credencial que o parser lê, sem o caractere de controle, ou que
        // o texto traz quando nenhum parser a lê, redige (achado do Grok no cross-review da #77, 06/10/2026). O
        // controle dentro do nome (`tok<U+0001>en`) só cai com o controle tirado: cru, nem o sufixo `token` casa.
        val linhas = auditar(
            "[a](ftp://files.example.com/x?access_token=valor-sintetico) [b](javascript:go?token=valor-sintetico) " +
                "https://example.com/api_key=valor-sintetico/x\u0001 https://example.com/?tok\u0001en=valor-sintetico " +
                "[c](<https://exa mple.com/?secret=1>) [d](https://example.com/public)",
        ).linhas
        assertEquals(6, linhas.size)
        assertEquals(
            listOf(
                "somente http, https e mailto sao permitidos", "somente http, https e mailto sao permitidos",
                "URL vazia ou com caracteres de controle", "URL vazia ou com caracteres de controle",
                "URL malformada ou incompleta",
            ),
            linhas.take(5).map { it.invalidade },
        )
        for (linha in linhas.take(5)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        val publica = linhas[5]
        assertEquals("https://example.com/public", publica.urlOriginal)
        assertEquals("d", publica.textoDaAncora)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("valor-sintetico") || gravado.contains("secret=1"), gravado)
    }

    @Test
    fun `o destino entre menor e maior com espacos a volta e lido sem eles, e a credencial e redigida`() {
        // O CommonMark guarda os espaços de `< ftp:/u:p@h/z >` no destino; o cinto da autoridade, ancorado no início,
        // lia o texto cru e não casava, e a URL ia inteira para a linha (achado do DeepSeek no cross-review da #77,
        // rodada 6, 06/10/2026). A regra lê o texto aparado, como a normalização. O `< //u:p@h/z >` começa por espaço
        // e cai no predicado de destino interno, como no canônico: não é auditado nem gravado, e o literal é mascarado
        // do contexto do vizinho.
        val texto = "[x](< ftp:/u:p@h/z >) [y](< ftp:u:p@h >) [z](< //u:p@h/z >) [w](< ws:\\u:p@h/ >) [d](https://example.com/public)"
        val linhas = auditar(texto).linhas
        assertEquals(4, linhas.size)
        for (linha in linhas.take(3)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<blocked URL>", linha.urlNormalizada, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        val publica = linhas[3]
        assertEquals("https://example.com/public", publica.urlOriginal)
        assertEquals("d", publica.textoDaAncora)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("u:p@"), gravado)
    }

    @Test
    fun `o destino que nenhum parser le e lido como texto inteiro, autoridade e caminho inclusive`() {
        // Sem parser, o cinto da autoridade só via o começo do texto e a regra do caminho só valia a partir de uma barra
        // inicial; `<prefixo https://u:p@h/>` e `<https://exa mple.com/api_key=…/x>` iam inteiros para a linha (achado
        // do Grok no cross-review da #77, rodada 6, 06/10/2026). O literal de URL dentro do texto passa pelo cinto, e o
        // texto antes da query passa pela regra do segmento.
        val linhas = auditar("[a](<prefixo https://u:p@h/>) [b](<https://exa mple.com/api_key=valor-sintetico/x>) [d](https://example.com/public)").linhas
        assertEquals(3, linhas.size)
        assertEquals(listOf("URL malformada ou incompleta", "URL malformada ou incompleta"), linhas.take(2).map { it.invalidade })
        for (linha in linhas.take(2)) {
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        assertEquals("d", linhas[2].textoDaAncora)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("u:p@") || gravado.contains("valor-sintetico"), gravado)
    }

    @Test
    fun `a credencial aninhada depois de uma barra, num destino que nenhum parser le, e redigida`() {
        // O cinto da autoridade, ancorado no início de cada literal de URL, não via o `https://u:p@h/` dentro do caminho
        // de `<x https://example.com/r/https://u:p@h/>`, que nenhum parser lê, e a URL ia inteira para a linha (achado do
        // Grok no cross-review da #77, rodada 7, 06/10/2026). No texto sem parser o cinto corre sem âncora, sobre o texto
        // todo; com o caractere de controle no fim, a leitura sem ele dá no mesmo. Na URL que o parser lê a âncora fica:
        // `https://example.com/r/https://u:p@h/` tem a autoridade `example.com` e um caminho, aqui e no canônico.
        val linhas = auditar(
            "[a](<x https://example.com/r/https://u:p@h/>) [b](<x https://example.com/r/https://u:p@h/\u0001>) " +
                "[d](https://example.com/public)",
        ).linhas
        assertEquals(3, linhas.size)
        assertEquals(listOf("URL malformada ou incompleta", "URL vazia ou com caracteres de controle"), linhas.take(2).map { it.invalidade })
        for (linha in linhas.take(2)) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao, linha.url)
            assertEquals("<blocked URL>", linha.urlOriginal, linha.url)
            assertEquals("<redacted context>", linha.textoAoRedor, linha.url)
        }
        val publica = linhas[2]
        assertEquals("d", publica.textoDaAncora)
        assertFalse(publica.textoAoRedor.contains("u:p@"), publica.textoAoRedor)
        val gravado = FormatoDeLinks.serializarLinhas(linhas)
        assertFalse(gravado.contains("u:p@"), gravado)
        // Controle: a mesma autoridade aninhada numa URL que o parser lê é caminho, não credencial (o canônico só lê o
        // usuário e a senha que o parser dá); o link é coletado, com âncora e contexto.
        val lida = auditar("[c](https://example.com/r/https://u:p@h/) agora.").linhas.single()
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, lida.classificacao, lida.invalidade)
        assertEquals("https://example.com/r/https://u:p@h/", lida.urlNormalizada)
        assertEquals("c", lida.textoDaAncora)
    }

    @Test
    fun `normalizacao recusa script e credenciais`() {
        fun recusada(url: String) =
            IntegridadeDeLinks.normalizar(url, analisador) is IntegridadeDeLinks.Normalizacao.Recusada
        assertTrue(recusada("javascript:alert(1)"))
        assertTrue(recusada("ftp://files.example.com/archive.zip"))
        assertTrue(recusada("tel:+5511999999999"))
        assertTrue(recusada("https://user:secret@example.com/"))
        assertTrue(recusada("https://example.com/a?access_token=secret"))
        assertTrue(recusada("https://example.com/a#access_token=secret"))
        assertTrue(recusada("https://example.com/a?utm_source=x;access_token=secret"))
        assertFalse(recusada("https://example.com/a"))
        assertFalse(recusada("mailto:editor@example.com"))
        assertEquals(
            IntegridadeDeLinks.Normalizacao.Recusada("parametro de credencial na URL e proibido"),
            IntegridadeDeLinks.normalizar("https://example.com/a?access_token=secret", analisador),
        )
    }

    @Test
    fun `a extracao conta toda ocorrencia antes do limite`() {
        val texto = (0..IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS)
            .joinToString("\n") { "[fonte $it](https://example.com/source)" }
        assertEquals(IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS + 1, IntegridadeDeLinks.contarOcorrencias(texto))
    }

    private val extraido = IntegridadeDeLinks.LinkExtraido(
        inicio = 0,
        urlInicio = 8,
        urlFim = 34,
        urlOriginal = "https://example.com/source",
        textoDaAncora = "fonte",
        textoAoRedor = "afirmacao A com fonte",
    )

    @Test
    fun `a identidade do link fica presa a origem exata`() {
        val primeiro = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source A"), extraido.urlOriginal, emptyList(), 1, agora,
        )
        val segundo = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source B"), extraido.urlOriginal, emptyList(), 1, agora,
        )
        assertNotEquals(primeiro.linkId, segundo.linkId)
    }

    @Test
    fun `evidencia alcancavel nao e suporte automatico`() {
        val linha = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source"), extraido.urlOriginal, emptyList(), 1, agora,
        )
        assertNull(linha.sustentaAfirmacao)
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, linha.classificacao)
        assertEquals(StatusDaRevisao.PENDENTE, linha.statusDaRevisao)
    }

    // ── o que a suíte canônica não cobre ─────────────────────────────────────

    @Test
    fun `o contexto ao redor mede 180 bytes UTF-8 de cada lado`() {
        // "é" ocupa 2 bytes: o link começa no byte 401, e 401 − 180 = 221 cai
        // no meio de um "é"; o recorte recua para o byte 220, que deixa 90
        // caracteres à esquerda, e não 180.
        val texto = "é".repeat(200) + " [x](https://example.com/a) " + "é".repeat(200)
        val contexto = IntegridadeDeLinks.extrair(texto).single().textoAoRedor
        val esquerda = contexto.substringBefore(" [x]")
        assertEquals(90, esquerda.length)
    }

    @Test
    fun `link aceito sai de pendente, e editar o texto o devolve a pendente`() {
        val registro = RegistroEmMemoria()
        val texto = "A fonte [oficial](https://example.com/a) sustenta a frase."
        val primeira = auditar(texto, registro)
        assertTrue(IntegridadeDeLinks.exigeResolucaoEditorial(primeira))
        val linha = primeira.linhas.single()
        IntegridadeDeLinks.revisar(
            IntegridadeDeLinks.PedidoDeRevisao(
                linkId = linha.linkId, decisao = DecisaoDeRevisao.ACEITAR, nota = "fonte oficial confere",
                revisor = "operator", esperada = IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha),
            ),
            registro,
            agora,
        )
        val segunda = auditar(texto, registro)
        assertFalse(IntegridadeDeLinks.exigeResolucaoEditorial(segunda))
        assertEquals("ok", segunda.linhas.single().tom)
        assertEquals(ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO, segunda.linhas.single().classificacao)
        val editada = auditar("$texto Mais uma frase.", registro)
        assertTrue(IntegridadeDeLinks.exigeResolucaoEditorial(editada))
        assertEquals(StatusDaRevisao.PENDENTE, editada.linhas.single().statusDaRevisao)
    }

    @Test
    fun `revisao aceita cai quando o conteudo da pagina muda`() {
        // Origem, contexto, âncora e URL já entram no identificador; o hash do
        // conteúdo é a única conferência da preservação que não é redundante.
        val registro = RegistroEmMemoria()
        var sha = HASH_1
        val coletorMutavel = IntegridadeDeLinks.ColetorDeEvidencia { url -> evidencia(url, sha = sha) }
        val texto = "Ver [x](https://example.com/a)."
        fun rodar() = IntegridadeDeLinks.auditar(texto, analisador, coletorMutavel, registro) { agora }
        val linha = rodar().linhas.single()
        IntegridadeDeLinks.revisar(
            IntegridadeDeLinks.PedidoDeRevisao(
                linha.linkId, DecisaoDeRevisao.ACEITAR, "fonte oficial confere", "operator",
                IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha).copy(sha256 = HASH_1),
            ),
            registro,
            agora,
        )
        assertEquals("ok", rodar().linhas.single().tom)
        sha = HASH_2
        val depois = rodar().linhas.single()
        assertEquals(linha.linkId, depois.linkId)
        assertEquals(StatusDaRevisao.PENDENTE, depois.statusDaRevisao)
    }

    @Test
    fun `revisao exige revisor aceito, nota e o mesmo conteudo lido`() {
        val registro = RegistroEmMemoria()
        val linha = auditar("Ver [x](https://example.com/a).", registro).linhas.single()
        fun pedido(
            revisor: String = "operator",
            nota: String = "nota suficiente",
            url: String = linha.urlNormalizada,
            sha: String? = linha.sha256,
            decisao: DecisaoDeRevisao = DecisaoDeRevisao.ACEITAR,
            urlFinal: String? = linha.urlFinal,
            cadeia: List<Redirecionamento> = linha.cadeiaDeRedirecionamento,
        ) = IntegridadeDeLinks.PedidoDeRevisao(
            linha.linkId, decisao, nota, revisor, IntegridadeDeLinks.IdentidadeDaEvidencia(url, sha, urlFinal, cadeia),
        )
        fun falha(pedido: IntegridadeDeLinks.PedidoDeRevisao) =
            assertFailsWith<IntegridadeDeLinks.Falha> { IntegridadeDeLinks.revisar(pedido, registro, agora) }.message
        assertEquals("reviewer identity is not allowlisted", falha(pedido(revisor = "chatgpt")))
        assertEquals("review note must contain at least 10 characters", falha(pedido(nota = "  curta   ")))
        assertEquals(
            "link URL, redirect identity, or content hash changed since it was read; reload before reviewing",
            falha(pedido(sha = "outro")),
        )
        // Só a URL normalizada diferente, com hash, URL final e cadeia iguais (pedido do Codex e do Grok no
        // cross-review da #77, 05/10/2026).
        assertEquals(
            "link URL, redirect identity, or content hash changed since it was read; reload before reviewing",
            falha(pedido(url = "https://example.com/outra")),
        )
        val quarentena = IntegridadeDeLinks.revisar(pedido(decisao = DecisaoDeRevisao.QUARENTENA), registro, agora)
        assertEquals("blocked", quarentena.tom)
        assertEquals(StatusDaRevisao.REJEITADA, quarentena.statusDaRevisao)
    }

    @Test
    fun `a revisao confere a identidade do redirecionamento mesmo com o mesmo hash`() {
        // Porte de `review_rejects_changed_redirect_identity_even_with_the_same_content_hash` (`16a8cff`, #77).
        val linha = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source"), extraido.urlOriginal, emptyList(), 1, agora,
        ).copy(
            sha256 = "a".repeat(64),
            urlFinal = "https://example.com/first",
            cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/first", 302)),
        )
        fun confere(atual: LinhaDeLink) = IntegridadeDeLinks.evidenciaRevisadaConfere(
            atual, IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha),
        )
        assertTrue(confere(linha))
        assertFalse(confere(linha.copy(urlFinal = "https://example.com/second")))
        assertFalse(
            confere(linha.copy(cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/second", 302)))),
        )
        // A cadeia é comparada item a item, com o código de cada salto (`LinkEvidenceRedirect`: `url` e `status`).
        assertFalse(
            confere(linha.copy(cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/first", 301)))),
        )
    }

    @Test
    fun `aceite anterior cai quando a URL final ou a cadeia de redirecionamentos mudou`() {
        // Porte da preservação de `changed_redirect_destination_cannot_keep_editorial_acceptance` (`16a8cff`, #77).
        // A linha nova é aceitável: no teste do canônico ela não era, e o aceite caía também por isso.
        val nova = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source"), extraido.urlOriginal, emptyList(), 1, agora,
        ).copy(
            statusHttp = 200,
            sha256 = HASH_1,
            urlFinal = "https://example.com/old",
            cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/old", 301)),
            classificacaoMecanica = ClassificacaoDoLink.REDIRECIONADO_VERIFICADO,
        )
        val anterior = nova.copy(
            decisaoDeRevisao = DecisaoDeRevisao.ACEITAR,
            revisadoPor = "operator",
            notaDaRevisao = "fonte oficial confere",
            revisadoEm = "2026-10-05T00:00:00+00:00",
        )
        // Controle: o mesmo destino, pelo mesmo caminho, preserva o aceite.
        assertEquals(DecisaoDeRevisao.ACEITAR, IntegridadeDeLinks.preservarRevisao(nova, anterior).decisaoDeRevisao)
        assertNull(
            IntegridadeDeLinks.preservarRevisao(nova.copy(urlFinal = "https://example.com/new"), anterior).decisaoDeRevisao,
        )
        // Só a URL normalizada diferente, com hash, URL final e cadeia iguais.
        assertNull(
            IntegridadeDeLinks.preservarRevisao(nova.copy(urlNormalizada = "https://example.com/outra"), anterior).decisaoDeRevisao,
        )
        assertNull(
            IntegridadeDeLinks.preservarRevisao(
                nova.copy(cadeiaDeRedirecionamento = listOf(Redirecionamento("https://example.com/new", 301))),
                anterior,
            ).decisaoDeRevisao,
        )
    }

    @Test
    fun `a mesma URL por outro caminho volta a pendente, e a decisao da leitura antiga e recusada`() {
        // #77, seção 5.5 da medição: o id do link não inclui a URL final, e a coleta refeita pode seguir outro
        // caminho com o mesmo conteúdo. O aceite dado a um caminho não vale para o outro.
        val registro = RegistroEmMemoria()
        var cadeia = listOf(Redirecionamento("https://example.com/b", 301))
        val coletorMutavel = IntegridadeDeLinks.ColetorDeEvidencia { url ->
            evidencia(url).copy(urlFinal = "https://example.com/b", cadeiaDeRedirecionamento = cadeia)
        }
        fun rodar() =
            IntegridadeDeLinks.auditar("Ver [x](https://example.com/a).", analisador, coletorMutavel, registro) { agora }
        val lida = rodar().linhas.single()
        aceitar(lida, registro)
        // Controle: pelo mesmo caminho, o aceite é preservado.
        assertEquals(StatusDaRevisao.ACEITA, rodar().linhas.single().statusDaRevisao)
        cadeia = listOf(Redirecionamento("https://example.com/c", 302), Redirecionamento("https://example.com/b", 301))
        val depois = rodar().linhas.single()
        assertEquals(lida.linkId, depois.linkId)
        assertEquals(StatusDaRevisao.PENDENTE, depois.statusDaRevisao)
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { aceitar(lida, registro) }
        assertEquals(
            "link URL, redirect identity, or content hash changed since it was read; reload before reviewing",
            erro.message,
        )
        // Controle: a decisão montada sobre a versão nova é aceita.
        assertEquals(StatusDaRevisao.ACEITA, aceitar(depois, registro).statusDaRevisao)
    }

    @Test
    fun `a URL de rede ignora o fragmento, e a que nao parseia conta como diferente`() {
        // Porte da comparação de `changed_redirect_destination_cannot_keep_editorial_acceptance` (`16a8cff`, #77).
        assertTrue(
            IntegridadeDeLinks.mesmaUrlDeRede("https://example.com/source#monkey", "https://example.com/source", analisador),
        )
        assertFalse(IntegridadeDeLinks.mesmaUrlDeRede("https://example.com/other", "https://example.com/source", analisador))
        assertFalse(IntegridadeDeLinks.mesmaUrlDeRede("http://exa mple.com/", "http://exa mple.com/", analisador))
    }

    @Test
    fun `link com fragmento nao sai redirecionado na evidencia, no aceite nem na preservacao`() {
        // M10 e M28 (#77): a coleta tira o fragmento da URL final, e a comparação texto contra texto marcava todo
        // link com `#secao` como redirecionado, inclusive depois do aceite.
        val registro = RegistroEmMemoria()
        val semFragmento = IntegridadeDeLinks.ColetorDeEvidencia { url -> evidencia(url).copy(urlFinal = url.substringBefore('#')) }
        val texto = "Ver [x](https://example.com/a#secao)."
        fun rodar(coletor: IntegridadeDeLinks.ColetorDeEvidencia, onde: RegistroEmMemoria) =
            IntegridadeDeLinks.auditar(texto, analisador, coletor, onde) { agora }.linhas.single()
        val linha = rodar(semFragmento, registro)
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, linha.classificacaoMecanica)
        assertEquals(ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO, aceitar(linha, registro).classificacao)
        assertEquals(ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO, rodar(semFragmento, registro).classificacao)
        // Controle: outro destino continua redirecionado.
        val outro = IntegridadeDeLinks.ColetorDeEvidencia { url -> evidencia(url).copy(urlFinal = "https://example.com/b") }
        assertEquals(ClassificacaoDoLink.REDIRECIONADO_VERIFICADO, rodar(outro, RegistroEmMemoria()).classificacaoMecanica)
    }

    @Test
    fun `nao se aceita link que nao passou pela verificacao mecanica`() {
        val registro = RegistroEmMemoria()
        val linha = auditar("Ver [x](http://localhost:8787/test).", registro).linhas.single()
        assertEquals("error", linha.tom)
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> {
            IntegridadeDeLinks.revisar(
                IntegridadeDeLinks.PedidoDeRevisao(
                    linha.linkId, DecisaoDeRevisao.ACEITAR, "aceito mesmo assim", "operator",
                    IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha),
                ),
                registro,
                agora,
            )
        }
        assertEquals("cannot accept a link that did not pass mechanical validation", erro.message)
    }

    private fun aceitar(linha: LinhaDeLink, registro: RegistroEmMemoria) = IntegridadeDeLinks.revisar(
        IntegridadeDeLinks.PedidoDeRevisao(
            linha.linkId, DecisaoDeRevisao.ACEITAR, "fonte oficial confere", "operator", IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha),
        ),
        registro,
        agora,
    )

    private fun auditarCom(evidencia: RegistroDeEvidencia, registro: RegistroEmMemoria = RegistroEmMemoria()) =
        IntegridadeDeLinks.auditar("Ver [x](https://example.com/a).", analisador, { evidencia }, registro) {
            agora
        }.linhas.single()

    @Test
    fun `so evidencia pronta e sem interacao pendente pode ser aceita, mesmo com 200`() {
        // Divergência do canônico: lá o aceite só conferia o código HTTP. A
        // regra é a lista do que passa, não a do que falha: todo outro estado
        // e toda outra interação são recusados.
        val url = "https://example.com/a"
        val estados = EstadoDaEvidencia.entries.filter { it != EstadoDaEvidencia.PRONTA }
            .map { evidencia(url, estado = it) }
        // O auxiliar grava `resolvidaPorPessoa = false`: a resolvida por pessoa sem o booleano também é recusada (M12).
        val interacoes = EstadoDeInteracao.entries
            .filter { it != EstadoDeInteracao.NENHUMA }
            .map { evidencia(url, interacao = it) }
        for (caso in estados + interacoes) {
            val registro = RegistroEmMemoria()
            val linha = auditarCom(caso, registro)
            assertEquals(200, linha.statusHttp)
            val erro = assertFailsWith<IntegridadeDeLinks.Falha>(caso.toString()) { aceitar(linha, registro) }
            assertEquals("cannot accept a link that did not pass mechanical validation", erro.message, caso.toString())
        }
        // O tom segue o estado: `blocked` é o que precisa de alguém agir
        // (bloqueada, coleta que não terminou, ação do operador), e conta em
        // `bloqueadas`; `error` é a coleta que terminou e falhou.
        val terminadas = setOf(EstadoDaEvidencia.PRONTA, EstadoDaEvidencia.FALHOU)
        for (estado in EstadoDaEvidencia.entries) {
            val esperado = if (estado in terminadas) "error" else "blocked"
            assertEquals(esperado, auditarCom(evidencia(url, status = 404, estado = estado)).tom, estado.toString())
        }
        // Pronta, mas com interação pendente (consentimento, download, a
        // resolvida por pessoa sem o booleano, e também captcha, login e
        // paywall): alguém ainda precisa agir, então o tom é `blocked`, pelo
        // mesmo predicado da quarentena.
        for (interacao in EstadoDeInteracao.entries) {
            val pendente = interacao != EstadoDeInteracao.NENHUMA
            val linha = auditarCom(evidencia(url, status = 404, interacao = interacao))
            assertEquals(if (pendente) "blocked" else "error", linha.tom, interacao.toString())
        }
        // A evidência que não terminou vai para quarentena antes de o código
        // HTTP guardado nela ser lido: vencida com 404 é quarentena, e não
        // "não encontrada". Já captcha, login e paywall, que o motor grava
        // junto com o estado "ação do operador", têm classe própria e contam
        // como bloqueadas pelo estado.
        val finais = setOf(EstadoDaEvidencia.PRONTA, EstadoDaEvidencia.BLOQUEADA, EstadoDaEvidencia.FALHOU)
        for (estado in EstadoDaEvidencia.entries.filter { it !in finais }) {
            val linha = auditarCom(evidencia(url, status = 404, estado = estado))
            assertEquals(ClassificacaoDoLink.EM_QUARENTENA, linha.classificacao, estado.toString())
        }
        val captcha = auditarCom(
            evidencia(url, estado = EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR, interacao = EstadoDeInteracao.EXIGE_CAPTCHA),
        )
        assertEquals(ClassificacaoDoLink.EXIGE_CAPTCHA, captcha.classificacao)
        assertEquals("blocked", captcha.tom)
        // Controle: na pronta e na que falhou, o código HTTP segue a ordem do
        // canônico; a bloqueada vai para quarentena antes de o código ser lido
        // (M13, #77).
        assertEquals(ClassificacaoDoLink.NAO_ENCONTRADO, auditarCom(evidencia(url, status = 404)).classificacao)
        assertEquals(
            ClassificacaoDoLink.EM_QUARENTENA,
            auditarCom(evidencia(url, status = 403, estado = EstadoDaEvidencia.BLOQUEADA)).classificacao,
        )
        // Controle: interação que uma pessoa resolveu, com o booleano que o confirma, é aceita.
        val resolvida = RegistroEmMemoria()
        val linha = auditarCom(
            evidencia(url, interacao = EstadoDeInteracao.RESOLVIDA_POR_PESSOA).copy(resolvidaPorPessoa = true),
            resolvida,
        )
        assertEquals(StatusDaRevisao.ACEITA, aceitar(linha, resolvida).statusDaRevisao)
        // Controle: a mesma página sem interação pendente é aceita, inclusive
        // depois de uma quarentena, que troca a classificação exibida mas não
        // a mecânica.
        val registro = RegistroEmMemoria()
        val limpa = auditar("Ver [x](https://example.com/a).", registro).linhas.single()
        IntegridadeDeLinks.revisar(
            IntegridadeDeLinks.PedidoDeRevisao(
                limpa.linkId, DecisaoDeRevisao.QUARENTENA, "conferir depois", "operator", IntegridadeDeLinks.IdentidadeDaEvidencia.de(limpa),
            ),
            registro,
            agora,
        )
        assertEquals(StatusDaRevisao.ACEITA, aceitar(limpa, registro).statusDaRevisao)
    }

    @Test
    fun `so evidencia pronta, fresca e sem interacao pendente passa na classe de falha`() {
        // Porte de `only_ready_resolved_evidence_is_mechanically_acceptable` (`16a8cff`, #77; M12, M14, M15).
        val pronta = evidencia("https://example.com/a")
        for (estado in EstadoDaEvidencia.entries.filter { it != EstadoDaEvidencia.PRONTA && it != EstadoDaEvidencia.FALHOU }) {
            assertNotNull(IntegridadeDeLinks.classeDeFalhaMecanica(pronta.copy(estado = estado)), estado.toString())
        }
        for (cache in EstadoDoCache.entries.filter { it != EstadoDoCache.FRESCO }) {
            assertEquals(
                ClassificacaoDoLink.EM_QUARENTENA,
                IntegridadeDeLinks.classeDeFalhaMecanica(pronta.copy(estadoDoCache = cache)),
                cache.toString(),
            )
        }
        for (interacao in listOf(
            EstadoDeInteracao.EXIGE_CONSENTIMENTO,
            EstadoDeInteracao.CONFIRMAR_DOWNLOAD,
            EstadoDeInteracao.RESOLVIDA_POR_PESSOA,
        )) {
            assertEquals(
                ClassificacaoDoLink.EM_QUARENTENA,
                IntegridadeDeLinks.classeDeFalhaMecanica(pronta.copy(estadoDeInteracao = interacao)),
                interacao.toString(),
            )
        }
        assertNull(
            IntegridadeDeLinks.classeDeFalhaMecanica(
                pronta.copy(estadoDeInteracao = EstadoDeInteracao.RESOLVIDA_POR_PESSOA, resolvidaPorPessoa = true),
            ),
        )
        // Controle: a pronta, fresca, com 2xx e sem interação passa.
        assertNull(IntegridadeDeLinks.classeDeFalhaMecanica(pronta))
        // M16: a pronta sem código HTTP fica em quarentena.
        assertEquals(ClassificacaoDoLink.EM_QUARENTENA, IntegridadeDeLinks.classeDeFalhaMecanica(pronta.copy(status = null)))
    }

    @Test
    fun `o tom blocked segue o mesmo predicado da quarentena`() {
        // Especificação, seção 2.2: a linha conta como bloqueada sempre que alguém precisa agir antes de a evidência
        // valer, o mesmo predicado da quarentena. A pronta com cache não fresco e a pronta sem código HTTP (M15, M16)
        // entram nele; o canônico lhes dava `error`.
        val pronta = evidencia("https://example.com/a")
        for (cache in EstadoDoCache.entries.filter { it != EstadoDoCache.FRESCO }) {
            val linha = auditarCom(pronta.copy(estadoDoCache = cache))
            assertEquals(ClassificacaoDoLink.EM_QUARENTENA, linha.classificacao, cache.toString())
            assertEquals("blocked", linha.tom, cache.toString())
        }
        val semCodigo = auditarCom(pronta.copy(status = null))
        assertEquals(ClassificacaoDoLink.EM_QUARENTENA, semCodigo.classificacao)
        assertEquals("blocked", semCodigo.tom)
        // Controles que reprovam a hipótese contrária: a coleta que terminou sem pendência e falhou é `error`.
        assertEquals("error", auditarCom(pronta.copy(status = 404)).tom)
        assertEquals("error", auditarCom(pronta.copy(estado = EstadoDaEvidencia.FALHOU, status = null)).tom)
        assertEquals("blocked", auditarCom(pronta.copy(estado = EstadoDaEvidencia.BLOQUEADA)).tom)
    }

    @Test
    fun `um 403 com pedido de login e proibido, e o 401 exige autenticacao`() {
        // Porte de `login_required_403_keeps_forbidden_classification` (`16a8cff`, #77; M11).
        val login = evidencia("https://example.com/a", status = 403, interacao = EstadoDeInteracao.EXIGE_LOGIN)
        assertEquals(ClassificacaoDoLink.PROIBIDO, IntegridadeDeLinks.classeDeFalhaMecanica(login))
        assertEquals(ClassificacaoDoLink.EXIGE_AUTENTICACAO, IntegridadeDeLinks.classeDeFalhaMecanica(login.copy(status = 401)))
    }

    @Test
    fun `aceite e preservacao exigem a prova mecanica atual`() {
        // Porte de `accept_and_preservation_require_current_mechanical_proof` (`16a8cff`, #77; M13, M19).
        val base = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source"), extraido.urlOriginal, emptyList(), 1, agora,
        )
        val linha = IntegridadeDeLinks.aplicarEvidencia(base, evidencia(extraido.urlOriginal), analisador)
        val anterior = linha.copy(decisaoDeRevisao = DecisaoDeRevisao.ACEITAR)
        // Controle: a mesma verificação preserva o aceite.
        assertEquals(DecisaoDeRevisao.ACEITAR, IntegridadeDeLinks.preservarRevisao(linha, anterior).decisaoDeRevisao)
        val emQuarentena = linha.copy(classificacaoMecanica = ClassificacaoDoLink.EM_QUARENTENA)
        assertNull(IntegridadeDeLinks.preservarRevisao(emQuarentena, anterior).decisaoDeRevisao)
        val bloqueada = IntegridadeDeLinks.aplicarEvidencia(
            base, evidencia(extraido.urlOriginal, estado = EstadoDaEvidencia.BLOQUEADA), analisador,
        )
        assertEquals(ClassificacaoDoLink.EM_QUARENTENA, bloqueada.classificacaoMecanica)
    }

    @Test
    fun `aceite exige o hash do conteudo da evidencia, em qualquer caixa`() {
        // Porte de `http_accept_requires_valid_content_hash` (`16a8cff`, #77; M20, M21, M19). O hash nasceu no porte,
        // como divergência do Rust de `68528f9`, onde a revisão conferia `null` com `null`; o canônico passou a
        // exigi-lo em `16a8cff`, em qualquer caixa (decisão 5 do operador).
        for (sha in listOf(null, "", "sha-1", "short", "g".repeat(64), "a".repeat(63))) {
            val registro = RegistroEmMemoria()
            val linha = auditarCom(evidencia("https://example.com/a", sha = sha), registro)
            val erro = assertFailsWith<IntegridadeDeLinks.Falha>(sha.toString()) { aceitar(linha, registro) }
            assertEquals("cannot accept a link without the content hash of its evidence", erro.message)
        }
        val maiusculas = RegistroEmMemoria()
        val linha = auditarCom(evidencia("https://example.com/a", sha = "A".repeat(64)), maiusculas)
        assertEquals(StatusDaRevisao.ACEITA, aceitar(linha, maiusculas).statusDaRevisao)
        // Sem hash, o aceite anterior não é preservado; com o hash em maiúsculas, é.
        val base = IntegridadeDeLinks.linhaBase(
            extraido, TextoRust.sha256("source"), extraido.urlOriginal, emptyList(), 1, agora,
        )
        for ((sha, preservado) in listOf(null to false, "A".repeat(64) to true)) {
            val nova = IntegridadeDeLinks.aplicarEvidencia(base, evidencia(extraido.urlOriginal, sha = sha), analisador)
            val anterior = nova.copy(decisaoDeRevisao = DecisaoDeRevisao.ACEITAR)
            assertEquals(
                if (preservado) DecisaoDeRevisao.ACEITAR else null,
                IntegridadeDeLinks.preservarRevisao(nova, anterior).decisaoDeRevisao,
                sha.toString(),
            )
        }
        // Controle: o mailto, que não é coletado, é aceito sem hash (decisão 1 do operador).
        val registro = RegistroEmMemoria()
        val correio = auditar("Escreva para mailto:editor@example.com hoje.", registro).linhas.single()
        assertNull(correio.sha256)
        assertEquals(StatusDaRevisao.ACEITA, aceitar(correio, registro).statusDaRevisao)
    }

    @Test
    fun `aceite anterior nao e preservado se a verificacao nova deixou de passar`() {
        val registro = RegistroEmMemoria()
        var interacao = EstadoDeInteracao.NENHUMA
        val coletorMutavel = IntegridadeDeLinks.ColetorDeEvidencia { url -> evidencia(url, interacao = interacao) }
        val texto = "Ver [x](https://example.com/a)."
        fun rodar() = IntegridadeDeLinks.auditar(texto, analisador, coletorMutavel, registro) { agora }
        aceitar(rodar().linhas.single(), registro)
        assertEquals("ok", rodar().linhas.single().tom)
        // O mesmo hash de conteúdo, agora atrás de um captcha.
        interacao = EstadoDeInteracao.EXIGE_CAPTCHA
        val depois = rodar()
        assertEquals(StatusDaRevisao.PENDENTE, depois.linhas.single().statusDaRevisao)
        assertEquals(ClassificacaoDoLink.EXIGE_CAPTCHA, depois.linhas.single().classificacao)
        assertTrue(IntegridadeDeLinks.exigeResolucaoEditorial(depois))
    }

    @Test
    fun `URL que o saneamento alteraria fica bloqueada e nao e coletada`() {
        // Divergência do canônico: lá a URL cortada ou com `<redacted>` era a
        // que se coletava, e a revisão aprovaria outro destino.
        val longa = "https://example.com/" + "a".repeat(1000)
        val comSegredo = "https://example.com/x?chave=sk-" + "a".repeat(12)
        val resultado = auditar("Ver $longa e tambem $comSegredo agora.")
        assertEquals(2, resultado.linhas.size)
        for (linha in resultado.linhas) {
            assertEquals(ClassificacaoDoLink.MALFORMADO, linha.classificacao)
            assertEquals("blocked", linha.tom)
        }
        assertTrue(coletadas.isEmpty(), coletadas.toString())
        assertTrue(IntegridadeDeLinks.exigeResolucaoEditorial(resultado))
        // Controle: no limite exato, a URL passa e é coletada.
        val noLimite = "https://example.com/" + "a".repeat(1000 - "https://example.com/".length)
        assertEquals(ClassificacaoDoLink.VERIFICADO_MAS_FRACO, auditar("Ver $noLimite agora.").linhas.single().classificacao)
        assertEquals(listOf(noLimite), coletadas)
    }

    @Test
    fun `link malformado fica bloqueado e mailto nao e coletado`() {
        val resultado = auditar("Ver javascript:alert(1) e mailto:editor@example.com.")
        val (script, correio) = resultado.linhas
        assertEquals(ClassificacaoDoLink.MALFORMADO, script.classificacao)
        assertEquals("blocked", script.tom)
        assertEquals("warn", correio.tom)
        assertTrue(coletadas.none { it.startsWith("mailto:") })
        assertEquals(1, resultado.falhas)
        assertEquals(1, resultado.bloqueadas)
    }

    @Test
    fun `mais de 30 links recusam a auditoria`() {
        val texto = (0..30).joinToString("\n") { "https://example.com/r$it" }
        val erro = assertFailsWith<IntegridadeDeLinks.Falha> { auditar(texto) }
        assertEquals("link-integrity capacity exceeded: found 31 link occurrences; maximum is 30", erro.message)
    }

    @Test
    fun `listagem filtra, ordena e pagina como o canonico`() {
        val registro = RegistroEmMemoria()
        // Links afastados mais que a janela de contexto, para que a busca não
        // case um link pelo contexto do vizinho.
        val separador = " palavra".repeat(40) + " "
        auditar(
            "[a](https://example.com/a)$separador[b](https://example.com/b)$separador[c](https://example.com/c)",
            registro,
        )
        val pagina = IntegridadeDeLinks.listar(IntegridadeDeLinks.PedidoDeListagem(limite = 2), registro)
        assertEquals(3, pagina.total)
        assertEquals(2, pagina.itens.size)
        assertEquals("2", pagina.proximoCursor)
        assertEquals(pagina.itens.map { it.linkId }.sortedWith(OrdemRust), pagina.itens.map { it.linkId })
        val resto = IntegridadeDeLinks.listar(IntegridadeDeLinks.PedidoDeListagem(limite = 2, cursor = "+2"), registro)
        assertEquals(1, resto.itens.size)
        assertNull(resto.proximoCursor)
        assertFailsWith<IntegridadeDeLinks.Falha> {
            IntegridadeDeLinks.listar(IntegridadeDeLinks.PedidoDeListagem(cursor = "-1"), registro)
        }
        val busca = IntegridadeDeLinks.listar(IntegridadeDeLinks.PedidoDeListagem(consulta = " EXAMPLE.COM/B "), registro)
        assertEquals(listOf("https://example.com/b"), busca.itens.map { it.urlNormalizada })
    }

    @Test
    fun `a linha e a entrada do diario sao gravadas na mesma transacao`() {
        // Achado do Codex na #78: com o diário fora da transação, a linha ficava gravada quando a entrada falhava.
        val registro = RegistroEmMemoria()
        val linha = auditar("Ver [fonte oficial](https://example.com/a).", registro).linhas.single()
        IntegridadeDeLinks.revisar(
            IntegridadeDeLinks.PedidoDeRevisao(
                linha.linkId, DecisaoDeRevisao.QUARENTENA, "aguardando conferência", "operator", IntegridadeDeLinks.IdentidadeDaEvidencia.de(linha),
            ),
            registro,
            agora,
        )
        IntegridadeDeLinks.proporCorrecoes(
            IntegridadeDeLinks.PedidoDeCorrecao(linha.linkId, "crossref"),
            registro,
            { _, _, _ -> emptyList() },
            agora,
        )
        assertEquals(setOf("audit", "review", "correction_candidates"), registro.eventos.map { it.first }.toSet())
        assertEquals(emptyList<String>(), registro.foraDaTransacao)
    }

    @Test
    fun `propostas de correcao trazem substituir, remover e reescrever`() {
        val registro = RegistroEmMemoria()
        val linha = auditar("Ver [fonte oficial](https://example.com/a).", registro).linhas.single()
        val buscador = IntegridadeDeLinks.BuscadorDeEvidencia { consulta, provedor, limite ->
            assertEquals("fonte oficial", consulta)
            assertEquals("crossref", provedor)
            assertEquals(8, limite)
            listOf(evidencia("https://doi.org/10.1/x"), evidencia("https://doi.org/10.1/x"))
        }
        val proposta = IntegridadeDeLinks.proporCorrecoes(
            IntegridadeDeLinks.PedidoDeCorrecao(linha.linkId, " crossref "),
            registro,
            buscador,
            agora,
        )
        assertEquals(
            listOf(AcaoDeCorrecao.SUBSTITUIR, AcaoDeCorrecao.REMOVER, AcaoDeCorrecao.REESCREVER),
            proposta.candidatosDeCorrecao.map { it.acao },
        )
    }

    // ── a defesa de rede ─────────────────────────────────────────────────────

    private fun ip(literal: String): ByteArray = InetAddress.getByName(literal).address

    @Test
    fun `faixas bloqueadas do canonico`() {
        for (bloqueado in listOf(
            "127.0.0.1", "0.0.0.0", "10.0.0.1", "192.168.1.10", "172.16.0.1", "100.64.0.1", "169.254.169.254",
            "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1",
            "fe80::1", "ff02::1", "2001:db8::1",
        )) {
            assertTrue(RedePublica.ipBloqueado(ip(bloqueado)), bloqueado)
        }
        // `::127.0.0.1` e `::ffff:127.0.0.1`: o JVM devolve 4 bytes para o
        // segundo, então os dois são montados à mão em 16 bytes.
        val compativel = ByteArray(16).also { it[12] = 127; it[15] = 1 }
        val mapeado = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 127; it[15] = 1 }
        assertTrue(RedePublica.ipBloqueado(compativel))
        assertTrue(RedePublica.ipBloqueado(mapeado))
        assertFalse(RedePublica.ipBloqueado(ip("93.184.216.34")))
        assertFalse(RedePublica.ipBloqueado(ip("2606:2800:220:1::1")))
    }

    @Test
    fun `faixas IPv6 locais que o canonico deixa passar`() {
        // O site-local obsoleto, e o IPv4 privado embutido no NAT64 e no 6to4.
        for (bloqueado in listOf("fec0::1", "feff::1", "64:ff9b::a00:1", "64:ff9b::c0a8:101", "2002:c0a8:101::1")) {
            assertTrue(RedePublica.ipBloqueado(ip(bloqueado)), bloqueado)
        }
        // Controle: o IPv4 público embutido passa, porque numa rede com DNS64
        // todo site só IPv4 resolve para 64:ff9b::; e o vizinho de fec0::/10
        // fora da faixa também passa.
        for (publico in listOf("64:ff9b::5db8:d822", "2002:5db8:d822::1", "fe00::1")) {
            assertFalse(RedePublica.ipBloqueado(ip(publico)), publico)
        }
        // O prefixo NAT64 de uso local (RFC 8215) é recusado inteiro, em
        // qualquer leiaute do RFC 6052, inclusive com IPv4 público dentro: o
        // endereço não diz o comprimento do prefixo, e o prefixo é local.
        for (bloqueado in listOf(
            "64:ff9b:1:c0a8:1:100::",
            "64:ff9b:1::c0a8:101",
            "64:ff9b:1::5db8:d822",
            "64:ff9b:1:5db8:d8:2200::",
            "64:ff9b:1:ffff:ffff:ffff:ffff:ffff",
        )) {
            assertTrue(RedePublica.ipBloqueado(ip(bloqueado)), bloqueado)
        }
        // Controle: o vizinho fora do /48 é julgado pelo IPv4 embutido.
        assertFalse(RedePublica.ipBloqueado(ip("64:ff9b:2::5db8:d822")))
    }

    @Test
    fun `nomes locais e dominio que resolve para rede privada sao recusados`() {
        val nenhum = RedePublica.ResolvedorDeNomes { null }
        assertEquals("endereco local bloqueado por seguranca", RedePublica.motivoDeRecusa("http://localhost:8787/x", analisador, nenhum))
        assertEquals("endereco local bloqueado por seguranca", RedePublica.motivoDeRecusa("http://impressora.local/x", analisador, nenhum))
        assertEquals(
            "somente links http:// ou https:// podem ser auditados",
            RedePublica.motivoDeRecusa("ftp://example.com/x", analisador, nenhum),
        )
        val privado = RedePublica.ResolvedorDeNomes { listOf(ip("10.0.0.1")) }
        assertEquals(
            "dominio resolve para IP privado/reservado bloqueado por seguranca",
            RedePublica.motivoDeRecusa("https://10.0.0.1.example.com/source", analisador, privado),
        )
        val publico = RedePublica.ResolvedorDeNomes { listOf(ip("93.184.216.34")) }
        assertNull(RedePublica.motivoDeRecusa("https://example.com/source", analisador, publico))
        // Falha de resolução não bloqueia, como no canônico.
        assertNull(RedePublica.motivoDeRecusa("https://example.com/source", analisador, nenhum))
    }

    @Test
    fun `IP literal e julgado sem resolver nome`() {
        val literal = IntegridadeDeLinks.AnalisadorDeUrl { url ->
            urlDeTeste(url, ipDoHost = ip("127.0.0.1"))
        }
        val proibido = RedePublica.ResolvedorDeNomes { error("IP literal não pode ir ao DNS") }
        assertEquals(
            "IP privado, reservado ou local bloqueado por seguranca",
            RedePublica.motivoDeRecusa("http://127.0.0.1/test", literal, proibido),
        )
    }
}
