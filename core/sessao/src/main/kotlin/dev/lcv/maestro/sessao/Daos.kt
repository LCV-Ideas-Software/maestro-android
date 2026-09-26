package dev.lcv.maestro.sessao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Os DAOs são bloqueantes de propósito: quem os chama é o repositório, dentro
 * de `runInTransaction`, a partir de `Dispatchers.IO` (a coleta e a auditoria
 * do `:core:protocolo` são bloqueantes). Só [SessaoDao.observar] é `Flow`: é o
 * que substitui o *polling* do web (especificação, seção 4.2).
 */
@Dao
public interface SessaoDao {
    @Query("SELECT * FROM sessoes WHERE id = :id LIMIT 1")
    public fun carregar(id: String): SessaoEntidade?

    @Query("SELECT * FROM sessoes WHERE id = :id LIMIT 1")
    public fun observar(id: String): Flow<SessaoEntidade?>

    @Query("SELECT * FROM sessoes ORDER BY criadaEm DESC")
    public fun listar(): List<SessaoEntidade>

    /** `runnerStopRequested` visto do outro lado: o que ainda está na fila ou rodando. */
    @Query("SELECT * FROM sessoes WHERE status IN ('queued', 'running')")
    public fun emExecucao(): List<SessaoEntidade>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun inserir(sessao: SessaoEntidade)

    @Update
    public fun atualizar(sessao: SessaoEntidade): Int
}

@Dao
public interface ArtefatoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun inserir(artefato: ArtefatoEntidade)

    /** `loadSessionArtifacts` (`sessions.ts:3079-3087`): por turno e, no empate, por criação. */
    @Query("SELECT * FROM artefatos WHERE sessaoId = :sessaoId ORDER BY turno ASC, criadoEm ASC")
    public fun daSessao(sessaoId: String): List<ArtefatoEntidade>

    /** `loadSessionArtifact` (`sessions.ts:3089-3098`). */
    @Query("SELECT * FROM artefatos WHERE sessaoId = :sessaoId AND id = :id LIMIT 1")
    public fun um(sessaoId: String, id: String): ArtefatoEntidade?
}

@Dao
public interface ConfiguracoesDao {
    @Query("SELECT * FROM configuracoes WHERE id = :id LIMIT 1")
    public fun carregar(id: String = CONFIGURACOES_ID): ConfiguracoesEntidade?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public fun gravar(configuracoes: ConfiguracoesEntidade)
}

@Dao
public interface LinkDao {
    @Query("SELECT * FROM links WHERE linkId = :linkId LIMIT 1")
    public fun carregar(linkId: String): LinhaDeLinkEntidade?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public fun gravar(linha: LinhaDeLinkEntidade)

    @Query("SELECT * FROM links ORDER BY linkId ASC")
    public fun todos(): List<LinhaDeLinkEntidade>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun anotar(evento: EventoDeLinkEntidade)

    @Query("SELECT * FROM eventos_de_links WHERE linkId = :linkId ORDER BY seq ASC")
    public fun eventosDe(linkId: String): List<EventoDeLinkEntidade>
}

@Dao
public interface EvidenciaDao {
    @Query("SELECT * FROM evidencias WHERE id = :id LIMIT 1")
    public fun carregar(id: String): EvidenciaEntidade?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public fun gravar(evidencia: EvidenciaEntidade)

    @Query("SELECT caminhoDoCorpo FROM evidencias WHERE caminhoDoCorpo IS NOT NULL")
    public fun caminhosDosCorpos(): List<String>
}

@Dao
public interface AnexoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun inserir(anexo: AnexoEntidade)

    @Query("SELECT * FROM anexos WHERE sessaoId = :sessaoId ORDER BY criadoEm ASC, id ASC")
    public fun daSessao(sessaoId: String): List<AnexoEntidade>

    @Query("SELECT * FROM anexos WHERE id = :id LIMIT 1")
    public fun um(id: String): AnexoEntidade?

    @Query("DELETE FROM anexos WHERE id = :id")
    public fun remover(id: String): Int

    @Query("SELECT caminho FROM anexos")
    public fun caminhos(): List<String>
}

@Dao
public interface ExecucaoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun inserir(execucao: ExecucaoEntidade): Long

    @Update
    public fun atualizar(execucao: ExecucaoEntidade): Int

    /** As execuções que começaram depois de [desde], para a soma do orçamento de 24 horas. */
    @Query("SELECT * FROM execucoes WHERE inicio >= :desde ORDER BY inicio ASC")
    public fun desde(desde: String): List<ExecucaoEntidade>
}
