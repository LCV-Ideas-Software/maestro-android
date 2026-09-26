package dev.lcv.maestro.sessao

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import java.io.File

/**
 * O banco do aplicativo: o `ensureSchema` do web (`sessions.ts:451-669`) como
 * entidades do Room, mais as tabelas que o desktop guarda em arquivos (links,
 * evidências, anexos) e a de execuções do serviço em primeiro plano. O
 * arquivo é `maestro.db` na pasta de bancos do aplicativo, que o `:app` exclui
 * do backup e da transferência entre aparelhos (`dataExtractionRules`,
 * especificação, seção 4.2); os testes o abrem num arquivo temporário.
 */
@Database(
    entities = [
        SessaoEntidade::class,
        ArtefatoEntidade::class,
        ConfiguracoesEntidade::class,
        LinhaDeLinkEntidade::class,
        EventoDeLinkEntidade::class,
        EvidenciaEntidade::class,
        AnexoEntidade::class,
        ExecucaoEntidade::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Conversores::class)
public abstract class BancoDaSessao : RoomDatabase() {
    public abstract fun sessoes(): SessaoDao
    public abstract fun artefatos(): ArtefatoDao
    public abstract fun configuracoes(): ConfiguracoesDao
    public abstract fun links(): LinkDao
    public abstract fun evidencias(): EvidenciaDao
    public abstract fun anexos(): AnexoDao
    public abstract fun execucoes(): ExecucaoDao

    public companion object {
        public const val NOME_DO_ARQUIVO: String = "maestro.db"

        /** O banco do aplicativo, ou, em teste, o de [arquivo]. */
        public fun abrir(contexto: Context, arquivo: File = contexto.getDatabasePath(NOME_DO_ARQUIVO)): BancoDaSessao =
            Room.databaseBuilder(contexto, BancoDaSessao::class.java, arquivo.absolutePath).build()
    }
}
