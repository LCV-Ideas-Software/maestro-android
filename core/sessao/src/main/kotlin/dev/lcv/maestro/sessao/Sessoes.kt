package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `SessionPatch` (`sessions.ts:2910-2935`): as nove colunas que o runner e os
 * handlers podem escrever. `Campo.Presente(null)` grava `null` de verdade em
 * `textoFinal` e `erro` (o motivo declarado do web, `:2946-2947`).
 */
public data class Remendo(
    val status: Campo<String> = Campo.Ausente,
    val liderDoCiclo: Campo<String> = Campo.Ausente,
    val agentesAtivosJson: Campo<String> = Campo.Ausente,
    val estadoCircularJson: Campo<String> = Campo.Ausente,
    val autorAtual: Campo<String?> = Campo.Ausente,
    val textoAtual: Campo<String> = Campo.Ausente,
    val textoFinal: Campo<String?> = Campo.Ausente,
    val custoObservadoUsd: Campo<BigDecimal> = Campo.Ausente,
    val erro: Campo<String?> = Campo.Ausente,
) {
    internal val vazio: Boolean
        get() = listOf(status, liderDoCiclo, agentesAtivosJson, estadoCircularJson, autorAtual, textoAtual, textoFinal, custoObservadoUsd, erro)
            .all { it is Campo.Ausente }

    internal fun aplicar(linha: SessaoEntidade): SessaoEntidade = linha.copy(
        status = status.ou(linha.status),
        liderDoCiclo = liderDoCiclo.ou(linha.liderDoCiclo),
        agentesAtivosJson = agentesAtivosJson.ou(linha.agentesAtivosJson),
        estadoCircularJson = estadoCircularJson.ou(linha.estadoCircularJson),
        autorAtual = autorAtual.ou(linha.autorAtual),
        textoAtual = textoAtual.ou(linha.textoAtual),
        textoFinal = textoFinal.ou(linha.textoFinal),
        custoObservadoUsd = custoObservadoUsd.ou(linha.custoObservadoUsd),
        erro = erro.ou(linha.erro),
    )

    /** `{ ...base, ...this }`: o que está presente aqui vence o que está em [base]. */
    internal fun sobre(base: Remendo): Remendo = Remendo(
        status = status.senao(base.status),
        liderDoCiclo = liderDoCiclo.senao(base.liderDoCiclo),
        agentesAtivosJson = agentesAtivosJson.senao(base.agentesAtivosJson),
        estadoCircularJson = estadoCircularJson.senao(base.estadoCircularJson),
        autorAtual = autorAtual.senao(base.autorAtual),
        textoAtual = textoAtual.senao(base.textoAtual),
        textoFinal = textoFinal.senao(base.textoFinal),
        custoObservadoUsd = custoObservadoUsd.senao(base.custoObservadoUsd),
        erro = erro.senao(base.erro),
    )

    private fun <T> Campo<T>.ou(atual: T): T = when (this) {
        is Campo.Presente -> valor
        Campo.Ausente -> atual
    }

    private fun <T> Campo<T>.senao(outro: Campo<T>): Campo<T> = if (this is Campo.Presente) this else outro
}

/** Lançada dentro da transação quando o portão de status falha: desfaz tudo o que a transação escreveu. */
internal class CasPerdido : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * `loadSession`, `persistSession`, `persistObservedCostFloor`, o insert do
 * `POST /sessions`, o cancelamento e a troca de conteúdo
 * (`sessions.ts:2906-3009, 4397-4436, 4651-4731`).
 *
 * Cada escrita é uma transação do Room: o web faz `UPDATE … WHERE id = ? AND
 * status IN (…)` num comando só; aqui a leitura, o portão e a escrita ficam
 * dentro de `runInTransaction`, que é a mesma atomicidade. Os DAOs são
 * bloqueantes e quem chama está em `Dispatchers.IO`.
 */
