/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A leitura do documento escolhido no seletor (decisão 26 do operador, #82): o motivo de cada falha. */
@RunWith(AndroidJUnit4::class)
class DocumentosTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun oAcessoQueOProvedorNegaFalhaComOMotivo() {
        val falhou = Documentos.ler(contexto.contentResolver, DOCUMENTO_NEGADO, 1024) as Documentos.Leitura.Falhou
        assertTrue(falhou.motivo, falhou.motivo.startsWith(ACESSO_NEGADO))
    }

    @Test
    fun oArquivoQueNaoSeLeFalhaComOMotivo() {
        val ausente = Uri.fromFile(File(contexto.cacheDir, "nao-existe-${System.nanoTime()}/citation-manifest.json"))
        val falhou = Documentos.ler(contexto.contentResolver, ausente, 1024) as Documentos.Leitura.Falhou
        assertTrue(falhou.motivo, falhou.motivo.contains("ENOENT"))
    }

    /** O que não é falha de armazenamento nem acesso negado não vira motivo de documento ilegível: segue adiante. */
    @Test
    fun oErroQueNaoEDoDocumentoSegueAdiante() {
        val erro = IllegalStateException("defeito do provedor")
        val lancado = assertThrows(IllegalStateException::class.java) {
            Documentos.ler(resolvedorQueLanca(erro), DOCUMENTO_DO_PROVEDOR_QUE_LANCA, 1024)
        }
        assertSame(erro, lancado)
    }
}
