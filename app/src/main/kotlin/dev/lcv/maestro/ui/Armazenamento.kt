/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import java.io.IOException

/**
 * Decisão 25 do operador (29/09/2026): numa ação de tela, o banco cheio, o erro de disco do SQLite e o
 * arquivo que não grava são a falha da ação, com o motivo no aviso, e não derrubam o aplicativo. Devolve
 * esse motivo; qualquer outra exceção, inclusive o cancelamento da corrotina, segue adiante.
 */
internal fun motivoDeArmazenamento(erro: Exception): String = when (erro) {
    is SQLiteFullException, is SQLiteDiskIOException, is IOException -> erro.message.orEmpty()
    else -> throw erro
}
