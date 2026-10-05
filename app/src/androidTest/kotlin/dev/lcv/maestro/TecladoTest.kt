/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.app.KeyguardManager
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.ui.Marcas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O teclado na tela (MAEANDR-31), com a Activity de verdade: o `adjustResize` do manifesto vale só nela, e não na
 * Activity de teste em que as outras telas rodam. Com o teclado aberto num campo baixo de Configurações:
 * - a barra superior fica onde estava, inteira abaixo da barra de status;
 * - o campo em foco fica inteiro à vista, acima do teclado;
 * - o conteúdo termina no topo do teclado, sem faixa vazia entre os dois;
 * - os avisos aparecem acima do teclado.
 *
 * As posições são as da janela como ela aparece. Quando o sistema empurra a janela inteira para cima, o deslocamento
 * entra na posição da vista na janela, e não nas coordenadas do Compose, que são relativas à raiz.
 *
 * O foco no campo e o clique em salvar vão pela semântica, e não por toque na tela: um aviso na base da tela fica por
 * cima do campo e do botão e receberia o toque. É o que acontece na suíte: o `AplicacaoTest` deixa no processo a falha
 * do WorkManager, e a tela inicial a avisa.
 */
@RunWith(AndroidJUnit4::class)
class TecladoTest {

    @get:Rule
    val regra = createEmptyComposeRule()

    /** Em pixels da janela; as alturas do campo dizem se ele está inteiro à vista (a visível é a recortada). */
    private data class Medida(
        val baseDaBarraDeStatus: Int,
        val topoDoTeclado: Int,
        val topoDaBarra: Float,
        val baseDoCampo: Float,
        val alturaVisivelDoCampo: Float,
        val alturaDoCampo: Int,
        val baseDoConteudo: Float,
    )

    @Test
    fun oTecladoAbertoNaoMoveABarraNemCobreOCampoNemOAviso() {
        ActivityScenario.launch(MainActivity::class.java).use { cenario ->
            esperarTag(Marcas.IR_PARA_CONFIGURACOES)
            regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
            esperarAsLeiturasDaTela(cenario)
            // O campo vai para a borda de baixo da tela, e o teclado abre por cima dele.
            regra.onNodeWithTag(Marcas.CAMPO_EMAIL).performScrollTo()
            regra.waitForIdle()
            val topoDaBarraSemTeclado = deslocamento(cenario) + topo(Marcas.VOLTAR)
            regra.onNodeWithTag(Marcas.CAMPO_EMAIL).requestFocus()

            val medida = medirComOTecladoParado(cenario)

            assertTrue(
                "A barra superior foi para baixo da barra de status: o topo dela está em ${medida.topoDaBarra} px, " +
                    "e a barra de status vai até ${medida.baseDaBarraDeStatus} px ($medida).",
                medida.topoDaBarra >= medida.baseDaBarraDeStatus,
            )
            assertEquals(
                "A barra superior saiu do lugar com o teclado aberto: a janela inteira subiu ($medida).",
                topoDaBarraSemTeclado,
                medida.topoDaBarra,
                0.5f,
            )
            assertTrue(
                "O campo em foco não está inteiro à vista acima do teclado: a base dele está em ${medida.baseDoCampo} px, " +
                    "o teclado começa em ${medida.topoDoTeclado} px, e se veem ${medida.alturaVisivelDoCampo} " +
                    "dos ${medida.alturaDoCampo} px dele ($medida).",
                medida.alturaVisivelDoCampo >= medida.alturaDoCampo - 0.5f && medida.baseDoCampo <= medida.topoDoTeclado,
            )
            assertEquals(
                "O conteúdo não termina no topo do teclado: sobra ou falta uma faixa entre os dois ($medida).",
                medida.topoDoTeclado.toFloat(),
                medida.baseDoConteudo,
                1f,
            )

            // Uma ação com o teclado aberto: o aviso na tela tem de aparecer acima do teclado, seja o que ela dá (o de
            // gravado, o de recusa ou o de falha), seja um que já estava lá e espera a vez dele.
            regra.onNodeWithTag(Marcas.SALVAR_CONFIGURACOES).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            // A base sai da mesma leitura que acha o aviso na tela: lida depois, seria a do lugar vazio se ele saísse.
            var baseDoAvisoNaRaiz = 0f
            regra.waitUntil(15_000) {
                val aviso = regra.onNodeWithTag(Marcas.AVISOS).fetchSemanticsNode()
                baseDoAvisoNaRaiz = aviso.positionInRoot.y + aviso.size.height
                aviso.size.height > 0
            }
            val deslocamentoNoAviso = deslocamento(cenario)
            val baseDoAviso = deslocamentoNoAviso + baseDoAvisoNaRaiz
            val topoDoTeclado = checkNotNull(medir(cenario)) { "O teclado fechou com o clique no botão." }.topoDoTeclado
            assertTrue(
                "O aviso aparece atrás do teclado: a base dele está em $baseDoAviso px, com a janela deslocada em " +
                    "$deslocamentoNoAviso px, e o teclado começa em $topoDoTeclado px.",
                baseDoAviso <= topoDoTeclado,
            )
        }
    }

