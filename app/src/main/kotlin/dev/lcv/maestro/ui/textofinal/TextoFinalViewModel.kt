/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.textofinal

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Correio
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.motivoDoDocumento
import dev.lcv.maestro.ui.LeiturasDaTela
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
        /** O motivo de a sessão nunca ter sido lida (decisão 25 estendida, #80). */
        val falhaDeLeitura: String? = null,
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

    /** As leituras da tela sob a decisão 25 estendida (#80): aviso com o motivo, a tela segue, a volta lê de novo. */
    private val leituras = LeiturasDaTela { eventos.send(it) }

    val estado: StateFlow<Estado> = combine(leituras.observar(d.sessoes.observar(id), null), exportando) { lida, emCurso ->
        val linha = lida.valor
        Estado(
            carregada = true,
            titulo = linha?.titulo.orEmpty(),
            textoFinal = linha?.let { liberado(it.status, it.textoFinal) },
            exportando = emCurso,
            falhaDeLeitura = lida.falha,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    /** A volta da tela ao primeiro plano: a leitura que falhou lê de novo (decisão 25 estendida, #80). */
    /** O id da sessão, para o assunto do relato de conteúdo ofensivo. */
    val idDaSessao: String get() = id

    /**
     * Reportar conteúdo ofensivo (política de Conteúdo Gerado por IA do Google Play, seção 9): abre o aplicativo
     * de e-mail do aparelho com a mensagem a contato@lcv.dev já preenchida; sem aplicativo de e-mail, o aviso diz
     * o motivo.
     */
    fun reportarConteudo(contexto: Context, assunto: String, corpo: String) {
        val falha = d.correio.compor(contexto, Correio.CONTATO, assunto, corpo)
        if (falha != null) eventos.trySend(Mensagem.DeRecurso(R.string.reportar_sem_correio, listOf(falha)))
    }

    fun recarregar() {
        leituras.voltou()
    }

    /** O nome sugerido ao seletor de documentos: o título da sessão, sem o que um nome de arquivo não aceita. */
    fun nomeDoArquivo(formato: Formato): String = "${nomeBase(estado.value.titulo)}.${formato.extensao}"

    /**
     * Grava [formato] no documento que o seletor devolveu, fora da linha
     * principal. [uri] nulo é o seletor cancelado; uma falha de escrita apaga
     * o documento que o seletor já tinha criado, para não sobrar arquivo
     * vazio ou pela metade. Recriada a Activity com o seletor aberto, o
     * resultado chega a um ViewModel que ainda não leu a sessão: a exportação
     * espera essa leitura, e sem texto liberado o documento também é apagado
     * (achado do Codex na #78). O aviso traz o motivo, e só diz que nada foi salvo se o documento foi
     * apagado (decisão 25 do operador). [aoFalhar] é o de [gravar].
     */
    fun exportar(formato: Formato, uri: Uri?, resolver: ContentResolver, aoFalhar: (ContentResolver, Uri) -> Boolean = ::apagar) {
        if (uri == null) {
            eventos.trySend(Mensagem.DeRecurso(R.string.exportacao_cancelada))
            return
        }
        exportando.value = true
        viewModelScope.launch {
            val lido = estado.first { it.carregada }
            val texto = lido.textoFinal
            val falha = withContext(Dispatchers.IO) {
                when {
                    // Sem a sessão lida, o motivo é o do armazenamento, e não "sem texto liberado" (decisão 25 estendida, #80).
                    lido.falhaDeLeitura != null -> FalhaDaExportacao(lido.falhaDeLeitura, aoFalhar(resolver, uri))
                    texto == null -> FalhaDaExportacao(MOTIVO_SEM_TEXTO, aoFalhar(resolver, uri))
                    else -> gravar(resolver, uri, formato.bytes(texto), aoFalhar)
                }
            }
            exportando.value = false
            eventos.send(
                when {
                    falha == null -> Mensagem.DeRecurso(formato.sucesso)
                    falha.apagou -> Mensagem.DeRecurso(R.string.exportacao_falhou, listOf(falha.motivo))
                    else -> Mensagem.DeRecurso(R.string.exportacao_falhou_sem_apagar, listOf(falha.motivo))
                },
            )
        }
    }

    /** A gravação que falhou: o [motivo] e se o documento que o seletor criou foi [apagou]. */
    data class FalhaDaExportacao(val motivo: String, val apagou: Boolean)

    companion object {
        const val MOTIVO_SEM_TEXTO: String = "a sessão não tem texto final liberado"

        /** Só texto liberado (seção 4.4): a sessão convergiu e a finalização gravou o texto final. */
        fun liberado(status: String, textoFinal: String?): String? =
            textoFinal?.takeIf { status == Estados.CONVERGIDA && it.isNotEmpty() }

        fun nomeBase(titulo: String): String =
            // `\p{Cc}` (categoria geral do Unicode): o regex do Android é o ICU, e a classe POSIX não é a mesma coisa lá.
            titulo.replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "-").trim().take(120).ifEmpty { "texto-final" }

        /**
         * O fluxo do `ContentResolver`, nunca um caminho de arquivo; `null` quando gravou. O arquivo que não grava e o
         * acesso que o provedor nega passam pelo classificador do documento (decisão 26 do operador, #82); o resto
         * segue adiante, sem apagar nada.
         * [aoFalhar] apaga o documento que o seletor criou e diz se apagou; só os testes o trocam, para ver que é chamado.
         */
        fun gravar(
            resolver: ContentResolver,
            uri: Uri,
            bytes: ByteArray,
            aoFalhar: (ContentResolver, Uri) -> Boolean = ::apagar,
        ): FalhaDaExportacao? = try {
            val saida = resolver.openOutputStream(uri, "w") ?: throw IOException("o provedor não abriu o documento")
            saida.use { it.write(bytes) }
            null
        } catch (erro: Exception) {
            FalhaDaExportacao(motivoDoDocumento(erro), aoFalhar(resolver, uri))
        }

        private fun apagar(resolver: ContentResolver, uri: Uri): Boolean = try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (erro: Exception) {
            // O documento pode não existir, ou o provedor não apagar: o aviso manda conferir o destino.
            false
        }
    }
}
