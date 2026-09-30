package dev.lcv.maestro.ui

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import android.os.CancellationSignal
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * O banco cheio sob demanda (decisão 25 do operador, 29/09/2026), pelo `openHelperFactory` do Room:
 * com [cheio], toda escrita nas tabelas do aplicativo falha como o SQLite falha sem espaço, com a mesma
 * exceção e a mesma mensagem, no ponto em que o framework a lançaria. As leituras e as tabelas internas
 * do Room (`room_*`) seguem normais. Um gatilho SQL não serve: `RAISE` é `SQLITE_CONSTRAINT`, não
 * `SQLITE_FULL`, e o limite de páginas do SQLite só falha quando a escrita precisa de página nova.
 */
class BancoCheio(private val base: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()) : SupportSQLiteOpenHelper.Factory {

    @Volatile var cheio: Boolean = false

    /** Com [cheio], só a escrita nesta tabela falha: o espaço acaba no meio de uma transação. */
    @Volatile var soNaTabela: String? = null

    /** Posto, a leitura desta tabela falha como o SQLite com erro de disco. */
    @Volatile var leituraQuebrada: String? = null

    /** Posta, a escrita na tabela [tabelaTravada] espera a trava abrir: uma ação lenta, no meio da transação. */
    @Volatile var trava: CountDownLatch? = null
    @Volatile var tabelaTravada: String? = null

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = Ajudante(base.create(configuration))

    /**
     * Posta, uma leitura desta tabela feita na mesma thread depois de uma escrita nela falha com erro de disco,
     * uma vez só: a releitura que confirma o que a ação acabou de gravar. Passam antes dela [releiturasAntes]
     * leituras, as que a própria gravação faz depois de escrever. As leituras das outras threads, como a
     * observação da tela, seguem normais.
     */
    @Volatile var releituraQuebrada: String? = null
    @Volatile var releiturasAntes: Int = 0
    private val lidasDepoisDaEscrita = ThreadLocal<Int?>()

    private fun conferir(sql: String) {
        if (!ESCRITA.containsMatchIn(sql) || sql.contains("room_")) return
        if (releituraQuebrada?.let { sql.contains(it) } == true && lidasDepoisDaEscrita.get() == null) lidasDepoisDaEscrita.set(0)
        tabelaTravada?.takeIf { sql.contains(it) }?.let { trava?.await(10, TimeUnit.SECONDS) }
        val naTabela = soNaTabela?.let { sql.contains(it) } ?: true
        if (cheio && naTabela) throw SQLiteFullException(MENSAGEM)
    }

    /**
     * Posta, a leitura da tabela [tabelaDaLeituraPresa] depois de [leiturasAntes] outras dela espera a trava, uma
     * vez só: uma releitura que já leu parte da tela e termina depois de outra.
     */
    @Volatile var leituraPresa: CountDownLatch? = null
    @Volatile var tabelaDaLeituraPresa: String? = null
    private val leiturasAntes = java.util.concurrent.atomic.AtomicInteger(0)

    fun prenderLeitura(tabela: String, depoisDe: Int, trava: CountDownLatch) {
        leiturasAntes.set(depoisDe)
        tabelaDaLeituraPresa = tabela
        leituraPresa = trava
    }

    private fun conferirLeitura(sql: String) {
        if (leituraQuebrada?.let { sql.contains(it) } == true) throw SQLiteDiskIOException(MENSAGEM_DE_DISCO)
        val lidas = lidasDepoisDaEscrita.get()
        if (lidas != null && releituraQuebrada?.let { sql.contains(it) } == true) {
            if (lidas < releiturasAntes) {
                lidasDepoisDaEscrita.set(lidas + 1)
            } else {
                lidasDepoisDaEscrita.set(null)
                releituraQuebrada = null
                throw SQLiteDiskIOException(MENSAGEM_DE_DISCO)
            }
        }
        if (tabelaDaLeituraPresa?.let { sql.contains(it) } == true && leituraPresa != null) {
            if (leiturasAntes.getAndDecrement() <= 0) {
                val trava = synchronized(this) { leituraPresa.also { leituraPresa = null } }
                trava?.await(10, TimeUnit.SECONDS)
            }
        }
    }

    private inner class Ajudante(private val base: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by base {
        override val writableDatabase: SupportSQLiteDatabase get() = Banco(base.writableDatabase)
        override val readableDatabase: SupportSQLiteDatabase get() = Banco(base.readableDatabase)
    }

    private inner class Banco(private val base: SupportSQLiteDatabase) : SupportSQLiteDatabase by base {
        override fun compileStatement(sql: String): SupportSQLiteStatement = Instrucao(base.compileStatement(sql), sql)

        override fun query(query: String): Cursor {
            conferirLeitura(query)
            return base.query(query)
        }

        override fun query(query: String, bindArgs: Array<out Any?>): Cursor {
            conferirLeitura(query)
            return base.query(query, bindArgs)
        }

        override fun query(query: SupportSQLiteQuery): Cursor {
            conferirLeitura(query.sql)
            return base.query(query)
        }

        override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor {
            conferirLeitura(query.sql)
            return base.query(query, cancellationSignal)
        }

        override fun execSQL(sql: String) {
            conferir(sql)
            base.execSQL(sql)
        }

        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            conferir(sql)
            base.execSQL(sql, bindArgs)
        }

        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
            conferir("INSERT INTO $table")
            return base.insert(table, conflictAlgorithm, values)
        }

        override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            conferir("UPDATE $table")
            return base.update(table, conflictAlgorithm, values, whereClause, whereArgs)
        }

        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            conferir("DELETE FROM $table")
            return base.delete(table, whereClause, whereArgs)
        }
    }

    private inner class Instrucao(private val base: SupportSQLiteStatement, private val sql: String) : SupportSQLiteStatement by base {
        override fun execute() {
            conferir(sql)
            base.execute()
        }

        override fun executeInsert(): Long {
            conferir(sql)
            return base.executeInsert()
        }

        override fun executeUpdateDelete(): Int {
            conferir(sql)
            return base.executeUpdateDelete()
        }
    }

    companion object {
        /** A mensagem do framework para `SQLITE_FULL`. */
        const val MENSAGEM: String = "database or disk is full (code 13 SQLITE_FULL)"

        /** A mensagem do framework para `SQLITE_IOERR`. */
        const val MENSAGEM_DE_DISCO: String = "disk I/O error (code 10 SQLITE_IOERR)"
        private val ESCRITA = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)
    }
}
