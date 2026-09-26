package dev.lcv.maestro.sessao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * `maestro_ai_sessions` (`ensureSchema`, `sessions.ts:454-477`). As colunas
 * JSON ficam como texto e são lidas pelos leitores próprios: os tolerantes
 * (`taxasJson`, `modelosJson`, `agentesAtivosJson`, com valor padrão quando
 * não parseiam, `parseJson`) e os estritos (`eventosJson` e
 * `estadoCircularJson`, que falham fechado para `paused_resume_state_invalid`).
 * `tetoDeMinutos` é inteiro, e não `REAL` como no web: minuto é a unidade da
 * tela e do teto de produto (desvio declarado).
 */
@Entity(tableName = "sessoes")
public data class SessaoEntidade(
    @PrimaryKey val id: String,
    val titulo: String,
    val pedido: String,
    val protocolo: String,
    val agenteInicial: String,
    val liderDoCiclo: String,
    val agentesAtivosJson: String,
    @ColumnInfo(defaultValue = "{}") val estadoCircularJson: String = "{}",
    val autorAtual: String? = null,
    @ColumnInfo(defaultValue = "") val textoAtual: String = "",
    val textoFinal: String? = null,
    val status: String,
    val custoObservadoUsd: BigDecimal = BigDecimal.ZERO,
    val tetoDeCustoUsd: BigDecimal,
    val tetoDeMinutos: Int? = null,
    @ColumnInfo(defaultValue = "2") val maxCiclos: Int = 2,
    val taxasJson: String,
    val modelosJson: String,
    val eventosJson: String,
    val criadaEm: String,
    val atualizadaEm: String,
    val erro: String? = null,
)

/** `maestro_ai_artifacts` (`sessions.ts:517-542`), com o índice `(session_id, cycle, turn)`. */
@Entity(
    tableName = "artefatos",
    foreignKeys = [
        ForeignKey(entity = SessaoEntidade::class, parentColumns = ["id"], childColumns = ["sessaoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sessaoId", "ciclo", "turno"])],
)
public data class ArtefatoEntidade(
    @PrimaryKey val id: String,
    val sessaoId: String,
    val ciclo: Int,
    val turno: Int,
    val agente: String,
    /** `draft` ou `revision`. */
    val papel: String,
    val status: String,
    val titulo: String,
    val conteudoMd: String,
    val relatorioDeRevisaoJson: String,
    val auditoriaDeLinksJson: String,
    val custoUsd: BigDecimal = BigDecimal.ZERO,
    val modelo: String? = null,
    val artefatoAnteriorId: String? = null,
    @ColumnInfo(defaultValue = "0") val bytesDoConteudo: Long = 0,
    val criadoEm: String,
)

/**
 * `maestro_ai_settings` (`sessions.ts:482-494`), sem `configured_secrets_json`
 * (a chave é do cofre, seção 6), sem `models_json` (o modelo de cada provedor
 * é fixo em `Provedor.modelo`) e sem as colunas de migração do D1 legado.
 */
@Entity(tableName = "configuracoes")
public data class ConfiguracoesEntidade(
    @PrimaryKey val id: String = CONFIGURACOES_ID,
    val protocolo: String,
    val tetoDeCustoUsd: BigDecimal = BigDecimal.ZERO,
    val tetoDeMinutos: Int? = null,
    @ColumnInfo(defaultValue = "2") val maxCiclos: Int = 2,
    val taxasJson: String,
    val atualizadaEm: String,
)

public const val CONFIGURACOES_ID: String = "default"

/**
 * Uma linha de link do motor do `:core:protocolo`, gravada como o canônico a
 * grava: um JSON por `link_id`, global — o id já leva a impressão da origem,
 * e a listagem filtra por ela. `sessaoId` é só informativa.
 */
@Entity(tableName = "links", indices = [Index(value = ["sessaoId"])])
public data class LinhaDeLinkEntidade(
    @PrimaryKey val linkId: String,
    val sessaoId: String?,
    val linhaJson: String,
    val atualizadaEm: String,
)

/** O `events.ndjson` da auditoria de links: uma linha por evento, só acrescentada. */
@Entity(tableName = "eventos_de_links", indices = [Index(value = ["linkId"])])
public data class EventoDeLinkEntidade(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val sessaoId: String?,
    val tipo: String,
    val linkId: String,
    val linhaJson: String,
    val em: String,
)

/**
 * `StoredWebEvidence` sem os bytes: o registro e os cabeçalhos seguros ficam
 * na linha; o corpo, quando existe, fica no arquivo [caminhoDoCorpo], uma
 * geração imutável nomeada pelo id e pelo hash do conteúdo.
 */
@Entity(tableName = "evidencias")
public data class EvidenciaEntidade(
    @PrimaryKey val id: String,
    val registroJson: String,
    val cabecalhosJson: String,
    val caminhoDoCorpo: String? = null,
    val atualizadaEm: String,
)

/** Um anexo da sessão (manifesto de citações e afins): metadados aqui, bytes no arquivo. */
@Entity(
    tableName = "anexos",
    foreignKeys = [
        ForeignKey(entity = SessaoEntidade::class, parentColumns = ["id"], childColumns = ["sessaoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sessaoId"])],
)
public data class AnexoEntidade(
    @PrimaryKey val id: String,
    val sessaoId: String,
    val nomeOriginal: String,
    val tipoDeMidia: String,
    val caminho: String,
    val bytes: Long,
    val sha256: String,
    val criadoEm: String,
)

/**
 * Cada execução do serviço em primeiro plano, para a tela somar o que já foi
 * gasto do orçamento agregado de seis horas por 24 horas do `dataSync`
 * (especificação, seção 4.1). A 3b grava; esta entrega só cria a tabela.
 */
@Entity(
    tableName = "execucoes",
    foreignKeys = [
        ForeignKey(entity = SessaoEntidade::class, parentColumns = ["id"], childColumns = ["sessaoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sessaoId"]), Index(value = ["inicio"])],
)
public data class ExecucaoEntidade(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val sessaoId: String,
    val inicio: String,
    val fim: String? = null,
    val motivoDaParada: String? = null,
)
