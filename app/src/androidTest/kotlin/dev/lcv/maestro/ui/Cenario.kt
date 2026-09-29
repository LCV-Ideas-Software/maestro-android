package dev.lcv.maestro.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import dev.lcv.maestro.CofreDaTela
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.maestro
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado
import dev.lcv.maestro.provedores.Uso
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.Agendador
import dev.lcv.maestro.sessao.ArtefatoEntidade
import dev.lcv.maestro.sessao.BancoDaSessao
import dev.lcv.maestro.sessao.Campo
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.EventoEntidade
import dev.lcv.maestro.sessao.FormatoDeInstante
import dev.lcv.maestro.sessao.PedidoDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeArtefatos
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.Retomada
import dev.lcv.maestro.sessao.SessaoEntidade
import dev.lcv.maestro.sessao.Taxas
import dev.lcv.maestro.sessao.TesteDeChaves
import java.io.File
import org.junit.rules.ExternalResource
import org.junit.rules.TestRule
import java.math.BigDecimal
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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

    override suspend fun chaves(): Map<Provedor, Boolean?> = synchronized(presentes) { presentes.toMap() }

    override suspend fun guardar(provedor: Provedor, chave: String): Guarda {
        guardadas += provedor to chave
        val resposta = if (respostas.isEmpty()) Guarda.Guardada(NivelDoCofre.AMBIENTE_SEGURO) else respostas.removeAt(0)
        if (resposta is Guarda.Guardada) {
            presentes[provedor] = true
            nivel = resposta.nivel
        }
        return resposta
    }

    override suspend fun apagar(provedor: Provedor): Boolean {
        presentes[provedor] = false
        return true
    }

    override suspend fun nivel(): NivelDoCofre? = nivel

    override fun travaDeTela(): Boolean = trava
}

/**
 * O agendador sem WorkManager de verdade: registra o que a tela pediu e, no
 * cancelamento, o status que a linha tinha naquele instante — a ordem
 * "grava, depois cancela o trabalho" é o que se prova com ele.
 */
internal class AgendadorFalso(workManager: WorkManager, private val sessoes: () -> RepositorioDeSessoes) : Agendador(workManager) {
    val enfileiradas: MutableList<String> = CopyOnWriteArrayList()
    val canceladas: MutableList<Pair<String, String?>> = CopyOnWriteArrayList()

    @Volatile var parada: Int? = null

    override fun enfileirar(sessaoId: String): UUID {
        enfileiradas += sessaoId
        return UUID.randomUUID()
    }

    override fun cancelar(sessaoId: String) {
        canceladas += sessaoId to sessoes().carregar(sessaoId)?.status
    }

    override fun ultimaParada(sessaoId: String): Int? = parada
}

/**
 * Um cenário por teste: o Room real num arquivo temporário, o relógio
 * injetado, o cofre e o agendador dublês, o teste de chaves sem rede e um
 * autenticador que responde o que o teste mandar.
 */
internal class Cenario {
    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val arquivo = File(contexto.cacheDir, "tela-${UUID.randomUUID()}.db")
    private var instante: Instant = Instant.parse("2026-09-28T12:00:00Z")
    val relogio: () -> Instant = { synchronized(this) { instante.also { instante = instante.plusSeconds(1) } } }

    val banco: BancoDaSessao = BancoDaSessao.abrir(contexto, arquivo)
    val sessoes = RepositorioDeSessoes(banco, relogio)
    val artefatos = RepositorioDeArtefatos(banco, relogio)
    val retomada = Retomada(banco, sessoes, artefatos, relogio)

    // O cofre real do processo só é passado porque o construtor o pede; as telas perguntam ao dublê.
    val configuracoes = RepositorioDeConfiguracoes(banco, contexto.maestro.cofre, relogio)
    val cofre = CofreFalso()
    val agendador = AgendadorFalso(WorkManager.getInstance(contexto)) { sessoes }
    val testadas: MutableList<Provedor> = CopyOnWriteArrayList()
    val testeDeChaves = TesteDeChaves { provedor, _, _ ->
        testadas += provedor
        Resultado.Concluida("OK", Uso(tokensDeEntrada = 10, tokensDeSaida = 1))
    }
    val dependencias = Dependencias(sessoes, artefatos, retomada, configuracoes, agendador, cofre, testeDeChaves, relogio)

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

/** A casca inteira, com o cenário no lugar do grafo do processo. */
internal fun ComposeContentTestRule.abrir(cenario: Cenario, sessaoPedida: String? = null) {
    setContent {
        MaestroTheme {
            MaestroApp(cenario.dependencias, cenario.autenticador, "teste", sessaoPedida) {}
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
