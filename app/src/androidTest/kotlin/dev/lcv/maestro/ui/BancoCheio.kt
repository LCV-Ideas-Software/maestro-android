package dev.lcv.maestro.ui

import android.content.ContentValues
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * O banco cheio sob demanda (decisão 25 do operador, 29/09/2026), pelo `openHelperFactory` do Room:
 * com [cheio], toda escrita nas tabelas do aplicativo falha como o SQLite falha sem espaço, com a mesma
 * exceção e a mesma mensagem, no ponto em que o framework a lançaria. As leituras e as tabelas internas
 * do Room (`room_*`) seguem normais. Um gatilho SQL não serve: `RAISE` é `SQLITE_CONSTRAINT`, não
 * `SQLITE_FULL`, e o limite de páginas do SQLite só falha quando a escrita precisa de página nova.
 */
class BancoCheio(private val base: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()) : SupportSQLiteOpenHelper.Factory {

    @Volatile var cheio: Boolean = false

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = Ajudante(base.create(configuration))

    private fun conferir(sql: String) {
        if (cheio && ESCRITA.containsMatchIn(sql) && !sql.contains("room_")) throw SQLiteFullException(MENSAGEM)
    }

    private inner class Ajudante(private val base: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper by base {
        override val writableDatabase: SupportSQLiteDatabase get() = Banco(base.writableDatabase)
        override val readableDatabase: SupportSQLiteDatabase get() = Banco(base.readableDatabase)
    }

    private inner class Banco(private val base: SupportSQLiteDatabase) : SupportSQLiteDatabase by base {
        override fun compileStatement(sql: String): SupportSQLiteStatement = Instrucao(base.compileStatement(sql), sql)

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
        private val ESCRITA = Regex("^\\s*(INSERT|UPDATE|DELETE|REPLACE)\\b", RegexOption.IGNORE_CASE)
    }
}
