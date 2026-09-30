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
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.Resultado
import dev.lcv.maestro.ui.anexos.AnexosViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun umManifestoDeOutroProtocoloERecusadoComOHashAtivo() {
        // Achado do Codex na #78: a sessão recusaria esse manifesto na primeira revisão, depois de pagar o rascunho.
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        val ativo = FormatoDoRegistro.sha256(RepositorioDeConfiguracoes.PROTOCOLO_PADRAO)
        val deOutro = MANIFESTO_DE_EXEMPLO.replace(ativo, FormatoDoRegistro.sha256("outro protocolo"))
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", deOutro.toByteArray()))))

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Anexo adicionado.")
        regra.onNodeWithTag(Marcas.MANIFESTO_RESULTADO).assertTextEquals(
            "Manifesto recusado: O manifesto nao esta vinculado ao hash do protocolo ativo. Hash do protocolo ativo: $ativo. " +
                "Com ele, a sessão pausaria na auditoria final antes de qualquer chamada paga; remova ou troque o arquivo.",
        )
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
    fun oDiscoQueFalhaEAvisadoSemDerrubarOAplicativo() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        // Um arquivo onde a pasta dos anexos deveria estar: o anexo não pode ser gravado.
        c.pastaDosAnexos.writeBytes(byteArrayOf(0))

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("failed to write attachment: cannot create directory ${c.pastaDosAnexos.absolutePath}")
        assertTrue(c.anexos.daSessao(id).isEmpty())
    }

    @Test
    fun comASessaoNaFilaOuEmExecucaoOsAnexosNaoMudam() {
        val id = c.sessao(Estados.RODANDO)
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        regra.onNodeWithTag(Marcas.ANEXOS_EM_EXECUCAO).assertExists()
        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).assertIsNotEnabled()
        assertTrue(c.anexos.daSessao(id).isEmpty())
    }

    // Decisão 25 do operador (29/09/2026): o banco cheio numa ação da tela é a falha da ação, sem derrubar o aplicativo.

    @Test
    fun oBancoCheioAoAnexarEAvisadoENaoDeixaArquivo() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        assertTrue(c.anexos.daSessao(id).isEmpty())
        // O arquivo já publicado sai com a transação que falhou.
        assertEquals(0, c.pastaDosAnexos.listFiles()?.size ?: 0)
    }

    @Test
    fun aReleituraQueFalhaDepoisDeAnexarNaoDizQueAGravacaoFalhou() {
        // Achado do Codex na #78: o anexo gravou, e só a releitura falhou; a tela não pode dizer o contrário.
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        c.bancoCheio.leituraQuebrada = "anexos"

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Não foi possível reler os anexos; a lista ficou como estava. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        regra.esperarTexto("Anexo adicionado.")
        c.bancoCheio.leituraQuebrada = null
        assertEquals(1, c.anexos.daSessao(id).size)
    }

    @Test
    fun umaReleituraAntigaQueTerminaDepoisNaoRepoeAListaVelha() {
        // Achado do Codex na #78: a releitura da volta à tela corria junto com a de um anexo novo.
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        abrirNosAnexos(id, SeletorDeTeste(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray()))))
        // A releitura da volta à tela lê a lista de anexos e fica presa antes da leitura do manifesto.
        val trava = CountDownLatch(1)
        c.bancoCheio.prenderLeitura("anexos", depoisDe = 1, trava)
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        regra.waitUntil(5_000) { c.bancoCheio.leituraPresa == null }

        regra.onNodeWithTag(Marcas.ANEXAR_MANIFESTO).performClick()
        regra.esperarTexto("Anexo adicionado.")
        val anexo = c.anexos.daSessao(id).single()
        trava.countDown()
        Thread.sleep(1_000)
        regra.waitForIdle()
        regra.onNodeWithTag(Marcas.removerAnexo(anexo.id)).assertExists()
    }

    @Test
    fun oBancoCheioAoRemoverEAvisadoEOAnexoFica() {
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        val anexo = (c.anexos.adicionar(id, "citation-manifest.json", "application/json", MANIFESTO_DE_EXEMPLO.toByteArray()) as Resultado.Ok).valor
        abrirNosAnexos(id, SeletorDeTeste())
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.removerAnexo(anexo.id)).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível gravar no aparelho. Motivo: ${BancoCheio.MENSAGEM}")
        assertEquals(listOf(anexo.id), c.anexos.daSessao(id).map { it.id })
        assertTrue(File(anexo.caminho).exists())
    }

    /**
     * Achado do Codex na #78: a leitura do provedor de documentos demora e a sessão é retomada nesse
     * meio-tempo. Um ViewModel recém-criado ainda não leu o status, como o que recebe o arquivo depois
     * de o processo morrer: a tela deixa passar, e só a trava do núcleo recusa.
     */
    @Test
    fun oArquivoQueChegaComASessaoJaEmExecucaoERecusadoNaGravacao() {
        val id = c.sessao(Estados.RODANDO)
        val vm = AnexosViewModel(c.dependencias, id)
        vm.anexar(Uri.fromFile(c.arquivo("citation-manifest.json", MANIFESTO_DE_EXEMPLO.toByteArray())), c.contexto.contentResolver)
        assertEquals(Mensagem.Literal(AnexosDaSessao.MENSAGEM_EM_EXECUCAO), runBlocking { withTimeout(10_000) { vm.avisos.first() } })
        assertTrue(c.anexos.daSessao(id).isEmpty())
        assertEquals(0, c.pastaDosAnexos.listFiles()?.size ?: 0)
    }
}
