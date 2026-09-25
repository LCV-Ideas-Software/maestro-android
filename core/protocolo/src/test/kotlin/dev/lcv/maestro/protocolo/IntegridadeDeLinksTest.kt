package dev.lcv.maestro.protocolo

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O motor de integridade de links e a defesa de rede. Os sete testes de
 * `link_integrity.rs` (linhas 976–1068) e as faixas de IP do teste
 * `link_audit_blocks_local_and_private_targets` (`lib.rs`, 1341) estão aqui;
 * os que dependem do parser de URL real e da sondagem ficam com eles, no
 * `:core:provedores`. As regras aqui correm com dublês.
 */
class IntegridadeDeLinksTest {

    private companion object {
        /** Hashes de conteúdo no formato real: 64 dígitos hexadecimais minúsculos. */
        val HASH_1 = "1".repeat(64)
        val HASH_2 = "2".repeat(64)
    }

    private val agora: Instant = Instant.parse("2026-09-24T12:00:00Z")

    /** Dublê do parser: `java.net.URI`, só para exercitar as regras em volta dele. */
    private val analisador = IntegridadeDeLinks.AnalisadorDeUrl { url ->
        try {
            val uri = URI(url)
            val esquema = uri.scheme?.lowercase() ?: return@AnalisadorDeUrl null
            val info = uri.rawUserInfo
            IntegridadeDeLinks.UrlAnalisada(
                esquema = esquema,
                host = uri.host,
                usuario = info?.substringBefore(':') ?: "",
                senha = info?.takeIf { ':' in it }?.substringAfter(':'),
                caminho = uri.rawPath ?: "",
                serializada = url,
            )
        } catch (erro: URISyntaxException) {
            null
        }
    }

