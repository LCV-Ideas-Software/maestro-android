package dev.lcv.maestro.sessao

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * As classes reais do SQLite que o classificador da decisão 25 reconhece: o banco cheio, o erro de disco e, desde a
 * extensão de 30/09/2026 (#80), o banco que não abre e o corrompido, também embrulhados pelo `get()` do WorkManager.
 */
@RunWith(AndroidJUnit4::class)
class ArmazenamentoNoAparelhoTest {

    @Test
    fun oBancoCheioODiscoOBancoQueNaoAbreEOCorrompidoSaoArmazenamento() {
        assertEquals("cheio", motivoDeArmazenamento(SQLiteFullException("cheio")))
        assertEquals("disco", motivoDeArmazenamento(SQLiteDiskIOException("disco")))
        assertEquals("não abre", motivoDeArmazenamento(SQLiteCantOpenDatabaseException("não abre")))
        assertEquals("corrompido", motivoDeArmazenamento(SQLiteDatabaseCorruptException("corrompido")))
        assertEquals("não abre", motivoDeArmazenamento(ExecutionException(SQLiteCantOpenDatabaseException("não abre"))))
        assertEquals("corrompido", motivoDeArmazenamento(ExecutionException(SQLiteDatabaseCorruptException("corrompido"))))
    }

    @Test
    fun aRestricaoDoSqliteNaoEArmazenamento() {
        val restricao = SQLiteConstraintException("restrição")
        assertSame(restricao, assertThrows(SQLiteConstraintException::class.java) { motivoDeArmazenamento(restricao) })
        val embrulhada = ExecutionException(SQLiteConstraintException("restrição"))
        assertSame(embrulhada, assertThrows(ExecutionException::class.java) { motivoDeArmazenamento(embrulhada) })
    }
}