public class RepositorioDeSessoes(
    private val banco: BancoDaSessao,
    private val relogio: () -> Instant,
) {
    public fun carregar(id: String): SessaoEntidade? = banco.sessoes().carregar(id)

    public fun observar(id: String): Flow<SessaoEntidade?> = banco.sessoes().observar(id)

    public fun listar(): List<SessaoEntidade> = banco.sessoes().listar()

    /** `runnerStopRequested` visto do banco: as sessões que ainda estão na fila ou rodando. */
    public fun emExecucao(): List<SessaoEntidade> = banco.sessoes().emExecucao()

    /** O insert do `POST /sessions` (`sessions.ts:4397-4436`). */
    public fun criar(entrada: EntradaResolvida): SessaoEntidade {
        val id = "android-${UUID.randomUUID()}"
        val criadaEm = FormatoDeInstante.iso(relogio())
        val primeiroEvento = EventoDaSessao(em = criadaEm, status = EventoDaSessao.NA_FILA, mensagem = MENSAGEM_NA_FILA)
        val linha = SessaoEntidade(
            id = id,
            titulo = entrada.titulo,
            pedido = entrada.pedido,
            protocolo = entrada.protocolo,
            agenteInicial = entrada.agenteInicial.agente,
            liderDoCiclo = entrada.agenteInicial.agente,
            agentesAtivosJson = agentesJson(entrada.agentesAtivos),
            estadoCircularJson = "{}",
            autorAtual = null,
            textoAtual = Texto.sanear(entrada.conteudoInicial, 120_000),
            textoFinal = null,
            status = Estados.NA_FILA,
            custoObservadoUsd = BigDecimal.ZERO,
            tetoDeCustoUsd = entrada.tetoDeCustoUsd,
            tetoDeMinutos = entrada.tetoDeMinutos,
            maxCiclos = entrada.maxCiclos,
            taxasJson = Taxas.paraJson(entrada.taxas),
            modelosJson = modelosJson(),
            eventosJson = Jornal.serializar(listOf(primeiroEvento)),
            criadaEm = criadaEm,
            atualizadaEm = criadaEm,
            erro = null,
        )
        banco.sessoes().inserir(linha)
        return linha
    }

    /**
     * `persistSession`: só as colunas presentes em [remendo], mais o evento
     * anexado ao jornal, mais `atualizadaEm`; com [seSituacaoEm], só se a
     * linha ainda estiver num desses status. Devolve se escreveu.
     */
    public fun persistir(
        id: String,
        remendo: Remendo,
        seSituacaoEm: Set<String>? = null,
        evento: EventoDaSessao? = null,
    ): Boolean = try {
        banco.runInTransaction<Boolean> { persistirDentroDaTransacao(id, remendo, seSituacaoEm, evento) }
    } catch (perdido: CasPerdido) {
        false
    }

    /**
     * O miolo de [persistir], para quem já está numa transação (o checkpoint
     * do turno). Lança [CasPerdido] quando o portão falha, para a transação
     * inteira desfazer; devolve `false` só quando não há nada a escrever.
     */
    internal fun persistirDentroDaTransacao(
        id: String,
        remendo: Remendo,
        seSituacaoEm: Set<String>?,
        evento: EventoDaSessao?,
    ): Boolean {
        if (remendo.vazio && evento == null) return false
        val linha = banco.sessoes().carregar(id) ?: throw CasPerdido()
        if (seSituacaoEm != null && linha.status !in seSituacaoEm) throw CasPerdido()
        val eventosJson = if (evento == null) linha.eventosJson else Jornal.anexar(linha.eventosJson, evento)
        val nova = remendo.aplicar(linha).copy(eventosJson = eventosJson, atualizadaEm = FormatoDeInstante.iso(relogio()))
        return banco.sessoes().atualizar(nova) > 0
    }

    /** `persistObservedCostFloor`: monotônico, `max(0, custo)`, comparado como número. */
    public fun persistirPisoDeCusto(id: String, custo: BigDecimal) {
        val piso = custo.max(BigDecimal.ZERO)
        banco.runInTransaction {
            val linha = banco.sessoes().carregar(id) ?: return@runInTransaction
            if (linha.custoObservadoUsd < piso) {
                banco.sessoes().atualizar(linha.copy(custoObservadoUsd = piso, atualizadaEm = FormatoDeInstante.iso(relogio())))
            }
        }
    }

    /** `handleMaestroAiSessionCancelPost` (`sessions.ts:4702-4731`). */
    public fun cancelar(id: String): Resultado<SessaoEntidade> {
        val linha = carregar(id) ?: return Resultado.Recusado(MENSAGEM_NAO_ENCONTRADA)
        if (linha.status !in Estados.ATIVOS) return Resultado.Recusado("Sessao ja finalizada; nada a cancelar.")
        val aplicado = persistir(
            id,
            Remendo(status = Campo.Presente(Estados.CANCELADA), erro = Campo.Presente(MENSAGEM_CANCELADA)),
            seSituacaoEm = Estados.ATIVOS,
            evento = EventoDaSessao(em = FormatoDeInstante.iso(relogio()), status = EventoDaSessao.BLOQUEADO, mensagem = MENSAGEM_CANCELADA),
        )
        if (!aplicado) return Resultado.Recusado("Sessao mudou de estado durante o cancelamento.")
        return Resultado.Ok(carregar(id) ?: linha)
    }

    /**
     * `handleMaestroAiSessionContentPut` (`sessions.ts:4651-4693`): campos
     * ausentes mantêm o valor atual. A escrita é condicional ao status lido:
     * uma retomada ou um cancelamento entre a leitura e a escrita recusa a
     * edição em vez de sobrescrever o progresso concorrente (o web escreve
     * sem portão; achado do Codex na #67).
     */
    public fun substituirConteudo(id: String, titulo: String?, conteudo: String?): Resultado<SessaoEntidade> {
        val linha = carregar(id) ?: return Resultado.Recusado(MENSAGEM_NAO_ENCONTRADA)
        if (linha.status in Estados.ATIVOS) {
            return Resultado.Recusado("Sessao em execucao; aguarde terminar ou cancele antes de editar o conteudo.")
        }
        if (linha.status in Estados.RETOMAVEIS && linha.textoFinal == null && conteudo != null) {
            return Resultado.Recusado(
                "Conteudo de uma sessao retomavel nao pode ser substituido fora da cadeia de custodia. " +
                    "Retome a sessao para preservar autoria, hash e historico circular.",
            )
        }
        val escritas = banco.sessoes().substituirConteudo(
            id = id,
            statusLido = linha.status,
            titulo = Texto.sanear(titulo ?: linha.titulo, 200),
            textoAtual = Texto.sanear(conteudo ?: linha.textoAtual, 160_000),
            atualizadaEm = FormatoDeInstante.iso(relogio()),
        )
        if (escritas == 0) return Resultado.Recusado(MENSAGEM_MUDOU_NA_EDICAO)
        return Resultado.Ok(carregar(id) ?: linha)
    }

    /**
     * A reconciliação na abertura do aplicativo (`sweepStaleSessions`,
     * `sessions.ts:4842-4844`, com o motivo daqui): a linha ainda ativa cujo
     * worker não está vivo vai para `error`, que é retomável. [evento] leva o
     * rótulo do motivo de parada quando o WorkManager o informa.
     */
    public fun marcarInterrompida(id: String, evento: EventoDaSessao? = null): Boolean = persistir(
        id,
        Remendo(status = Campo.Presente(Estados.ERRO), erro = Campo.Presente(MENSAGEM_INTERROMPIDA)),
        seSituacaoEm = Estados.ATIVOS,
        evento = evento,
    )

    public companion object {
        public const val MENSAGEM_NA_FILA: String = "Maestro AI Android session queued."
        public const val MENSAGEM_NAO_ENCONTRADA: String = "Sessao Maestro AI nao encontrada."
        public const val MENSAGEM_CANCELADA: String = "Sessao cancelada pelo operador."
        public const val MENSAGEM_MUDOU_NA_EDICAO: String = "Sessao mudou de estado durante a edicao; recarregue antes de editar."
        public const val MENSAGEM_INTERROMPIDA: String =
            "Sessao interrompida: o processo do aplicativo foi encerrado antes de a deliberacao terminar."

        /** `JSON.stringify(active_agents)`. */
        public fun agentesJson(agentes: List<Provedor>): String =
            Json.ESTRITO.writeValueAsString(agentes.map { it.agente })

        /** `parseJson<ProviderKey[]>(active_agents_json, [])` filtrado por `isProviderKey`. */
        public fun lerAgentes(texto: String?): List<Provedor> {
            val raiz = Json.tolerante(texto) ?: return emptyList()
            if (!raiz.isArray) return emptyList()
            return raiz.mapNotNull { no -> no.takeIf { it.isTextual }?.let { Agentes.porChave(it.textValue()) } }
        }

        /** `models_json`: o modelo fixo de cada provedor, gravado para o histórico dizer com que modelo a sessão correu. */
        public fun modelosJson(): String {
            val raiz = Json.ESTRITO.createObjectNode()
            Provedor.entries.forEach { raiz.put(it.agente, it.modelo) }
            return Json.ESTRITO.writeValueAsString(raiz)
        }
    }
}
