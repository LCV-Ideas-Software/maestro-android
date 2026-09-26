package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.time.Instant

/** O que [Retomada.preparar] devolve ao worker antes do primeiro turno. */
public sealed interface Preparacao {
    /** A linha não existe mais. */
    public data object SessaoAusente : Preparacao

    /** O jornal não passou na leitura estrita; a sessão foi para `paused_resume_state_invalid`. */
    public data class JornalInvalido(val mensagem: String) : Preparacao

    /** A custódia circular não validou; evento bloqueado anexado e sessão em `paused_resume_state_invalid`. */
    public data class CustodiaInvalida(val mensagem: String) : Preparacao

    /** Alguém mudou o status no meio (cancelamento, varredura): o runner para. */
    public data object Perdida : Preparacao

    /** Uma execução nova: o líder redige; a âncora do tempo é `criadaEm`. */
    public data class Nova(
        val sessao: SessaoEntidade,
        val escala: List<Provedor>,
        val lider: Provedor,
        val eventos: List<EventoDaSessao>,
        val ancora: Instant,
    ) : Preparacao

    /** Uma retomada: rascunho pulado, custódia restaurada, status já `running`; a âncora é o agora. */
    public data class Retomar(
        val sessao: SessaoEntidade,
        val escala: List<Provedor>,
        val lider: Provedor,
        val eventos: List<EventoDaSessao>,
        val autorAtual: Provedor,
        val textoAtual: String,
        val progresso: ProgressoDaRetomada,
        val ancora: Instant,
    ) : Preparacao
}

/**
 * `handleMaestroAiSessionResumePost` e a fatia de preparação do `runSession`
 * (`sessions.ts:4733-4815, 3320-3355, 3500-3563`). [pedir] deixa a sessão
 * `queued` e devolve a linha; quem enfileira o worker é a 3b (o
 * `waitUntil` do web). [preparar] é o que o worker chama depois do seu
 * próprio `runnerStopRequested`.
 */
