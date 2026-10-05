package dev.lcv.maestro.ui

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import android.content.Context
import dev.lcv.maestro.BuscaDaTela
import dev.lcv.maestro.CofreDaTela
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.Navegador
import dev.lcv.maestro.ReconciliacaoDaAbertura
import dev.lcv.maestro.maestro
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import dev.lcv.maestro.provedores.ColetaCancelada
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.ResolvedorPublico
import dev.lcv.maestro.provedores.Resultado
import dev.lcv.maestro.provedores.Uso
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.seguranca.Remocao
import dev.lcv.maestro.sessao.Agendador
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.ArmazemDeEvidenciasEmArquivo
import dev.lcv.maestro.sessao.ArtefatoEntidade
import dev.lcv.maestro.sessao.BancoDaSessao
import dev.lcv.maestro.sessao.Campo
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.EventoEntidade
import dev.lcv.maestro.sessao.FormatoDeInstante
import dev.lcv.maestro.sessao.LinksDaSessao
import dev.lcv.maestro.sessao.PedidoDeConfiguracoes
import dev.lcv.maestro.sessao.Reconciliacao
import dev.lcv.maestro.sessao.RepositorioDeArtefatos
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.Retomada
import dev.lcv.maestro.sessao.SessaoEntidade
import dev.lcv.maestro.sessao.Taxas
import dev.lcv.maestro.sessao.TesteDeChaves
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.awaitCancellation
import okhttp3.Dns
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * O cofre visto pela tela, em memória: o `CofreDeChaves` é preso ao hardware
 * e já existe no processo (o `Application` o criou); a tela só depende destas
 * cinco perguntas (especificação, seção 8).
 */
internal class CofreFalso : CofreDaTela {
    val presentes: MutableMap<Provedor, Boolean?> = Collections.synchronizedMap(Provedor.entries.associateWith<Provedor, Boolean?> { false }.toMutableMap())
    /** O que `guardar` responde, na ordem; vazio é "guardada". */
    val respostas: MutableList<Guarda> = CopyOnWriteArrayList()
    val guardadas: MutableList<Pair<Provedor, String>> = CopyOnWriteArrayList()

    @Volatile var nivel: NivelDoCofre? = null

    @Volatile var trava: Boolean = true

    /** Posta, a próxima leitura das chaves tira a foto e espera aqui antes de devolver: a releitura antiga e lenta (#81). */
    @Volatile var chavesPresas: java.util.concurrent.CountDownLatch? = null

    override suspend fun chaves(): Map<Provedor, Boolean?> {
        val foto = synchronized(presentes) { presentes.toMap() }
        val trava = synchronized(this) { chavesPresas.also { chavesPresas = null } }
        trava?.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return foto
    }

    override suspend fun guardar(provedor: Provedor, chave: String): Guarda {
        guardadas += provedor to chave
        val resposta = if (respostas.isEmpty()) Guarda.Guardada(NivelDoCofre.AMBIENTE_SEGURO) else respostas.removeAt(0)
        if (resposta is Guarda.Guardada) {
            presentes[provedor] = true
            nivel = resposta.nivel
        }
        return resposta
    }

    /** Posto, a remoção falha com este motivo, como o disco que não grava. */
    @Volatile var falhaAoApagar: String? = null

    override suspend fun apagar(provedor: Provedor): Remocao {
        falhaAoApagar?.let { return Remocao.Falhou(it) }
        presentes[provedor] = false
        return Remocao.Removida
    }

    override suspend fun nivel(): NivelDoCofre? = nivel

    override fun travaDeTela(): Boolean = trava
}

/**
 * O agendador sem WorkManager de verdade: registra o que a tela pediu e, no
 * cancelamento, o status que a linha tinha naquele instante — a ordem
 * "grava, depois cancela o trabalho" é o que se prova com ele.
 */
