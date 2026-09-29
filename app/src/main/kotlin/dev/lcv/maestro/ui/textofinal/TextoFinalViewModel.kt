/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.textofinal

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.ui.Mensagem
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O texto final de uma sessão (especificação, seção 4.4): só o texto
 * liberado, isto é, gravado pela auditoria fresca da finalização numa sessão
 * `converged`. Qualquer outro estado não tem o que exibir nem exportar.
 */
class TextoFinalViewModel(private val d: Dependencias, private val id: String) : ViewModel() {

    data class Estado(
        val carregada: Boolean = false,
        val titulo: String = "",
        /** O Markdown liberado, ou `null` quando a sessão não tem texto liberado. */
        val textoFinal: String? = null,
        val exportando: Boolean = false,
    )

    /** Os dois exportáveis que saem do próprio texto; o PDF é do `WebView`, na tela. */
    enum class Formato(@StringRes val sucesso: Int, val extensao: String) {
        /** O artefato, tal qual: os bytes gravados, em UTF-8. */
        MARKDOWN(R.string.exportado_markdown, "md"),

        /** O `TextContentRenderer` da commonmark-java. */
        TEXTO(R.string.exportado_txt, "txt"),
        ;

        fun bytes(markdown: String): ByteArray = when (this) {
            MARKDOWN -> markdown.toByteArray(Charsets.UTF_8)
            TEXTO -> RenderizadorDoTextoFinal.texto(markdown).toByteArray(Charsets.UTF_8)
        }
    }

    private val exportando = MutableStateFlow(false)
    private val eventos = Channel<Mensagem>(Channel.BUFFERED)
    val avisos: Flow<Mensagem> = eventos.receiveAsFlow()

    val estado: StateFlow<Estado> = combine(d.sessoes.observar(id), exportando) { linha, emCurso ->
        Estado(carregada = true, titulo = linha?.titulo.orEmpty(), textoFinal = linha?.let { liberado(it.status, it.textoFinal) }, exportando = emCurso)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    /** O nome sugerido ao seletor de documentos: o título da sessão, sem o que um nome de arquivo não aceita. */
    fun nomeDoArquivo(formato: Formato): String = "${nomeBase(estado.value.titulo)}.${formato.extensao}"

    /**
     * Grava [formato] no documento que o seletor devolveu, fora da linha
     * principal. [uri] nulo é o seletor cancelado; uma falha de escrita apaga
     * o documento que o seletor já tinha criado, para não sobrar arquivo
     * vazio ou pela metade. Recriada a Activity com o seletor aberto, o
     * resultado chega a um ViewModel que ainda não leu a sessão: a exportação
     * espera essa leitura, e sem texto liberado o documento também é apagado
     * (achado do Codex na #78). [aoFalhar] é o de [gravar].
     */
    fun exportar(formato: Formato, uri: Uri?, resolver: ContentResolver, aoFalhar: (ContentResolver, Uri) -> Unit = ::apagar) {
        if (uri == null) {
            eventos.trySend(Mensagem.DeRecurso(R.string.exportacao_cancelada))
            return
        }
        exportando.value = true
        viewModelScope.launch {
            val texto = estado.first { it.carregada }.textoFinal
            val gravou = withContext(Dispatchers.IO) {
                if (texto == null) {
                    aoFalhar(resolver, uri)
                    false
                } else {
                    gravar(resolver, uri, formato.bytes(texto), aoFalhar)
                }
            }
            exportando.value = false
            eventos.send(Mensagem.DeRecurso(if (gravou) formato.sucesso else R.string.exportacao_falhou))
        }
    }

    companion object {
        /** Só texto liberado (seção 4.4): a sessão convergiu e a finalização gravou o texto final. */
        fun liberado(status: String, textoFinal: String?): String? =
            textoFinal?.takeIf { status == Estados.CONVERGIDA && it.isNotEmpty() }

        fun nomeBase(titulo: String): String =
            // `\p{Cc}` (categoria geral do Unicode): o regex do Android é o ICU, e a classe POSIX não é a mesma coisa lá.
            titulo.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "-").trim().take(120).ifEmpty { "texto-final" }

        /**
         * O fluxo do `ContentResolver`, nunca um caminho de arquivo; `false` quando não gravou.
         * [aoFalhar] apaga o documento que o seletor criou; só os testes o trocam, para ver que é chamado.
         */
        fun gravar(
            resolver: ContentResolver,
            uri: Uri,
            bytes: ByteArray,
            aoFalhar: (ContentResolver, Uri) -> Unit = ::apagar,
        ): Boolean = try {
            val saida = resolver.openOutputStream(uri, "w") ?: throw IOException("o provedor não abriu o documento")
            saida.use { it.write(bytes) }
            true
        } catch (erro: IOException) {
            aoFalhar(resolver, uri)
            false
        } catch (erro: SecurityException) {
            aoFalhar(resolver, uri)
            false
        }

        private fun apagar(resolver: ContentResolver, uri: Uri) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (erro: Exception) {
                // O documento pode não existir, ou o provedor não apagar: não há mais o que fazer.
            }
        }
    }
}