public class Retomada(
    private val banco: BancoDaSessao,
    private val sessoes: RepositorioDeSessoes,
    private val artefatos: RepositorioDeArtefatos,
    private val relogio: () -> Instant,
) {
    /**
     * `POST /sessions/{id}/resume`. [liderPedido] e [painelPedido] são o corpo
     * cru (ausentes = os da linha); [chaves] é o que o cofre respondeu por
     * provedor. Mensagens do web.
     */
    public fun pedir(
        id: String,
        liderPedido: String?,
        painelPedido: List<String>?,
        chaves: Map<Provedor, Boolean?>,
    ): Resultado<SessaoEntidade> {
        val linha = sessoes.carregar(id) ?: return Resultado.Recusado(RepositorioDeSessoes.MENSAGEM_NAO_ENCONTRADA)
        if (linha.status in Estados.ATIVOS) return Resultado.Recusado("Sessao ainda ativa; nada a retomar.")
        if (linha.status !in Estados.RETOMAVEIS || linha.textoFinal != null) {
            return Resultado.Recusado("Sessao concluida; nada a retomar.")
        }
        val salvos = RepositorioDeSessoes.lerAgentes(linha.agentesAtivosJson).map { it.agente }
        val pedidos = painelPedido ?: salvos
        val painel = pedidos.mapNotNull(Agentes::porChave)
        if (painel.size != pedidos.size || painel.toSet().size != painel.size) {
            return Resultado.Recusado("O painel de retomada contem agente invalido ou duplicado.")
        }
        if (painel.size < 2) return Resultado.Recusado("Selecione ao menos dois agentes para retomar a revisao circular.")
        val liderBruto = liderPedido ?: linha.liderDoCiclo.ifEmpty { linha.agenteInicial }
        val lider = Agentes.porChave(liderBruto)?.takeIf { it in painel }
            ?: return Resultado.Recusado("O agente lider da retomada deve pertencer ao painel selecionado.")
        val taxas = Taxas.lerBruto(linha.taxasJson)
        val indisponiveis = painel.filter { chaves[it] != true || !Taxas.positivas(taxas[it]) }
        if (indisponiveis.isNotEmpty()) {
            return Resultado.Recusado("Agentes indisponiveis para retomada: ${indisponiveis.joinToString(", ") { it.rotulo }}.")
        }
        val aplicado = sessoes.persistir(
            id,
            Remendo(
                status = Campo.Presente(Estados.NA_FILA),
                liderDoCiclo = Campo.Presente(lider.agente),
                agentesAtivosJson = Campo.Presente(RepositorioDeSessoes.agentesJson(painel)),
                erro = Campo.Presente(null),
            ),
            seSituacaoEm = setOf(linha.status),
            evento = EventoDaSessao(
                em = FormatoDeInstante.iso(relogio()),
                status = EventoDaSessao.RODANDO,
                mensagem = "Sessao retomada pelo operador com ${lider.rotulo} como lider do ciclo.",
            ),
        )
        if (!aplicado) return Resultado.Recusado(MENSAGEM_MUDOU_DE_ESTADO)
        val retomada = sessoes.carregar(id)
        if (retomada?.status != Estados.NA_FILA) return Resultado.Recusado(MENSAGEM_MUDOU_DE_ESTADO)
        return Resultado.Ok(retomada)
    }

    /** A escala do ciclo (`sessions.ts:3336-3341`): os ativos a partir de quem vem depois do líder, líder por último. */
    public fun escala(linha: SessaoEntidade): Pair<Provedor, List<Provedor>> {
        val ativos = RepositorioDeSessoes.lerAgentes(linha.agentesAtivosJson).ifEmpty { Provedor.entries }
        val inicialOriginal = Agentes.sanear(linha.agenteInicial, Provedor.CLAUDE)
        val candidato = Agentes.sanear(linha.liderDoCiclo.ifEmpty { linha.agenteInicial }, inicialOriginal)
        val lider = if (candidato in ativos) candidato else inicialOriginal
        val indice = ativos.indexOf(lider)
        return lider to (ativos.drop(indice + 1) + ativos.take(indice + 1))
    }

    /** A preparação do `runSession`: jornal estrito, e, numa retomada, a custódia validada e restaurada. */
    public fun preparar(id: String): Preparacao {
        val linha = sessoes.carregar(id) ?: return Preparacao.SessaoAusente
        val (lider, escala) = escala(linha)
        val eventos = try {
            Jornal.lerEstrito(linha.eventosJson)
        } catch (erro: IntegridadeDeLinks.Falha) {
            val mensagem = "Session journal integrity check failed: ${erro.message}"
            sessoes.persistir(
                id,
                Remendo(status = Campo.Presente(Estados.RETOMADA_INVALIDA), erro = Campo.Presente(mensagem)),
                seSituacaoEm = Estados.ATIVOS,
            )
            return Preparacao.JornalInvalido(mensagem)
        }
        val ehRetomada = TrimJs.aparar(linha.textoAtual).isNotEmpty() && linha.autorAtual != null
        if (!ehRetomada) {
            return Preparacao.Nova(linha, escala, lider, eventos, FormatoDeInstante.ler(linha.criadaEm) ?: relogio())
        }
        val textoAtual = linha.textoAtual
        val autorAtual = Agentes.sanear(linha.autorAtual, lider)
        val progresso = try {
            banco.runInTransaction<ProgressoDaRetomada> {
                val restaurado = restaurar(linha, escala, autorAtual, textoAtual)
                persistirProgressoDentroDaTransacao(
                    id,
                    ProgressoCircular(
                        autorAtual = autorAtual,
                        textoAtual = textoAtual,
                        artefatoDeCustodiaId = restaurado.artefatoDeCustodiaId,
                        artefatoAnteriorId = restaurado.artefatoAnteriorId,
                        rodada = restaurado.rodada,
                        indiceDoTurno = restaurado.indiceDoTurno,
                        escala = escala,
                        agentesValidos = restaurado.agentesValidos,
                        aprovacoesEstaveis = restaurado.aprovacoesEstaveis,
                        turnoDoArtefato = restaurado.turnoDoArtefato,
                    ),
                    Remendo(status = Campo.Presente(Estados.RODANDO)),
                    seSituacaoEm = Estados.ATIVOS,
                    evento = null,
                )
                restaurado
            }
        } catch (erro: IntegridadeDeLinks.Falha) {
            val mensagem = "Circular custody integrity check failed: ${erro.message}"
            val agora = FormatoDeInstante.iso(relogio())
            sessoes.persistir(
                id,
                Remendo(),
                seSituacaoEm = Estados.ATIVOS,
                evento = EventoDaSessao(em = agora, agente = autorAtual, papel = "draft", status = EventoDaSessao.BLOQUEADO, mensagem = mensagem),
            )
            sessoes.persistir(
                id,
                Remendo(status = Campo.Presente(Estados.RETOMADA_INVALIDA), erro = Campo.Presente(mensagem)),
                seSituacaoEm = Estados.ATIVOS,
            )
            return Preparacao.CustodiaInvalida(mensagem)
        } catch (perdido: CasPerdido) {
            return Preparacao.Perdida
        }
        val eventoDeRetomada = EventoDaSessao(
            em = FormatoDeInstante.iso(relogio()),
            agente = autorAtual,
            papel = "draft",
            status = EventoDaSessao.RODANDO,
            mensagem = MENSAGEM_RETOMADA,
        )
        if (!sessoes.persistir(id, Remendo(), seSituacaoEm = Estados.ATIVOS, evento = eventoDeRetomada)) return Preparacao.Perdida
        val atual = sessoes.carregar(id) ?: return Preparacao.SessaoAusente
        return Preparacao.Retomar(atual, escala, lider, eventos + eventoDeRetomada, autorAtual, textoAtual, progresso, relogio())
    }

    /** `persistCircularProgress` (`sessions.ts:3406-3437`): custódia serializada mais o [remendo], sob o portão. */
    public fun persistirProgressoCircular(
        id: String,
        progresso: ProgressoCircular,
        remendo: Remendo = Remendo(),
        seSituacaoEm: Set<String>? = null,
        evento: EventoDaSessao? = null,
    ): Boolean = try {
        banco.runInTransaction<Boolean> { persistirProgressoDentroDaTransacao(id, progresso, remendo, seSituacaoEm, evento) }
    } catch (perdido: CasPerdido) {
        false
    }

    internal fun persistirProgressoDentroDaTransacao(
        id: String,
        progresso: ProgressoCircular,
        remendo: Remendo,
        seSituacaoEm: Set<String>?,
        evento: EventoDaSessao?,
    ): Boolean {
        val custodia = progresso.artefatoDeCustodiaId
        val anterior = progresso.artefatoAnteriorId
        if (custodia.isNullOrEmpty() || anterior.isNullOrEmpty() || TrimJs.aparar(progresso.textoAtual).isEmpty()) {
            throw IllegalStateException("Cannot persist circular progress without accepted draft custody.")
        }
        val base = Remendo(
            autorAtual = Campo.Presente(progresso.autorAtual.agente),
            textoAtual = Campo.Presente(progresso.textoAtual),
            estadoCircularJson = Campo.Presente(EstadoCircular.serializar(id, progresso, relogio())),
        )
        return sessoes.persistirDentroDaTransacao(id, remendo.sobre(base), seSituacaoEm, evento)
    }

    /** A restauração (`sessions.ts:3504-3524`): estado persistido validado, ou o legado reconstruído. */
    private fun restaurar(linha: SessaoEntidade, escala: List<Provedor>, autorAtual: Provedor, textoAtual: String): ProgressoDaRetomada {
        val persistido = EstadoCircular.ler(linha.estadoCircularJson.ifEmpty { "{}" })
        if (persistido == null) {
            return EstadoCircular.reconstruirLegado(linha, autorAtual, textoAtual, artefatos.daSessao(linha.id), artefatos::criar)
        }
        val estado = EstadoCircular.validar(linha, persistido) { artefatos.um(linha.id, it) }
        val lista = artefatos.daSessao(linha.id)
        val restaurado = EstadoCircular.restaurar(estado, escala, autorAtual, lista.mapNotNull(EstadoCircular::relatorioDeliberativo))
        // Um artefato órfão além do contador (CAS perdido) reserva o número do turno sem virar custódia.
        return restaurado.copy(turnoDoArtefato = maxOf(restaurado.turnoDoArtefato, lista.maxOfOrNull { it.turno } ?: 0))
    }

    public companion object {
        public const val MENSAGEM_MUDOU_DE_ESTADO: String = "Sessao mudou de estado durante a retomada; tente novamente."
        public const val MENSAGEM_RETOMADA: String = "Session resumed: draft phase skipped and circular custody state restored."
    }
}