internal class AgendadorFalso(
    workManager: WorkManager,
    private val sessoes: () -> RepositorioDeSessoes,
    private val anexos: () -> AnexosDaSessao,
) : Agendador(workManager) {
    val enfileiradas: MutableList<String> = CopyOnWriteArrayList()
    val canceladas: MutableList<Pair<String, String?>> = CopyOnWriteArrayList()

    /** Quantos anexos a sessão já tinha no instante de cada enfileiramento: o worker só os lê depois dele. */
    val anexosAoEnfileirar: MutableList<Int> = CopyOnWriteArrayList()

    @Volatile var parada: Int? = null

    override fun enfileirar(sessaoId: String): UUID {
        anexosAoEnfileirar += anexos().daSessao(sessaoId).size
        enfileiradas += sessaoId
        return UUID.randomUUID()
    }

    override fun cancelar(sessaoId: String) {
        canceladas += sessaoId to sessoes().carregar(sessaoId)?.status
    }

    /**
     * Posta, a consulta ao WorkManager falha como o `get()` do futuro entrega a falha do banco dele: embrulhada em
     * `ExecutionException`. O `BancoCheio` não alcança o banco do WorkManager (#80).
     */
    @Volatile var falhaAoConsultar: Exception? = null

    override fun viva(sessaoId: String): Boolean {
        falhaAoConsultar?.let { throw it }
        return super.viva(sessaoId)
    }

    /** Quantas vezes a parada foi consultada: a que falhou espera a volta da tela (#81). */
    val consultasDaParada = java.util.concurrent.atomic.AtomicInteger(0)

    override fun ultimaParada(sessaoId: String): Int? {
        consultasDaParada.incrementAndGet()
        falhaAoConsultar?.let { throw it }
        return parada
    }
}

/** O navegador do sistema nos testes: anota a URL e responde [falha]; nenhum teste abre navegador. */
internal class NavegadorFalso : Navegador {
    val abertas: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var falha: String? = null

    /** O que o teste quer ver no instante do disparo, como o banco no momento em que o app pode morrer. */
    @Volatile var aoAbrir: (() -> Unit)? = null

    override fun abrir(contexto: Context, url: String): String? {
        aoAbrir?.invoke()
        abertas += url
        return falha
    }
}

/**
 * Um cenário por teste: o Room real num arquivo temporário, o relógio
 * injetado, o cofre e o agendador dublês, o teste de chaves sem rede, a
 * busca e o navegador dublês, e um autenticador que responde o que o teste
 * mandar.
 */
internal class Cenario {
    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val arquivo = File(contexto.cacheDir, "tela-${UUID.randomUUID()}.db")
    private var instante: Instant = Instant.parse("2026-09-28T12:00:00Z")
    val relogio: () -> Instant = { synchronized(this) { instante.also { instante = instante.plusSeconds(1) } } }

    /** Ligado, toda escrita nas tabelas do aplicativo falha como o SQLite sem espaço. */
    val bancoCheio = BancoCheio()

    // O `BancoDaSessao.abrir` da produção, com o `openHelperFactory` que pode encher o banco.
    val banco: BancoDaSessao = Room.databaseBuilder(contexto, BancoDaSessao::class.java, arquivo.absolutePath).openHelperFactory(bancoCheio).build()
    val sessoes = RepositorioDeSessoes(banco, relogio)
    val artefatos = RepositorioDeArtefatos(banco, relogio)
    val retomada = Retomada(banco, sessoes, artefatos, relogio)

    // O cofre real do processo só é passado porque o construtor o pede; as telas perguntam ao dublê.
    val configuracoes = RepositorioDeConfiguracoes(banco, contexto.maestro.cofre, relogio)
    val cofre = CofreFalso()
    val agendador = AgendadorFalso(WorkManager.getInstance(contexto), { sessoes }, { anexos })
    val testadas: MutableList<Provedor> = CopyOnWriteArrayList()
    val testeDeChaves = TesteDeChaves { provedor, _, _ ->
        testadas += provedor
        Resultado.Concluida("OK", Uso(tokensDeEntrada = 10, tokensDeSaida = 1))
    }
    val pastaDosAnexos = File(contexto.cacheDir, "anexos-${UUID.randomUUID()}")
    val anexos = AnexosDaSessao(banco, pastaDosAnexos, relogio)
    val links = LinksDaSessao(banco, relogio)
    val pastaDasEvidencias = File(contexto.cacheDir, "evidencias-${UUID.randomUUID()}")
    val evidencias = ArmazemDeEvidenciasEmArquivo(banco, pastaDasEvidencias, relogio)