    private fun esperarTag(marca: String) {
        regra.waitUntil(10_000) { regra.onAllNodesWithTag(marca).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * As leituras das Configurações correm em `Dispatchers.IO`, que o `waitForIdle` não espera. A das configurações
     * habilita o botão de salvar. A do cofre, num aparelho sem trava de tela, acrescenta um aviso acima do campo, e o
     * aviso que chegasse depois da rolagem empurraria o campo para baixo dela.
     */
    private fun esperarAsLeiturasDaTela(cenario: ActivityScenario<MainActivity>) {
        regra.waitUntil(10_000) {
            regra.onAllNodes(hasTestTag(Marcas.SALVAR_CONFIGURACOES) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        var semTrava = false
        cenario.onActivity { atividade -> semTrava = !atividade.getSystemService(KeyguardManager::class.java).isDeviceSecure }
        if (semTrava) esperarTag(Marcas.SEM_TRAVA_AVISO)
    }

    private fun topo(marca: String): Float = regra.onNodeWithTag(marca).fetchSemanticsNode().boundsInRoot.top

    /** A posição da vista do Compose na janela: negativa quando o sistema empurra a janela para cima. */
    private fun deslocamento(cenario: ActivityScenario<MainActivity>): Int {
        val posicao = IntArray(2)
        cenario.onActivity { atividade ->
            atividade.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).getLocationInWindow(posicao)
        }
        return posicao[1]
    }

    /**
     * Espera o teclado abrir e a tela parar, porque o teclado e o deslocamento da janela são animados, e devolve a
     * medida. Sem teclado na tela não há o que provar: o teste falha dizendo isso, em vez de passar sem o teclado.
     */
    private fun medirComOTecladoParado(cenario: ActivityScenario<MainActivity>): Medida {
        var anterior: Medida? = null
        var iguais = 0
        val prazo = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < prazo) {
            regra.waitForIdle()
            val atual = medir(cenario)
            iguais = if (atual != null && atual == anterior) iguais + 1 else 0
            if (iguais >= 5) return atual!!
            anterior = atual
            Thread.sleep(100)
        }
        throw AssertionError("O teclado na tela não abriu, ou a tela não parou, em 15 s. Última medida: $anterior. ${estado(cenario)}")
    }

    /** O que diz por que o teclado não abriu: o foco do campo, o foco da janela e a tela de bloqueio. */
    private fun estado(cenario: ActivityScenario<MainActivity>): String {
        val campoComFoco = regra.onNodeWithTag(Marcas.CAMPO_EMAIL).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused)
        var janela = ""
        cenario.onActivity { atividade ->
            val bloqueio = atividade.getSystemService(KeyguardManager::class.java)
            janela = "janela com foco: ${atividade.hasWindowFocus()}; tela bloqueada: ${bloqueio.isKeyguardLocked}"
        }
        return "Campo com foco: $campoComFoco; $janela."
    }

    /** A medida de agora, ou nulo enquanto o teclado não está na tela. */
    private fun medir(cenario: ActivityScenario<MainActivity>): Medida? {
        var janela: IntArray? = null
        cenario.onActivity { atividade ->
            val decoracao = atividade.window.decorView
            val recuos = ViewCompat.getRootWindowInsets(decoracao) ?: return@onActivity
            val teclado = recuos.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (!recuos.isVisible(WindowInsetsCompat.Type.ime()) || teclado <= 0) return@onActivity
            val posicao = IntArray(2)
            atividade.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).getLocationInWindow(posicao)
            janela = intArrayOf(recuos.getInsets(WindowInsetsCompat.Type.statusBars()).top, decoracao.height - teclado, posicao[1])
        }
        val (barraDeStatus, topoDoTeclado, deslocamento) = janela ?: return null
        val campo = regra.onNodeWithTag(Marcas.CAMPO_EMAIL).fetchSemanticsNode()
        return Medida(
            baseDaBarraDeStatus = barraDeStatus,
            topoDoTeclado = topoDoTeclado,
            topoDaBarra = deslocamento + topo(Marcas.VOLTAR),
            baseDoCampo = deslocamento + campo.boundsInRoot.bottom,
            alturaVisivelDoCampo = campo.boundsInRoot.height,
            alturaDoCampo = campo.size.height,
            baseDoConteudo = deslocamento + regra.onNodeWithTag(Marcas.CONTEUDO).fetchSemanticsNode().boundsInRoot.bottom,
        )
    }
}
