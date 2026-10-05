/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.sessao

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * Decisão 25 do operador (29/09/2026), estendida em 30/09/2026 (#80): o banco cheio, o erro de disco do SQLite, o
 * banco que não abre ou está corrompido e o arquivo que não grava nem lê não derrubam o aplicativo. Numa ação de tela,
 * numa leitura ao abrir ou voltar à tela, numa observação, na reconciliação da abertura e no cancelamento pela
 * notificação, viram aviso com o motivo. Devolve esse motivo, também quando ele chega embrulhado pelo `get()` do
 * futuro do WorkManager; qualquer outra exceção, inclusive o cancelamento da corrotina, segue adiante. É o classificador
 * das telas e do núcleo da sessão: o `:app` e este módulo o usam.
 */
public fun motivoDeArmazenamento(erro: Exception): String = when {
    eDeArmazenamento(erro) -> erro.message.orEmpty()
    erro is ExecutionException && erro.cause.let { it != null && eDeArmazenamento(it) } -> erro.cause?.message.orEmpty()
    else -> throw erro
}

/**
 * A entrada e saída do documento que a pessoa escolheu no seletor do sistema (decisão 26 do operador, 01/10/2026,
 * #82): o provedor de documentos que nega acesso (`SecurityException`) também é falha de armazenamento, com o motivo
 * dela; o resto é o de [motivoDeArmazenamento]. Só essa fronteira usa esta entrada: fora dela, um `SecurityException`
 * (uma permissão do sistema que falta) é defeito de código e segue adiante.
 */
public fun motivoDoDocumento(erro: Exception): String =
    if (erro is SecurityException) erro.message.orEmpty() else motivoDeArmazenamento(erro)

private fun eDeArmazenamento(erro: Throwable): Boolean =
    erro is SQLiteFullException || erro is SQLiteDiskIOException || erro is SQLiteCantOpenDatabaseException ||
        erro is SQLiteDatabaseCorruptException || erro is IOException