    /**
     * A regra de rede pública com um DNS de teste: `interno.exemplo` resolve
     * para rede privada; o resto não resolve, como num aparelho sem rede, e a
     * regra deixa passar (falha de resolução não bloqueia, como no canônico).
     */
    val importacao = ImportacaoDoOperador(
        ResolvedorPublico(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    if (hostname == "interno.exemplo") listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 0, 0, 1))) else throw UnknownHostException(hostname)
            },
        ),
    )

    /** O que a busca dublê recebeu (consulta, provedor) e o que ela devolve. */
    val buscas: MutableList<Pair<String, String>> = CopyOnWriteArrayList()

    @Volatile var resultadosDaBusca: List<RegistroDeEvidencia> = emptyList()

    /**
     * Posta, a busca fica presa no "HTTP" até o cancelamento dela (ou dez
     * segundos): é a busca real, que só para pelo `cancelarTudo`. Cancelada,
     * ela lança `ColetaCancelada`, como a real.
     */
    @Volatile var buscaPresa: CountDownLatch? = null

    /**
     * Posto, a busca presa devolve os resultados mesmo cancelada: a resposta HTTP já tinha chegado, e
     * não há chamada para o cancelamento interromper.
     */
    @Volatile var buscaTerminouAntes: Boolean = false

    /** Posto, a busca falha ao guardar os resultados, como a real com o disco cheio: a `IOException` do armazém sai crua. */
    @Volatile var discoDaBusca: IOException? = null
    val buscasCanceladas = AtomicInteger()
    val navegador = NavegadorFalso()

    /** A reconciliação da abertura sobre este banco, como a do `MaestroApplication` sobre o do processo (#80). */
    val reconciliacao = Reconciliacao(banco, sessoes, retomada, agendador, { cofre.chaves() }, evidencias, anexos, relogio)
    val abertura = ReconciliacaoDaAbertura { reconciliacao.naAbertura() }
    val dependencias = Dependencias(
        sessoes, artefatos, retomada, configuracoes, agendador, cofre, testeDeChaves, anexos,
        links = links,
        evidencias = evidencias,
        importacao = importacao,
        busca = {
            // Como a `Fabrica.buscaDeEvidencias`, que lê o e-mail de contato do agente no Room ao montar a busca.
            configuracoes.carregar()
            BuscaDaTela(
                IntegridadeDeLinks.BuscadorDeEvidencia { consulta, provedor, _ ->
                    buscas += consulta to provedor
                    buscaPresa?.let { presa ->
                        presa.await(10, TimeUnit.SECONDS)
                        if (buscasCanceladas.get() > 0 && !buscaTerminouAntes) throw ColetaCancelada()
                    }
                    discoDaBusca?.let { throw it }
                    resultadosDaBusca
                },
                cancelar = {
                    buscasCanceladas.incrementAndGet()
                    buscaPresa?.countDown()
                },
            )
        },
        navegador = navegador,
        falhasDaAbertura = abertura.falhas,
        relogio = relogio,
    )

    val autenticacoes = AtomicInteger()

    @Volatile var autentica: Boolean = true

    /** Quando posto, a autenticação fica aberta até o teste o completar: o prompt que a pessoa ainda não respondeu. */
    @Volatile var portao: CompletableDeferred<Boolean>? = null
    val autenticador = Autenticador {
        autenticacoes.incrementAndGet()
        portao?.await() ?: autentica
    }

    /**
     * Fecha o banco depois do teste **e** depois de a regra do Compose desmontar a
     * Activity: a regra de um teste é encadeada por fora dela (`RuleChain`), porque um
     * `@After` roda com a composição viva e as consultas observadas do Room abertas.
     */
    val fechamento: TestRule = object : ExternalResource() {
        override fun after() = fechar()
    }

    fun fechar() {
        banco.close()
        pastaDosAnexos.deleteRecursively()
        pastaDasEvidencias.deleteRecursively()
        arquivo.delete()
        File(arquivo.path + "-wal").delete()
        File(arquivo.path + "-shm").delete()
    }

    fun agora(): String = FormatoDeInstante.iso(relogio())

    /** Configurações gravadas com teto positivo: sem ele, o núcleo recusa iniciar. */
    fun configurar(teto: String = "5", minutos: Int? = null) {
        configuracoes.salvar(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal(teto), tetoDeMinutos = Campo.Presente(minutos)))
    }

    fun chaves(vararg provedores: Provedor) {
        provedores.forEach { cofre.presentes[it] = true }
    }

    /** Uma sessão gravada direto no banco, no status que o teste precisa. */
    fun sessao(
        status: String,
        teto: String = "5",
        custo: String = "0",
        erro: String? = null,
        textoFinal: String? = null,
        textoAtual: String = "Texto atual da sessão.",
        agentes: List<Provedor> = listOf(Provedor.CLAUDE, Provedor.CODEX),
        titulo: String = "Artigo de teste",
    ): String {
        val id = "android-${UUID.randomUUID()}"
        val em = agora()
        banco.sessoes().inserir(
            SessaoEntidade(
                id = id,
                titulo = titulo,
                pedido = "Pedido de teste.",
                protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
                agenteInicial = agentes.first().agente,
                liderDoCiclo = agentes.first().agente,
                agentesAtivosJson = RepositorioDeSessoes.agentesJson(agentes),
                status = status,
                textoAtual = textoAtual,
                textoFinal = textoFinal,
                erro = erro,
                custoObservadoE8 = Dinheiro.paraE8(BigDecimal(custo)),
                tetoDeCustoE8 = Dinheiro.paraE8(BigDecimal(teto)),
                taxasJson = Taxas.paraJson(Taxas.PADRAO),
                modelosJson = RepositorioDeSessoes.modelosJson(),
                criadaEm = em,
                atualizadaEm = em,
            ),
        )
        return id
    }

    fun evento(sessaoId: String, status: String, mensagem: String, agente: Provedor? = null, custo: String? = null) {
        banco.eventos().inserir(
            EventoEntidade(
                sessaoId = sessaoId,
                em = agora(),
                agente = agente?.agente,
                status = status,
                mensagem = mensagem,
                custoE8 = custo?.let { Dinheiro.paraE8(BigDecimal(it)) },
            ),
        )
    }

    /**
     * As linhas de link de [texto], gravadas pelo motor real como a auditoria
     * da sessão as grava; sem rede, a coleta falha com `timeout`. [texto] tem
     * de ser o texto atual (ou final) da sessão para as linhas serem dela.
     */
    fun auditarLinks(
        sessaoId: String,
        texto: String,
        coletor: IntegridadeDeLinks.ColetorDeEvidencia = IntegridadeDeLinks.ColetorDeEvidencia { throw IntegridadeDeLinks.Falha("timeout") },
    ) {
        IntegridadeDeLinks.auditar(texto, AnalisadorDeUrlOkHttp, coletor, links.registro(sessaoId), relogio)
    }

    /**
     * Uma gravação nas linhas de link sem lê-las, como a auditoria de outra sessão faz: o `InvalidationTracker` avisa
     * quem observa a tabela.
     */
    fun tocarLinks() {
        banco.runInTransaction { banco.openHelper.writableDatabase.execSQL("UPDATE links SET linkId = linkId") }
    }

    /**
     * O texto atual da sessão trocado como o checkpoint de um turno o troca,
     * dentro de uma transação do Room, para a tela que observa a linha ver a
     * mudança.
     */
    fun mudarTextoAtual(sessaoId: String, texto: String) {
        banco.runInTransaction {
            banco.openHelper.writableDatabase.execSQL("UPDATE sessoes SET textoAtual = ? WHERE id = ?", arrayOf<Any>(texto, sessaoId))
        }
    }

    /** Um resultado de busca do Crossref como a `BuscaDeEvidencias` o devolve. */
    fun resultadoDeBusca(url: String, titulo: String): RegistroDeEvidencia = RegistroDeEvidencia(
        id = "busca-${UUID.randomUUID()}", versaoDoEsquema = "web_evidence.v1", estado = EstadoDaEvidencia.PRONTA,
        url = url, metodo = MetodoHttp.GET, modoDeAcesso = ModoDeAcesso.API_OFICIAL, status = 200,
        urlFinal = url, titulo = titulo, tipoDeConteudo = "application/json", sha256 = "1".repeat(64),
        coletadaEm = agora(), expiraEm = null, validadeDoCache = "P30D", estadoDoCache = EstadoDoCache.FRESCO,
        estadoDoRobots = EstadoDoRobots.NAO_SE_APLICA, estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
        estadoDeInteracao = EstadoDeInteracao.NENHUMA, resolvidaPorPessoa = false, bytes = 10, duracaoMs = 5,
        cadeiaDeRedirecionamento = emptyList(), comandoCurl = null, provedor = "crossref", consulta = "consulta",
        nomeDoArtefato = null, notas = emptyList(), criadaEm = agora(), atualizadaEm = agora(),
    )

    fun artefato(sessaoId: String, turno: Int, agente: Provedor, texto: String, anterior: String? = null): String {
        val id = "artifact-${UUID.randomUUID()}"
        banco.artefatos().inserir(
            ArtefatoEntidade(
                id = id,
                sessaoId = sessaoId,
                ciclo = 1,
                turno = turno,
                agente = agente.agente,
                papel = if (anterior == null) "draft" else "revision",
                status = "READY",
                titulo = "Artigo de teste",
                textoAceito = texto,
                relatorioDeRevisaoJson = "{\"decision\":\"READY\"}",
                auditoriaDeLinksJson = "[]",
                custoE8 = Dinheiro.paraE8(BigDecimal("0.0123")),
                modelo = agente.modelo,
                artefatoAnteriorId = anterior,
                bytesDoConteudo = 2048,
                criadoEm = agora(),
            ),
        )
        return id
    }
}

