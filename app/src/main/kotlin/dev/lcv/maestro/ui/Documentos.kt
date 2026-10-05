/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.lcv.maestro.sessao.motivoDoDocumento
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Um documento que a pessoa escolheu no seletor do sistema (plano do `:app`,
 * emenda A7): só pelo `ContentResolver`, nunca por um caminho de arquivo, sem
 * permissão de armazenamento, e com o teto aplicado **durante** a leitura —
 * um arquivo acima dele não chega a ser alocado inteiro.
 */
object Documentos {

    sealed interface Leitura {
        data class Lido(val nome: String, val tipo: String?, val bytes: ByteArray) : Leitura

        /** Maior que o teto: nada foi guardado. */
        data object AcimaDoTeto : Leitura

        /** O provedor não abriu, não entregou ou negou o documento: [motivo] é o do classificador (decisão 26). */
        data class Falhou(val motivo: String) : Leitura
    }

    /**
     * Lê [uri] até [teto] bytes. Bloqueante: chamar fora da linha principal. O arquivo que não se lê e o acesso
     * que o provedor nega passam pelo classificador do documento (decisão 26 do operador, #82); o resto segue adiante.
     */
    fun ler(resolver: ContentResolver, uri: Uri, teto: Int): Leitura = try {
        val entrada = resolver.openInputStream(uri) ?: throw IOException("o provedor não abriu o documento")
        val bytes = entrada.use { fluxo ->
            val saida = ByteArrayOutputStream()
            val bloco = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val lidos = fluxo.read(bloco)
                if (lidos < 0) break
                total += lidos
                if (total > teto) return Leitura.AcimaDoTeto
                saida.write(bloco, 0, lidos)
            }
            saida.toByteArray()
        }
        Leitura.Lido(nome(resolver, uri), resolver.getType(uri), bytes)
    } catch (erro: Exception) {
        Leitura.Falhou(motivoDoDocumento(erro))
    }

    /** O nome que o provedor mostra (`DISPLAY_NAME`), ou o último segmento do endereço. */
    private fun nome(resolver: ContentResolver, uri: Uri): String {
        val exibido = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (erro: RuntimeException) {
            // Um endereço `file:` ou um provedor sem a coluna: fica o segmento do endereço.
            null
        }
        return exibido?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().ifEmpty { "documento" }
    }
}
