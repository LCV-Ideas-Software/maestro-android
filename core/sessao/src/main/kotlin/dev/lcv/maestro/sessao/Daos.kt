package dev.lcv.maestro.sessao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Os DAOs são bloqueantes de propósito: quem os chama é o repositório, dentro
 * de `runInTransaction`, a partir de `Dispatchers.IO`. Só os `Flow` são
 * assíncronos: são o que substitui o *polling* do web (especificação, seção
 * 4.2).
 *
 * **Não existe `@Update` de sessão.** Cada transição é um `UPDATE` das suas
 * colunas com o portão de status na própria instrução (`WHERE id = :id AND
 * status IN (:permitidos)`), e devolve quantas linhas mudou: zero é o CAS
 * perdido. O portão não pode ser esquecido por quem chama porque faz parte da
 * consulta (decisão do operador de 26/09/2026). As escritas do worker levam
 * ainda a cerca de execução (`execucaoAtual = :execucao`).
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

    /** Cancelar, marcar interrompida, pausar: status e erro, sob o portão; `:execucao` nulo dispensa a cerca. */
    @Query(
        "UPDATE sessoes SET status = :status, erro = :erro, atualizadaEm = :em " +
            "WHERE id = :id AND status IN (:permitidos) AND (:execucao IS NULL OR execucaoAtual = :execucao)",
    )
    public fun mudarStatus(id: String, permitidos: List<String>, status: String, erro: String?, em: String, execucao: Long?): Int

    /** O pedido de retomada (`sessions.ts:4787-4796`): volta à fila com o líder e o painel escolhidos, sob o status lido. */
    @Query(
        "UPDATE sessoes SET status = 'queued', liderDoCiclo = :lider, agentesAtivosJson = :agentesJson, erro = NULL, atualizadaEm = :em " +
            "WHERE id = :id AND status = :statusLido",
    )
    public fun retomar(id: String, statusLido: String, lider: String, agentesJson: String, em: String): Int

    /** A reivindicação de `preparar`: a execução assume a sessão, que passa a `running`; zero linhas = outro chegou antes. */
    @Query(
        "UPDATE sessoes SET status = 'running', execucaoAtual = :execucao, atualizadaEm = :em " +
            "WHERE id = :id AND status IN ('queued', 'running')",
    )
    public fun reivindicar(id: String, execucao: Long, em: String): Int

    /**
     * A troca de conteúdo do operador: só as colunas fornecidas (`COALESCE`
     * preserva as omitidas dentro do SQL), e só se a linha ainda estiver no
     * status em que foi lida.
     */
    @Query(
        "UPDATE sessoes SET titulo = COALESCE(:titulo, titulo), textoAtual = COALESCE(:textoAtual, textoAtual), atualizadaEm = :em " +
            "WHERE id = :id AND status = :statusLido",
    )
    public fun substituirConteudo(id: String, statusLido: String, titulo: String?, textoAtual: String?, em: String): Int

    /** `persistObservedCostFloor` (`sessions.ts:2998-3009`): monotônico, atômico, sem portão, nunca dentro do checkpoint. */
    @Query("UPDATE sessoes SET custoObservadoE8 = MAX(custoObservadoE8, :e8), atualizadaEm = :em WHERE id = :id")
    public fun subirPiso(id: String, e8: Long, em: String): Int

    /** O carimbo de um evento (`appendEvent` do web também mexe em `updated_at`), sob o portão e a cerca opcional. */
    @Query(
        "UPDATE sessoes SET atualizadaEm = :em " +
            "WHERE id = :id AND status IN (:permitidos) AND (:execucao IS NULL OR execucaoAtual = :execucao)",
    )
    public fun tocar(id: String, permitidos: List<String>, em: String, execucao: Long?): Int

    /**
     * O checkpoint (`persistCircularProgress`, `sessions.ts:3406-3437`): a
     * custódia inteira, o status e o erro resultantes, sob o portão e a cerca
     * de execução. Sempre chamado dentro da transação que inseriu o artefato.
     */
    @Query(
        "UPDATE sessoes SET autorAtual = :autorAtual, textoAtual = :textoAtual, custodiaArtefatoId = :custodiaArtefatoId, " +
            "artefatoAnteriorId = :artefatoAnteriorId, rodada = :rodada, indiceDoTurno = :indiceDoTurno, turnoDoArtefato = :turnoDoArtefato, " +
            "escalaJson = :escalaJson, agentesValidosJson = :agentesValidosJson, aprovacoesEstaveisJson = :aprovacoesEstaveisJson, " +
            "status = :status, erro = :erro, atualizadaEm = :em " +
            "WHERE id = :id AND status IN (:permitidos) AND execucaoAtual = :execucao",
    )
    public fun gravarCustodia(
        id: String,
        permitidos: List<String>,
        execucao: Long,
        autorAtual: String,
        textoAtual: String,
        custodiaArtefatoId: String,
        artefatoAnteriorId: String,
        rodada: Int,
        indiceDoTurno: Int,
        turnoDoArtefato: Int,
        escalaJson: String,
        agentesValidosJson: String,
        aprovacoesEstaveisJson: String,
        status: String,
        erro: String?,
        em: String,
    ): Int

    /** O fim da deliberação (3b): o texto final e o status terminal, sob o portão e a cerca. */
    @Query(
        "UPDATE sessoes SET textoFinal = :textoFinal, status = :status, erro = NULL, atualizadaEm = :em " +
            "WHERE id = :id AND status IN (:permitidos) AND execucaoAtual = :execucao",
    )
    public fun concluir(id: String, permitidos: List<String>, execucao: Long, textoFinal: String, status: String, em: String): Int
}

@Dao
public interface EventoDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    public fun inserir(evento: EventoEntidade): Long

    @Query("SELECT * FROM eventos WHERE sessaoId = :sessaoId ORDER BY seq ASC")
    public fun daSessao(sessaoId: String): List<EventoEntidade>

    @Query("SELECT * FROM eventos WHERE sessaoId = :sessaoId ORDER BY seq ASC")
    public fun observar(sessaoId: String): Flow<List<EventoEntidade>>
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

    /** O maior turno já gravado, para a reserva do turno órfão na retomada (`sessions.ts:3515-3521`). */
    @Query("SELECT COALESCE(MAX(turno), 0) FROM artefatos WHERE sessaoId = :sessaoId")
    public fun turnoMaximo(sessaoId: String): Int
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

    @Query("SELECT * FROM execucoes WHERE seq = :seq LIMIT 1")
    public fun uma(seq: Long): ExecucaoEntidade?

    /**
     * As execuções que tocam a janela que começa em [desde], para a soma do
     * orçamento de 24 horas: as que começaram dentro dela, as que ainda não
     * terminaram e as que começaram antes e terminaram dentro (quem soma
     * corta a parte de fora da janela).
     */
    @Query("SELECT * FROM execucoes WHERE inicio >= :desde OR fim IS NULL OR fim >= :desde ORDER BY inicio ASC")
    public fun naJanela(desde: String): List<ExecucaoEntidade>
}