/**
 * O exemplo oficial do canônico (`docs/examples/citation-manifest.example.json`): uma citação, uma fonte.
 * O `protocol_hash` é o do protocolo que as sessões de teste usam: sem ele, a sessão recusa o manifesto
 * antes de começar (`CitacoesDaSessao.recusaDoVinculo`).
 */
internal val MANIFESTO_DE_EXEMPLO: String = """
    {"schema_version": "citation_manifest.v1",
     "protocol_hash": "${FormatoDoRegistro.sha256(RepositorioDeConfiguracoes.PROTOCOLO_PADRAO)}",
     "citations": [{"schema_version": "citation.v1", "claim_id": "claim-001", "citation_type": "direct_quote",
       "author_display": "Silva, Maria", "author_key": "SILVA", "year": "2026", "locator": "p. 12",
       "source_id": "source-001", "source_access": "full_document_opened", "verification_status": "verified",
       "risk_if_wrong": "medium", "original_text": "Trecho literal comprovado pelo operador."}],
     "sources": [{"source_id": "source-001", "source_type": "book",
       "authors": [{"author_display": "Silva, Maria", "author_key": "SILVA"}],
       "title": "Obra de exemplo", "place": "Sao Paulo", "publisher": "Editora Exemplo", "year": "2026",
       "verification_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
       "verification_status": "verified", "prohibited": false}]}
""".trimIndent()

