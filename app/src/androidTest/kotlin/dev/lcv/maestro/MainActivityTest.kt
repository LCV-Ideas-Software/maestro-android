package dev.lcv.maestro

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Dinheiro
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.FormatoDeInstante
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.SessaoEntidade
import dev.lcv.maestro.sessao.Taxas
import dev.lcv.maestro.ui.Marcas
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O toque na notificação de uma sessão (plano do `:app`, emenda A10): abre a
 * tela da sessão no arranque frio e, com o aplicativo aberto, por
 * `onNewIntent`; um pedido já atendido não volta a empilhar a sessão quando a
 * Activity é recriada. Usa a Activity de verdade, sobre o grafo do processo.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val regra = createEmptyComposeRule()

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val grafo = contexto.maestro.grafo
    private val criadas = mutableListOf<String>()

    /** Uma sessão parada por erro no banco de produção: a reconciliação não a toca. */
    private fun sessao(titulo: String): String {
        val id = "android-notificacao-${UUID.randomUUID()}"
        val em = FormatoDeInstante.iso(Instant.now())
        grafo.banco.sessoes().inserir(
            SessaoEntidade(
                id = id,
                titulo = titulo,
                pedido = "p",
                protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
                agenteInicial = Provedor.CLAUDE.agente,
                liderDoCiclo = Provedor.CLAUDE.agente,
                agentesAtivosJson = RepositorioDeSessoes.agentesJson(listOf(Provedor.CLAUDE, Provedor.CODEX)),
                status = Estados.ERRO,
                erro = "Falha qualquer.",
                tetoDeCustoE8 = Dinheiro.paraE8(BigDecimal.ONE),
                taxasJson = Taxas.paraJson(Taxas.PADRAO),
                modelosJson = RepositorioDeSessoes.modelosJson(),
                criadaEm = em,
                atualizadaEm = em,
            ),
        )
        criadas += id
        return id
    }

    @After
    fun apagar() {
        criadas.forEach { grafo.banco.openHelper.writableDatabase.execSQL("DELETE FROM sessoes WHERE id = ?", arrayOf(it)) }
    }

    private fun esperarTag(marca: String) {
        regra.waitUntil(10_000) { regra.onAllNodesWithTag(marca).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun oToqueAbreASessaoNoArranqueENaNovaIntencaoENaoVoltaNaRecriacao() {
        val primeira = sessao("Sessão A da notificação")
        val segunda = sessao("Sessão B da notificação")
        ActivityScenario.launch<MainActivity>(MainActivity.intencao(contexto, primeira)).use { cenario ->
            // Arranque frio pelo toque: a tela da sessão, com o botão de voltar à inicial.
            esperarTag(Marcas.METRICA_CUSTO)
            regra.onNodeWithTag(Marcas.VOLTAR).performClick()
            esperarTag(Marcas.INICIAR)

            // Recriada (rotação, tema, processo restaurado), a Activity não reabre o pedido já atendido.
            cenario.recreate()
            esperarTag(Marcas.INICIAR)
            regra.waitForIdle()
            regra.onNodeWithTag(Marcas.METRICA_CUSTO).assertDoesNotExist()

            // Com o aplicativo aberto, o toque chega por `onNewIntent` e abre a outra sessão.
            contexto.startActivity(MainActivity.intencao(contexto, segunda))
            esperarTag(Marcas.METRICA_CUSTO)
        }
    }
}
