package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Lançada dentro de uma transação quando um portão falha: desfaz tudo o que a transação escreveu. */
internal class CasPerdido : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Quem tira a sessão de `queued`/`running` fecha a execução que ela tinha, na
 * mesma transação: uma linha de `execucoes` só fica aberta enquanto a sessão
 * está ativa, e o orçamento de 24 horas nunca conta uma execução encerrada
 * como viva. [motivo] é o status novo, ou o rótulo do caso anormal.
 */
internal fun BancoDaSessao.encerrarExecucaoDa(id: String, em: String, motivo: String) {
    sessoes().carregar(id)?.execucaoAtual?.let { execucoes().encerrar(it, em, motivo) }
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

    /** A lista observada, para a tela; `listar()` é uma fotografia. */
    public fun observarTodas(): Flow<List<SessaoEntidade>> = banco.sessoes().observarTodas()

    /** `runnerStopRequested` visto do banco: as sessões que ainda estão na fila ou rodando. */
    public fun emExecucao(): List<SessaoEntidade> = banco.sessoes().emExecucao()

    public fun eventos(id: String): List<EventoDaSessao> = banco.eventos().daSessao(id).map(EventoEntidade::paraEvento)

    public fun observarEventos(id: String): Flow<List<EventoEntidade>> = banco.eventos().observar(id)

    /**
     * O insert do `POST /sessions` (`sessions.ts:4397-4436`): a linha e o primeiro evento, juntos.
     * [junto] grava na mesma transação o que a sessão não pode existir sem (o manifesto do
     * formulário, em [AnexosDaSessao.criarSessao]); se ele falha, nada é gravado.
     */
    public fun criar(entrada: EntradaResolvida, junto: (SessaoEntidade) -> Unit = {}): SessaoEntidade {
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
            junto(linha)
        }
        return linha
    }

    /**
     * Um evento sob o portão (`pushEvent`, `sessions.ts:3369-3375`): gravado
     * só se a sessão ainda estiver em [seSituacaoEm] e, com [execucao], só
     * pela execução que a reivindicou. Devolve se gravou. Um evento da
     * execução dona é, por padrão, um desfecho registrado: o marcador da
     * chamada em voo dela é apagado na mesma transação. O rótulo de uma
     * parada pelo sistema não é desfecho ([limpaChamada] falso): o marcador
     * tem de sobreviver a ele (decisão 16).
     */
    public fun anotar(
        id: String,
        evento: EventoDaSessao,
        seSituacaoEm: Set<String> = Estados.ATIVOS,
        execucao: Long? = null,
        limpaChamada: Boolean = true,
    ): Boolean = try {
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().tocar(id, seSituacaoEm.toList(), agora(), execucao) == 0) throw CasPerdido()
            banco.eventos().inserir(evento.paraEntidade(id))
            if (limpaChamada) execucao?.let { banco.execucoes().limparChamada(it) }
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /**
     * O marcador da chamada paga (decisão 16 do operador, 27/09/2026): gravado
     * imediatamente antes de cada despacho pago, só numa execução ainda
     * aberta. `false` é "a execução já não é dona": nada é enviado.
     */
    public fun marcarChamadaEmVoo(execucao: Long, provedor: Provedor): Boolean =
        banco.execucoes().marcarChamada(execucao, provedor.agente, agora()) == 1

    /** As execuções que tocam a janela de 24 horas que termina em [agora] (orçamento do `dataSync`, seção 4.1). */
    public fun execucoesNaJanela(agora: Instant): List<ExecucaoEntidade> =
        banco.execucoes().naJanela(FormatoDeInstante.iso(agora.minus(Orcamento.JANELA)))

    /**
     * Uma transição de status com o seu evento (pausas da 3b, cancelamento,
     * reconciliação), sob o portão e a cerca opcional. Se o status novo sai de
     * `queued`/`running`, a execução da sessão é fechada junto, com o status
     * como motivo.
     */
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
            execucao?.let { banco.execucoes().limparChamada(it) }
            if (status !in Estados.ATIVOS) banco.encerrarExecucaoDa(id, agora(), status)
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /**
     * O fim da deliberação (3b): texto final e status terminal, sob o portão
     * e a cerca; a execução fecha junto. O texto final tem o mesmo teto do
     * texto aceito ([MarkdownDoArtefato.conferirTexto]), recusado, não cortado.
     */
    public fun concluir(id: String, execucao: Long, textoFinal: String, status: String, evento: EventoDaSessao?): Boolean = try {
        MarkdownDoArtefato.conferirTexto(textoFinal)
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().concluir(id, Estados.ATIVOS.toList(), execucao, textoFinal, status, agora()) == 0) throw CasPerdido()
            if (evento != null) banco.eventos().inserir(evento.paraEntidade(id))
            banco.execucoes().encerrar(execucao, agora(), status)
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    /**
     * O lugar do `persistObservedCostFloor` do web: cada chamada paga soma o
     * seu custo ao acumulado da sessão, atômico, sem portão e nunca dentro do
     * checkpoint (um checkpoint desfeito não apaga gasto incorrido). Devolve
     * o total gravado, que é o que o guarda de custo compara com o teto —
     * inclusive o gasto de outra execução que se cruzou com esta (achado do
     * Codex na #70). Um custo negativo conta zero; acima da coluna, satura.
     */
    public fun somarCusto(id: String, custo: BigDecimal): BigDecimal = banco.runInTransaction<BigDecimal> {
        banco.sessoes().somarCusto(id, Dinheiro.paraE8Observado(custo.max(BigDecimal.ZERO)), agora())
        Dinheiro.deE8(banco.sessoes().carregar(id)?.custoObservadoE8 ?: 0L)
    }

    /** O total observado gravado agora, para o guarda de custo não comparar um valor local velho com o teto. */
    public fun custoObservado(id: String): BigDecimal = Dinheiro.deE8(banco.sessoes().carregar(id)?.custoObservadoE8 ?: 0L)

    /** `handleMaestroAiSessionCancelPost` (`sessions.ts:4702-4731`). */
    public fun cancelar(id: String): Resultado<SessaoEntidade> {
        val linha = carregar(id) ?: return Resultado.Recusado(MENSAGEM_NAO_ENCONTRADA)
        if (linha.status !in Estados.ATIVOS) return Resultado.Recusado("Sessao ja finalizada; nada a cancelar.")
        val evento = EventoDaSessao(em = agora(), status = EventoDaSessao.BLOQUEADO, mensagem = MENSAGEM_CANCELADA)
        // Relida na mesma transação: uma releitura que falhasse depois do commit diria que nada foi gravado,
        // com o cancelamento já feito e o trabalho sem ser cancelado (revisão antes do push da rodada 10 na #78).
        val cancelada = banco.runInTransaction<SessaoEntidade?> {
            if (transicionar(id, Estados.CANCELADA, MENSAGEM_CANCELADA, evento)) carregar(id) ?: linha else null
        } ?: return Resultado.Recusado("Sessao mudou de estado durante o cancelamento.")
        return Resultado.Ok(cancelada)
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
     * worker não está vivo vai para `error`, que é retomável, e a execução
     * que ela tinha é fechada na mesma transação (`interrupted`): quem declara
     * o worker morto fecha a linha dele, senão o orçamento de 24 horas o
     * conta como vivo até a próxima reivindicação.
     *
     * [execucaoInspecionada] é o `execucaoAtual` que quem reconcilia leu ao
     * decidir que o worker morreu (`null` para uma sessão nunca reivindicada);
     * a escrita exige esse mesmo valor na linha, para que uma execução
     * substituta que reivindicou a sessão nesse meio-tempo não seja pausada
     * nem fechada como morta (achado do Codex na #67, rodada 5).
     */
    public fun marcarInterrompida(
        id: String,
        execucaoInspecionada: Long?,
        evento: EventoDaSessao? = null,
        mensagem: String = MENSAGEM_INTERROMPIDA,
    ): Boolean = try {
        banco.runInTransaction<Boolean> {
            if (banco.sessoes().interromper(id, mensagem, agora(), execucaoInspecionada) == 0) throw CasPerdido()
            if (evento != null) banco.eventos().inserir(evento.paraEntidade(id))
            execucaoInspecionada?.let { banco.execucoes().encerrar(it, agora(), MOTIVO_INTERROMPIDA) }
            true
        }
    } catch (perdido: CasPerdido) {
        false
    }

    public companion object {
        public const val MENSAGEM_NA_FILA: String = "Maestro AI Android session queued."
        public const val MENSAGEM_NAO_ENCONTRADA: String = "Sessao Maestro AI nao encontrada."
        public const val MENSAGEM_CANCELADA: String = "Sessao cancelada pelo operador."
        public const val MENSAGEM_MUDOU_NA_EDICAO: String = "Sessao mudou de estado durante a edicao; recarregue antes de editar."
        public const val MENSAGEM_TETO_NAO_SOBE: String = "O novo teto deve ser maior que o teto atual e que o custo ja observado."
        public const val MENSAGEM_INTERROMPIDA: String =
            "Sessao interrompida: o processo do aplicativo foi encerrado antes de a deliberacao terminar."

        /**
         * O teto novo de uma retomada (plano do `:app`, emenda A9): o teto vale
         * sobre o acumulado da sessão inteira (emenda A13 do plano da 3a), então
         * retomar com o mesmo teto pausaria de novo na primeira chamada. Valor
         * positivo que cabe na coluna, acima do teto atual **e** do custo
         * observado; devolve o valor em 1e-8 USD. Quem grava é [Retomada.pedir],
         * na transação da própria retomada.
         */
        internal fun conferirTeto(linha: SessaoEntidade, teto: BigDecimal): Resultado<Long> {
            if (teto.signum() <= 0) return Resultado.Recusado(RepositorioDeConfiguracoes.MENSAGEM_TETO_POSITIVO)
            if (!Dinheiro.cabe(teto)) return Resultado.Recusado(RepositorioDeConfiguracoes.MENSAGEM_TETO_ACIMA_DO_MAXIMO)
            val tetoE8 = Dinheiro.paraE8(teto)
            if (tetoE8 <= linha.tetoDeCustoE8 || tetoE8 <= linha.custoObservadoE8) return Resultado.Recusado(MENSAGEM_TETO_NAO_SOBE)
            return Resultado.Ok(tetoE8)
        }

        internal fun mensagemDoTeto(teto: BigDecimal): String =
            "Teto financeiro da sessao elevado para US$ ${Custo.paraExibir(teto).toPlainString()} pelo operador."

        /** Decisão 16: a execução morreu com uma chamada paga em voo; só o operador retoma. */
        public fun mensagemDeChamadaIndeterminada(provedor: Provedor): String =
            "Chamada paga a ${provedor.rotulo} sem resultado registrado: a execucao anterior morreu durante ou logo apos a chamada; retome manualmente."
        /** O rótulo da execução cujo processo morreu e que a reconciliação fechou. */
        public const val MOTIVO_INTERROMPIDA: String = "interrupted"

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