/** Um arquivo de teste com [nome] e [conteudo], numa pasta própria do cache, para o seletor de teste devolver. */
internal fun Cenario.arquivo(nome: String, conteudo: ByteArray): File =
    File(contexto.cacheDir, "doc-${UUID.randomUUID()}").apply { mkdirs() }.resolve(nome).apply { writeBytes(conteudo) }

/**
 * Um documento cujo provedor nega acesso (decisão 26 do operador, #82): o provedor de contatos do sistema exige
 * `READ_CONTACTS` e `WRITE_CONTACTS`, que o aplicativo não declara, e abri-lo lança `SecurityException` ("Permission
 * Denial"), o mesmo erro do provedor de documentos que nega o documento escolhido. É um provedor real, e não uma
 * costura só de teste no código.
 */
internal val DOCUMENTO_NEGADO: Uri = ContactsContract.Contacts.CONTENT_URI

/** O começo da mensagem do `SecurityException` de [DOCUMENTO_NEGADO]. */
internal const val ACESSO_NEGADO: String = "Permission Denial"

/**
 * Um resolvedor cujo provedor, ao abrir qualquer documento, lança [erro]: o defeito que não é de armazenamento nem de
 * acesso negado, e que o classificador do documento deixa seguir adiante (decisão 26 do operador, #82). É a API oficial
 * `ContentResolver.wrap`, que chama o provedor direto, sem outro processo nem permissão no caminho.
 */