/** O que [PontoDeRetomada.gravarTurno] devolve. */
public sealed interface Gravacao {
    /** Tudo gravado; [artefato] é a linha inserida, quando havia artefato. */
    public data class Gravada(val artefato: ArtefatoEntidade?) : Gravacao

    /** O portão de status falhou: nada foi gravado, nem o artefato, nem o evento. */
    public data object Perdida : Gravacao
}

/**
 * O checkpoint por turno (especificação, seção 4.2; emenda A3): o artefato do
 * turno, a custódia, o jornal e o status numa transação só. O web grava em
 * dois comandos (`sessions.ts:4192-4203`); aqui um CAS perdido desfaz também
 * o artefato, em vez de deixá-lo órfão.
 */
public class PontoDeRetomada(
    private val banco: BancoDaSessao,
    private val artefatos: RepositorioDeArtefatos,
    private val retomada: Retomada,
) {
    /**
     * [progresso] recebe o artefato recém-inserido (ou `null`) e devolve a
     * custódia a gravar, porque o id só existe depois do insert.
     */
    public fun gravarTurno(
        sessaoId: String,
        artefato: EntradaDeArtefato?,
        progresso: (ArtefatoEntidade?) -> ProgressoCircular,
        remendo: Remendo = Remendo(),
        evento: EventoDaSessao? = null,
        seSituacaoEm: Set<String> = Estados.ATIVOS,
    ): Gravacao = try {
        banco.runInTransaction<Gravacao> {
            val inserido = artefato?.let(artefatos::criar)
            retomada.persistirProgressoDentroDaTransacao(sessaoId, progresso(inserido), remendo, seSituacaoEm, evento)
            Gravacao.Gravada(inserido)
        }
    } catch (perdido: CasPerdido) {
        Gravacao.Perdida
    }
}
