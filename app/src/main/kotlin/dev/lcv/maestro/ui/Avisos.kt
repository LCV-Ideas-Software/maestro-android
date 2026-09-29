/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * O texto de um aviso que o `ViewModel` emite sem ter `Context`: a mensagem
 * literal que o núcleo devolveu (as recusas do web, palavra por palavra) ou
 * um texto do `strings.xml`.
 */
sealed interface Mensagem {
    data class Literal(val texto: String) : Mensagem
    data class DeRecurso(@StringRes val id: Int, val argumentos: List<Any> = emptyList()) : Mensagem
    data class DePlural(@PluralsRes val id: Int, val quantidade: Int) : Mensagem

    fun em(recursos: Resources): String = when (this) {
        is Literal -> texto
        is DeRecurso -> recursos.getString(id, *argumentos.toTypedArray())
        is DePlural -> recursos.getQuantityString(id, quantidade, quantidade)
    }
}

/**
 * A notificação do web (`showNotification`) como `Snackbar`, num estado só
 * do aplicativo: um aviso dado por uma tela continua visível depois que a
 * navegação a troca por outra ("Sessão Maestro AI iniciada." aparece já na
 * tela da sessão).
 */
class Avisos(private val estado: SnackbarHostState, private val escopo: CoroutineScope) {
    fun mostrar(texto: String) {
        escopo.launch { estado.showSnackbar(texto, withDismissAction = true) }
    }
}

val LocalAvisos = staticCompositionLocalOf<Avisos> { error("nenhum Avisos fornecido") }