internal fun resolvedorQueLanca(erro: RuntimeException): ContentResolver = ContentResolver.wrap(object : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = throw erro
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
})

/** Um documento de [resolvedorQueLanca]: `content:`, porque o `file:` o `ContentResolver` abre sem passar pelo provedor. */
internal val DOCUMENTO_DO_PROVEDOR_QUE_LANCA: Uri = Uri.parse("content://dev.lcv.maestro.teste/documento")

/**
 * O seletor de documentos do sistema nos testes, pela API oficial
 * (`ActivityResultRegistry`): devolve o que o teste mandar em [resposta] e
 * guarda o que a tela pediu (o nome sugerido, os tipos aceitos).
 */
internal class SeletorDeTeste(var resposta: Uri? = null) : ActivityResultRegistry() {
    val pedidos: MutableList<Any?> = CopyOnWriteArrayList()

    /** Posto, o resultado espera [entregar], como o seletor do sistema, que demora: a tela pode mudar nesse meio-tempo. */
    @Volatile var adiado: Boolean = false
    private var pendente: Int? = null

    override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
        pedidos += input
        if (adiado) pendente = requestCode else dispatchResult(requestCode, resposta)
    }

    /** Entrega o resultado adiado; na linha principal (`runOnUiThread`). */
    fun entregar() {
        dispatchResult(checkNotNull(pendente) { "nenhum pedido adiado" }, resposta)
        pendente = null
    }
}

/**
 * O pedido de teclado do campo em foco, interceptado: os testes de tela rodam sem o teclado do sistema, e o texto
 * entra pela ação semântica do campo (`performTextInput`), como antes. Com o teclado no recuo da casca (MAEANDR-31), a
 * área das telas encolhe enquanto ele sobe, e o toque logo depois de digitar caía fora do botão no emulador da CI: o
 * botão saía do lugar entre o apertar e o soltar (dois casos da `SessoesScreenTest` na CI da #93). O teclado de
 * verdade é do `TecladoTest`, na `MainActivity`.
 */
private val SEM_TECLADO = object : PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: PlatformTextInputMethodRequest,
        nextHandler: PlatformTextInputSession,
    ): Nothing = awaitCancellation()
}

/** A casca inteira, com o cenário no lugar do grafo do processo, sem o teclado do sistema e, se houver, o seletor de teste. */
internal fun ComposeContentTestRule.abrir(cenario: Cenario, sessaoPedida: String? = null, seletor: SeletorDeTeste? = null) {
    setContent {
        InterceptPlatformTextInput(SEM_TECLADO) {
            MaestroTheme {
                if (seletor == null) {
                    MaestroApp(cenario.dependencias, cenario.autenticador, "teste", sessaoPedida) {}
                } else {
                    val dono = object : ActivityResultRegistryOwner {
                        override val activityResultRegistry: ActivityResultRegistry = seletor
                    }
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides dono) {
                        MaestroApp(cenario.dependencias, cenario.autenticador, "teste", sessaoPedida) {}
                    }
                }
            }
        }
    }
}

/**
 * Espera o texto aparecer. Um aviso chega por corrotina e entra na fila do
 * `Snackbar`, que mostra um por vez, cerca de 4 s cada: o segundo aviso de um
 * teste só aparece quando o primeiro sai, e o prazo cobre essa espera num
 * emulador lento.
 */
internal fun ComposeContentTestRule.esperarTexto(texto: String, substring: Boolean = false) {
    waitUntil(15_000) { onAllNodesWithText(texto, substring = substring).fetchSemanticsNodes().isNotEmpty() }
}
