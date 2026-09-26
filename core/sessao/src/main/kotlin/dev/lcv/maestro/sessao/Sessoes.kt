package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Lançada dentro de uma transação quando um portão falha: desfaz tudo o que a transação escreveu. */
internal class CasPerdido : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * As transições da sessão (`persistSession`, `persistObservedCostFloor`, o
 * insert do `POST /sessions`, o cancelamento e a troca de conteúdo —
 * `sessions.ts:2906-3009, 4397-4436, 4651-4731`), cada uma como um `UPDATE`
 * condicional do [SessaoDao] com o evento gravado na mesma transação. Não há
 * remendo genérico nem escrita de entidade inteira: o portão de status está
 * na consulta. Os DAOs são bloqueantes e quem chama está em `Dispatchers.IO`.
 */
public class RepositorioDeSessoes(
    private val banco: BancoDaSessao,
    private val relogio: () -> Instant,
) {
    private fun agora(): String = FormatoDeInstante.iso(relogio())

    public fun carregar(id: String): SessaoEntidade? = banco.sessoes().carregar(id)

    public fun observar(id: String): Flow<SessaoEntidade?> = banco.sessoes().observar(id)

    public fun listar(): List<SessaoEntidade> = banco.sessoes().listar()

    /** `runnerStopRequested` visto do banco: as sessões que ainda estão na fila ou rodando. */
    public fun emExecucao(): List<SessaoEntidade> = banco.sessoes().emExecucao()

    public fun eventos(id: String): List<EventoDaSessao> = banco.eventos().daSessao(id).map(EventoEntidade::paraEvento)

    public fun observarEventos(id: String): Flow<List<EventoEntidade>> = banco.eventos().observar(id)

    /** O insert do `POST /sessions` (`sessions.ts:4397-4436`): a linha e o primeiro evento, juntos. */
    public fun criar(entrada: EntradaResolvida): SessaoEntidade {
        val id = "android-${UUID.randomUUID()}"
        val criadaEm = agora()
        val linha = SessaoEntidade(
            id = id,
            titulo = entrada.titulo,
            pedido = entrada.pedido,
            protocolo = entrada.protocolo,
            agenteInicial = entrada.agenteInicial.agente,
            liderDoCiclo = entrada.agenteInicial.agente,
            agentesAtivosJson = agentesJson(entrada.agentesAtivos),
            status = Estados.NA_FILA,
            textoAtual = Texto.sanear(entrada.conteudoInicial, 120_000),
            tetoDeCustoE8 = Dinheiro.paraE8(entrada.tetoDeCustoUsd),
            tetoDeMinutos = entrada.tetoDeMinutos,
            maxCiclos = entrada.maxCiclos,
            taxasJson = Taxas.paraJson(entrada.taxas),
            modelosJson = modelosJson(),
            criadaEm = criadaEm,
            atualizadaEm = criadaEm,
        )
        banco.runInTransaction {
            banco.sessoes().inserir(linha)
            banco.eventos().inserir(EventoDaSessao(em = criadaEm, status = EventoDaSessao.NA_FILA, mensagem = MENSAGEM_NA_FILA).paraEntidade(id))
        }
        return linha
    }

    /**
     * Um evento sob o portão (`pushEvent`, `sessions.ts:3369-3375`): gravado
     * só se a sessão ainda estiver em [seSituacaoEm] e, com [execucao], só
     * pela execução que a reivindicou. Devolve se gravou.
     */
    public fun anotar(id: String, evento: EventoDaSessao, seSituacaoEm: Set<String> = Estados.ATIVOS, execucao: Long? = null): Boolean = try {
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().tocar(id, seSituacaoEm.toList(), agora(), execucao) == 0) throw CasPerdido()
            banco.eventos().inserir(evento.paraEntidade(id))
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /** Uma transição de status com o seu evento (pausas da 3b, cancelamento, reconciliação), sob o portão e a cerca opcional. */
    public fun transicionar(
        id: String,
        status: String,
        erro: String?,
        evento: EventoDaSessao?,
        seSituacaoEm: Set<String> = Estados.ATIVOS,
        execucao: Long? = null,
    ): Boolean = try {
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().mudarStatus(id, seSituacaoEm.toList(), status, erro, agora(), execucao) == 0) throw CasPerdido()
            if (evento != null) banco.eventos().inserir(evento.paraEntidade(id))
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /** O fim da deliberação (3b): texto final e status terminal, sob o portão e a cerca. */
    public fun concluir(id: String, execucao: Long, textoFinal: String, status: String, evento: EventoDaSessao?): Boolean = try {
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().concluir(id, Estados.ATIVOS.toList(), execucao, textoFinal, status, agora()) == 0) throw CasPerdido()
            if (evento != null) banco.eventos().inserir(evento.paraEntidade(id))
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /** `persistObservedCostFloor`: `max(0, custo)`, monotônico e atômico numa instrução, nunca dentro do checkpoint. */
    public fun subirPisoDeCusto(id: String, custo: BigDecimal) {
        banco.sessoes().subirPiso(id, Dinheiro.paraE8(custo.max(BigDecimal.ZERO)), agora())
    }

    /** `handleMaestroAiSessionCancelPost` (`sessions.ts:4702-4731`). */
    public fun cancelar(id: String): Resultado<SessaoEntidade> {
        val linha = carregar(id) ?: return Resultado.Recusado(MENSAGEM_NAO_ENCONTRADA)
        if (linha.status !in Estados.ATIVOS) return Resultado.Recusado("Sessao ja finalizada; nada a cancelar.")
        val evento = EventoDaSessao(em = agora(), status = EventoDaSessao.BLOQUEADO, mensagem = MENSAGEM_CANCELADA)
        if (!transicionar(id, Estados.CANCELADA, MENSAGEM_CANCELADA, evento)) {
            return Resultado.Recusado("Sessao mudou de estado durante o cancelamento.")
        }
        return Resultado.Ok(carregar(id) ?: linha)
    }

    /**
     * `handleMaestroAiSessionContentPut` (`sessions.ts:4651-4693`): campos
     * ausentes ficam como estão, dentro do SQL (`COALESCE`), e a escrita só
     * acontece no status em que a linha foi lida.
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
            titulo = titulo?.let { Texto.sanear(it, 200) },
            textoAtual = conteudo?.let { Texto.sanear(it, 160_000) },
            em = agora(),
        )
        if (escritas == 0) return Resultado.Recusado(MENSAGEM_MUDOU_NA_EDICAO)
        return Resultado.Ok(carregar(id) ?: linha)
    }

    /**
     * A reconciliação na abertura do aplicativo (`sweepStaleSessions`,
     * `sessions.ts:4842-4844`, com o motivo daqui): a linha ainda ativa cujo
     * worker não está vivo vai para `error`, que é retomável.
     */
    public fun marcarInterrompida(id: String, evento: EventoDaSessao? = null): Boolean =
        transicionar(id, Estados.ERRO, MENSAGEM_INTERROMPIDA, evento)

    public companion object {
        public const val MENSAGEM_NA_FILA: String = "Maestro AI Android session queued."
        public const val MENSAGEM_NAO_ENCONTRADA: String = "Sessao Maestro AI nao encontrada."
        public const val MENSAGEM_CANCELADA: String = "Sessao cancelada pelo operador."
        public const val MENSAGEM_MUDOU_NA_EDICAO: String = "Sessao mudou de estado durante a edicao; recarregue antes de editar."
        public const val MENSAGEM_INTERROMPIDA: String =
            "Sessao interrompida: o processo do aplicativo foi encerrado antes de a deliberacao terminar."

        /** `JSON.stringify(active_agents)`. */
        public fun agentesJson(agentes: List<Provedor>): String = Json.ESTRITO.writeValueAsString(agentes.map { it.agente })

        /** `parseJson<ProviderKey[]>(active_agents_json, [])` filtrado por `isProviderKey`: tolerante, como no web. */
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
