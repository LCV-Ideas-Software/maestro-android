package dev.lcv.maestro.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.ui.licencas.LicencasScreen
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * O que as licenças exigem que viaje com o binário (THIRDPARTY.md): o texto da
 * AGPL, o inventário, os avisos de terceiros no `NOTICE` e uma cópia do texto
 * da Apache-2.0 — nos assets do APK, pela tarefa do build, e na tela.
 */
@RunWith(AndroidJUnit4::class)
class LicencasScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private fun asset(nome: String): String = c.contexto.assets.open(nome).bufferedReader().use { it.readText() }

    @Test
    fun oApkLevaOsAvisosDeTerceirosEACopiaDaApache() {
        val aviso = asset("NOTICE")
        listOf(
            "Jackson is a high-performance, Free/Open Source JSON processing library.",
            "Copyright (c) 2024 Werner Randelshofer, Switzerland.",
            "Copyright 2018-2020 Raffaello Giulietti",
            "Copyright (c) 2015, Atlassian Pty Ltd",
            "Apache Commons IO",
            "Copyright (c) 2004-2023 QOS.ch",
            "Copyright 2008 Google Inc.  All rights reserved.",
            "https://publicsuffix.org/list/",
            "bd8cb85bd4bad964fe6918f79665bb40c3a8efef",
        ).forEach { assertTrue(it, it in aviso) }
        assertTrue("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION" in asset("Apache-2.0.txt"))
        assertTrue("GNU AFFERO GENERAL PUBLIC LICENSE" in asset("LICENSE"))
        assertTrue("# Third-party inventory" in asset("THIRDPARTY.md"))
    }

    @Test
    fun aTelaMostraAsQuatroSecoesEAsTabelasDoInventario() {
        regra.abrir(c)
        regra.onNodeWithTag(Marcas.IR_PARA_CONFIGURACOES).performClick()
        regra.onNodeWithTag(Marcas.ABRIR_LICENCAS).performScrollTo().performClick()
        regra.waitUntil(5_000) { regra.onAllNodesWithText("Componentes de terceiros").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithText("Aviso").assertExists()
        regra.onNodeWithText("Licença do aplicativo").assertExists()
        regra.onNodeWithText("Licença Apache, versão 2.0 (componentes de terceiros)").assertExists()
        // Uma linha de tabela do inventário vira um cartão com o nome em destaque.
        regra.onNodeWithText("androidx.navigation3:navigation3-runtime and -ui (-android)").assertExists()
    }

    // Decisão 25 do operador, estendida em 30/09/2026 (#80), com as Licenças por decisão expressa: o arquivo que não
    // se lê é o aviso com o motivo, o motivo no lugar dos textos, e a volta da tela lê de novo.

    @Test
    fun oArquivoQueNaoSeLeEAvisadoTemOMotivoNoLugarEVoltaNaVoltaDaTela() {
        val motivo = "asset ilegivel"
        val quebrado = AtomicBoolean(true)
        regra.setContent {
            MaestroTheme {
                val estado = remember { SnackbarHostState() }
                val escopo = rememberCoroutineScope()
                CompositionLocalProvider(LocalAvisos provides Avisos(estado, escopo)) {
                    Column {
                        SnackbarHost(estado)
                        LicencasScreen(ler = { contexto, nome ->
                            if (quebrado.get()) throw IOException(motivo)
                            contexto.assets.open(nome).bufferedReader().use { it.readText() }
                        })
                    }
                }
            }
        }
        regra.esperarTexto("Não foi possível ler os dados do aparelho. Motivo: $motivo")
        regra.waitUntil(5_000) {
            regra.onAllNodes(
                hasTestTag(Marcas.LEITURA_FALHOU) and
                    hasText("Não foi possível ler os dados desta tela; ela tenta de novo quando você voltar a ela. Motivo: $motivo"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        quebrado.set(false)
        // Sem a volta da tela, os arquivos não são relidos: nada de laço.
        Thread.sleep(1_000)
        regra.waitForIdle()
        regra.onNodeWithText("Componentes de terceiros").assertDoesNotExist()
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        regra.waitUntil(5_000) { regra.onAllNodesWithText("Componentes de terceiros").fetchSemanticsNodes().isNotEmpty() }
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
    }

    @Test
    fun aLeituraQueFalhaAntesDaPrimeiraRetomadaNaoERelidaPorElaQueEAAbertura() {
        val leituras = AtomicInteger(0)
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.setContent {
            MaestroTheme {
                val estado = remember { SnackbarHostState() }
                val escopo = rememberCoroutineScope()
                CompositionLocalProvider(LocalAvisos provides Avisos(estado, escopo)) {
                    LicencasScreen(ler = { _, _ ->
                        leituras.incrementAndGet()
                        throw IOException("asset ilegivel")
                    })
                }
            }
        }
        // Em STARTED, o teste do Compose não enxerga a tela: a prova é o contador de leituras do dublê.
        val prazo = System.currentTimeMillis() + 5_000
        while (leituras.get() == 0 && System.currentTimeMillis() < prazo) Thread.sleep(50)
        Thread.sleep(500)
        val naAbertura = leituras.get()
        assertEquals(1, naAbertura)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        Thread.sleep(1_000)
        regra.waitForIdle()
        assertEquals(naAbertura, leituras.get())
    }
}