    /** Registro em memória, com a mesma exclusão mútua que o do aparelho terá. */
    private class RegistroEmMemoria : IntegridadeDeLinks.RegistroDeLinks {
        val linhas = LinkedHashMap<String, LinhaDeLink>()
        val eventos = mutableListOf<Pair<String, String>>()
        override fun <T> emTransacao(bloco: () -> T): T = synchronized(this) { bloco() }
        override fun carregar(linkId: String): LinhaDeLink? = linhas[linkId]
        override fun salvar(linha: LinhaDeLink) {
            linhas[linha.linkId] = linha
        }
        override fun anotar(tipo: String, linha: LinhaDeLink) {
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

    // ── a suíte canônica ─────────────────────────────────────────────────────

    @Test
    fun `extracao preserva a ancora do markdown e o contexto`() {
        val links = IntegridadeDeLinks.extrair("A fonte [documento oficial](https://example.com/a) sustenta a frase.")
        assertEquals(1, links.size)
        assertEquals("documento oficial", links[0].textoDaAncora)
        assertTrue(links[0].textoAoRedor.contains("sustenta a frase"))
    }

    @Test
    fun `extracao nao duplica URL dentro de marcacao`() {
        val links = IntegridadeDeLinks.extrair(
            "[fonte](https://example.com/a) e <a href=\"https://example.com/b\">outra</a>",
        )
        assertEquals(2, links.size)
    }

    @Test
    fun `normalizacao recusa script e credenciais`() {
        fun recusada(url: String) =
            IntegridadeDeLinks.normalizar(url, analisador) is IntegridadeDeLinks.Normalizacao.Recusada
        assertTrue(recusada("javascript:alert(1)"))
        assertTrue(recusada("ftp://files.example.com/archive.zip"))
        assertTrue(recusada("tel:+5511999999999"))
        assertTrue(recusada("https://user:secret@example.com/"))
        assertFalse(recusada("https://example.com/a"))
        assertFalse(recusada("mailto:editor@example.com"))
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
    fun `a extracao conta toda ocorrencia antes do limite`() {
        val texto = (0..IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS)
            .joinToString("\n") { "[fonte $it](https://example.com/source)" }
        assertEquals(IntegridadeDeLinks.MAXIMO_DE_OCORRENCIAS + 1, IntegridadeDeLinks.contarOcorrencias(texto))
    }

    private val extraido = IntegridadeDeLinks.LinkExtraido(
        inicio = 0,
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
                revisor = "operator", urlNormalizadaEsperada = linha.urlNormalizada, sha256Esperado = linha.sha256,
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
                linha.linkId, DecisaoDeRevisao.ACEITAR, "fonte oficial confere", "operator", linha.urlNormalizada, HASH_1,
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
        ) = IntegridadeDeLinks.PedidoDeRevisao(linha.linkId, decisao, nota, revisor, url, sha)
        fun falha(pedido: IntegridadeDeLinks.PedidoDeRevisao) =
            assertFailsWith<IntegridadeDeLinks.Falha> { IntegridadeDeLinks.revisar(pedido, registro, agora) }.message
        assertEquals("reviewer identity is not allowlisted", falha(pedido(revisor = "chatgpt")))
        assertEquals("review note must contain at least 10 characters", falha(pedido(nota = "  curta   ")))
        assertEquals(
            "link URL or content hash changed since it was read; reload before reviewing",
            falha(pedido(sha = "outro")),
        )
        val quarentena = IntegridadeDeLinks.revisar(pedido(decisao = DecisaoDeRevisao.QUARENTENA), registro, agora)
        assertEquals("blocked", quarentena.tom)
        assertEquals(StatusDaRevisao.REJEITADA, quarentena.statusDaRevisao)
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
                    linha.urlNormalizada, linha.sha256,
                ),
                registro,
                agora,
            )
        }
        assertEquals("cannot accept a link that did not pass mechanical validation", erro.message)
    }

    private fun aceitar(linha: LinhaDeLink, registro: RegistroEmMemoria) = IntegridadeDeLinks.revisar(
        IntegridadeDeLinks.PedidoDeRevisao(
            linha.linkId, DecisaoDeRevisao.ACEITAR, "fonte oficial confere", "operator", linha.urlNormalizada, linha.sha256,
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
        val interacoes = EstadoDeInteracao.entries
            .filter { it != EstadoDeInteracao.NENHUMA && it != EstadoDeInteracao.RESOLVIDA_POR_PESSOA }
            .map { evidencia(url, interacao = it) }
        for (caso in estados + interacoes) {
            val registro = RegistroEmMemoria()
            val linha = auditarCom(caso, registro)
            assertEquals(200, linha.statusHttp)
            // A quarentena conta entre as bloqueadas no resumo da auditoria.
            if (linha.classificacao == ClassificacaoDoLink.EM_QUARENTENA) assertEquals("blocked", linha.tom, caso.toString())
            val erro = assertFailsWith<IntegridadeDeLinks.Falha>(caso.toString()) { aceitar(linha, registro) }
            assertEquals("cannot accept a link that did not pass mechanical validation", erro.message, caso.toString())
        }
        // A evidência que não terminou vai para quarentena antes de o código
        // HTTP guardado nela ser lido: vencida com 404 é bloqueada, e não
        // "não encontrada".
        val finais = setOf(EstadoDaEvidencia.PRONTA, EstadoDaEvidencia.BLOQUEADA, EstadoDaEvidencia.FALHOU)
        for (estado in EstadoDaEvidencia.entries.filter { it !in finais }) {
            val linha = auditarCom(evidencia(url, status = 404, estado = estado))
            assertEquals(ClassificacaoDoLink.EM_QUARENTENA, linha.classificacao, estado.toString())
            assertEquals("blocked", linha.tom, estado.toString())
        }
        // Controle: nos estados finais, o código HTTP segue a ordem do canônico.
        assertEquals(ClassificacaoDoLink.NAO_ENCONTRADO, auditarCom(evidencia(url, status = 404)).classificacao)
        assertEquals(
            ClassificacaoDoLink.PROIBIDO,
            auditarCom(evidencia(url, status = 403, estado = EstadoDaEvidencia.BLOQUEADA)).classificacao,
        )
        // Controle: interação que uma pessoa resolveu é aceita.
        val resolvida = RegistroEmMemoria()
        val linha = auditarCom(evidencia(url, interacao = EstadoDeInteracao.RESOLVIDA_POR_PESSOA), resolvida)
        assertEquals(StatusDaRevisao.ACEITA, aceitar(linha, resolvida).statusDaRevisao)
        // Controle: a mesma página sem interação pendente é aceita, inclusive
        // depois de uma quarentena, que troca a classificação exibida mas não
        // a mecânica.
        val registro = RegistroEmMemoria()
        val limpa = auditar("Ver [x](https://example.com/a).", registro).linhas.single()
        IntegridadeDeLinks.revisar(
            IntegridadeDeLinks.PedidoDeRevisao(
                limpa.linkId, DecisaoDeRevisao.QUARENTENA, "conferir depois", "operator", limpa.urlNormalizada, limpa.sha256,
            ),
            registro,
            agora,
        )
        assertEquals(StatusDaRevisao.ACEITA, aceitar(limpa, registro).statusDaRevisao)
    }

    @Test
    fun `aceite exige o hash do conteudo da evidencia`() {
        // Divergência do canônico: lá a revisão conferia `null` com `null`, e
        // o aceite sem hash sobrevivia a qualquer mudança do destino.
        for (sha in listOf(null, "", "sha-1", "A".repeat(64))) {
            val registro = RegistroEmMemoria()
            val linha = auditarCom(evidencia("https://example.com/a", sha = sha), registro)
            val erro = assertFailsWith<IntegridadeDeLinks.Falha>(sha.toString()) { aceitar(linha, registro) }
            assertEquals("cannot accept a link without the content hash of its evidence", erro.message)
        }
        // Controle: o mailto, que não é coletado, é aceito sem hash.
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
            IntegridadeDeLinks.UrlAnalisada("http", "127.0.0.1", "", null, "/", url, ip("127.0.0.1"))
        }
        val proibido = RedePublica.ResolvedorDeNomes { error("IP literal não pode ir ao DNS") }
        assertEquals(
            "IP privado, reservado ou local bloqueado por seguranca",
            RedePublica.motivoDeRecusa("http://127.0.0.1/test", literal, proibido),
        )
    }
}
