package dev.lcv.maestro.sessao

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `maestro_ai_sessions` (`ensureSchema`, `sessions.ts:454-477`) como o Room
 * a guarda, e não como o D1 a guardava (decisões do operador de 26/09/2026):
 *
 * - o jornal é a tabela [EventoEntidade], não uma coluna JSON;
 * - a custódia circular (`circular_state_json` no web) são colunas tipadas
 *   desta linha: o artefato de custódia e o anterior, os contadores e as três
 *   listas de agentes; a leitura de volta é conferida na retomada, não
 *   desserializada de um blob;
 * - [execucaoAtual] é a cerca de execução: a execução que reivindicou a
 *   sessão em `preparar`, exigida em toda escrita do worker, para que uma
 *   escrita tardia de uma execução superada falhe por si só;
 * - dinheiro em inteiros de 10⁻⁸ USD ([Dinheiro]).
 *
 * Os ids de custódia **não** são chaves estrangeiras: `artefatos` já aponta
 * para `sessoes`, e o ciclo só traria sutileza; quem lê a custódia confere que
 * o artefato existe nesta sessão e está aceito. `taxasJson` e
 * `agentesAtivosJson` são lidos com tolerância, como no web (`parseJson`).
 * `tetoDeMinutos` é inteiro (minuto é a unidade da tela e do teto de produto).
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
    val status: String,
    val autorAtual: String? = null,
    @ColumnInfo(defaultValue = "") val textoAtual: String = "",
    val textoFinal: String? = null,
    val erro: String? = null,
    @ColumnInfo(defaultValue = "0") val custoObservadoE8: Long = 0,
    val tetoDeCustoE8: Long,
    val tetoDeMinutos: Int? = null,
    @ColumnInfo(defaultValue = "2") val maxCiclos: Int = 2,
    val taxasJson: String,
    val modelosJson: String,
    // ── custódia circular ──
    val custodiaArtefatoId: String? = null,
    val artefatoAnteriorId: String? = null,
    @ColumnInfo(defaultValue = "1") val rodada: Int = 1,
    @ColumnInfo(defaultValue = "0") val indiceDoTurno: Int = 0,
    @ColumnInfo(defaultValue = "0") val turnoDoArtefato: Int = 0,
    @ColumnInfo(defaultValue = "[]") val escalaJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val agentesValidosJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val aprovacoesEstaveisJson: String = "[]",
    // ── cerca de execução ──
    val execucaoAtual: Long? = null,
    val criadaEm: String,
    val atualizadaEm: String,
)

/**
 * O jornal da sessão (`SessionEvent`, `sessions.ts:196-209`), uma linha por
 * evento, só acrescentada; `seq` é a ordem. Sem blob, um evento é gravado
 * dentro da mesma transação da transição que o motivou e nunca segura uma
 * transição por estar malformado.
 */
@Entity(
    tableName = "eventos",
    foreignKeys = [
        ForeignKey(entity = SessaoEntidade::class, parentColumns = ["id"], childColumns = ["sessaoId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["sessaoId", "seq"])],
)
public data class EventoEntidade(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val sessaoId: String,
    val em: String,
    val agente: String? = null,
    val papel: String? = null,
    val status: String,
    val mensagem: String,
    val custoE8: Long? = null,
    val fonteDoCusto: String? = null,
    val modelo: String? = null,
    val auditoriaDeLinksJson: String? = null,
    val auditoriaFinalJson: String? = null,
)

/**
 * `maestro_ai_artifacts` (`sessions.ts:517-542`) mais [textoAceito]: o texto
 * exatamente como foi aceito (canônico, [EstadoCircular.textoCanonico]), que
 * é o que a retomada compara com `sessoes.textoAtual`. [conteudoMd] é o
 * markdown do web, byte a byte, derivado para exibição e exportação e nunca
 * lido de volta.
 */
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
    val textoAceito: String,
    val conteudoMd: String,
    val relatorioDeRevisaoJson: String,
    val auditoriaDeLinksJson: String,
    @ColumnInfo(defaultValue = "0") val custoE8: Long = 0,
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
    @ColumnInfo(defaultValue = "0") val tetoDeCustoE8: Long = 0,
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
 * Cada execução do worker sobre uma sessão: nasce em `preparar`, que a
 * reivindica em `sessoes.execucaoAtual`; a 3b grava [fim] e [motivoDaParada].
 * A tela soma as que tocam a janela de 24 horas do orçamento agregado do
 * `dataSync` (especificação, seção 4.1).
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
