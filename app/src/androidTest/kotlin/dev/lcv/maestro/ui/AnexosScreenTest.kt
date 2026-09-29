package dev.lcv.maestro.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.Estados
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Os anexos e o manifesto de citações (especificação, seção 2.2; MAEANDR-18):
 * o que a tela diz do manifesto é a leitura que a sessão fará ao começar;
 * um arquivo acima do teto não é guardado; com a sessão na fila ou em
 * execução, nada muda.
 */
@RunWith(AndroidJUnit4::class)
class AnexosScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private fun abrirNosAnexos(id: String, seletor: SeletorDeTeste) {
        regra.abrir(c, sessaoPedida = id, seletor = seletor)
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.ABRIR_ANEXOS).fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.ABRIR_ANEXOS).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(Marcas.MANIFESTO_RESULTADO).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun oManifestoValidoMostraAsContagensQueASessaoVaiLer() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        val seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray())))
        abrirNosAnexos(id, seletor)
        regra.onNodeWithTag(Marcas.MANIFESTO_RESULTADO).assertTextEquals("Nenhum manifesto de citações anexado.")

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Anexo adicionado.")
        regra.onNodeWithTag(Marcas.MANIFESTO_RESULTADO).assertTextEquals("Manifesto lido: 1 citação e 1 fonte.")
        val anexos = c.anexos.daSessao(id)
        assertEquals(listOf("citation-manifest.json"), anexos.map { it.nomeOriginal })
        // O seletor aceita JSON e, pela lista, qualquer arquivo: é a leitura que decide o que é manifesto.
        assertEquals(listOf("application/json", "*/*"), (seletor.pedidos.single() as Array<*>).toList())
    }

    @Test
    fun oManifestoInvalidoMostraOMotivoERemoverApagaALinhaEOArquivo() {
        val id = c.sessao(Estados.CANCELADA)
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", "{}".toByteArray()))))

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Anexo adicionado.")
        regra.onNodeWithTag(Marcas.MANIFESTO_RESULTADO).assertTextEquals(
            "Manifesto recusado: citation manifest attachment must use citation_manifest.v1. Com ele, a sessão pausaria " +
                "na auditoria final antes de qualquer chamada paga; remova ou troque o arquivo.",
        )
        val anexo = c.anexos.daSessao(id).single()
        assertTrue(File(anexo.caminho).exists())

        regra.onNodeWithTag(Marcas.removerAnexo(anexo.id)).performScrollTo().performClick()
        regra.esperarTexto("Anexo removido.")
        assertTrue(c.anexos.daSessao(id).isEmpty())
        assertFalse(File(anexo.caminho).exists())
        regra.onNodeWithTag(Marcas.MANIFESTO_RESULTADO).assertTextEquals("Nenhum manifesto de citações anexado.")
    }

    @Test
    fun umArquivoAcimaDoTetoNaoEGuardado() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        val grande = c.arquivo("citation-manifest.json", ByteArray(AnexosDaSessao.MAX_BYTES + 1) { ' '.code.toByte() })
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(grande)))

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto(AnexosDaSessao.MENSAGEM_ACIMA_DO_TETO)
        assertTrue(c.anexos.daSessao(id).isEmpty())
        grande.delete()
    }

    @Test
    fun comASessaoNaFilaOuEmExecucaoOsAnexosNaoMudam() {
        val id = c.sessao(Estados.RODANDO)
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.onNodeWithTag(Marcas.ANEXOS_EM_EXECUCAO).assertExists()
        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).assertIsNotEnabled()
        assertTrue(c.anexos.daSessao(id).isEmpty())
    }
}
