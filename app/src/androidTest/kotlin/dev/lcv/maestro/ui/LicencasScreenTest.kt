package dev.lcv.maestro.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O que as licenças exigem que viaje com o binário (THIRDPARTY.md): o texto da
 * AGPL, o inventário, os avisos de terceiros no `NOTICE` e uma cópia do texto
 * da Apache-2.0 — nos assets do APK, pela tarefa do build, e na tela.
 */
@RunWith(AndroidJUnit4::class)
class LicencasScreenTest {

    val regra = createComposeRule()

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
}
