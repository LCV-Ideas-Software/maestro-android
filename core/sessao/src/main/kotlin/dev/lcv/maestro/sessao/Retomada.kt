package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.time.Instant

/** O que [Retomada.preparar] devolve ao worker antes do primeiro turno. */
public sealed interface Preparacao {
    /** A linha não existe mais. */
    public data object SessaoAusente : Preparacao

    /** A custódia não validou; evento bloqueado gravado e sessão em `paused_resume_state_invalid`. */
    public data class CustodiaInvalida(val mensagem: String) : Preparacao

    /** A sessão já não estava em `queued`/`running` quando a execução tentou reivindicá-la: o runner para. */
    public data object Perdida : Preparacao

    /** Uma execução nova, reivindicada: o líder redige; a âncora do tempo é `criadaEm`. */
    public data class Nova(
        val sessao: SessaoEntidade,
        val escala: List<Provedor>,
        val lider: Provedor,
        val eventos: List<EventoDaSessao>,
        /** A cerca de execução: vai em toda escrita do worker. */
        val execucao: Long,
        val ancora: Instant,
    ) : Preparacao

    /** Uma retomada reivindicada: rascunho pulado, custódia validada e restaurada; a âncora é o agora. */
    public data class Retomar(
        val sessao: SessaoEntidade,
        val escala: List<Provedor>,
        val lider: Provedor,
        val eventos: List<EventoDaSessao>,
        val autorAtual: Provedor,
        val textoAtual: String,
        val progresso: ProgressoDaRetomada,
        val execucao: Long,
        val ancora: Instant,
    ) : Preparacao
}

/**
 * `handleMaestroAiSessionResumePost` e a fatia de preparação do `runSession`
 * (`sessions.ts:4733-4815, 3320-3355, 3500-3563`). [pedir] deixa a sessão
 * `queued` e devolve a linha; quem enfileira o worker é a 3b. [preparar] é o
 * que o worker chama primeiro: **reivindica** a sessão numa transação (uma
 * linha de `execucoes` e `execucaoAtual`, sob o portão de status) e só então
 * valida e restaura a custódia; um cancelamento ou uma reconciliação que já
 * tirou a sessão de `queued`/`running` faz a reivindicação falhar.
 */
public class Retomada(
    private val banco: BancoDaSessao,
    private val sessoes: RepositorioDeSessoes,
    private val artefatos: RepositorioDeArtefatos,
    private val relogio: () -> Instant,
) {
    private fun agora(): String = FormatoDeInstante.iso(relogio())

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
        val evento = EventoDaSessao(
            em = agora(),
            status = EventoDaSessao.RODANDO,
            mensagem = "Sessao retomada pelo operador com ${lider.rotulo} como lider do ciclo.",
        )
        val aplicado = try {
            banco.runInTransaction<Boolean> {
                if (banco.sessoes().retomar(id, linha.status, lider.agente, RepositorioDeSessoes.agentesJson(painel), agora()) == 0) throw CasPerdido()
                banco.eventos().inserir(evento.paraEntidade(id))
                true
            }
        } catch (perdido: CasPerdido) {
            false
        }
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

    /**
     * A preparação do `runSession`: reivindica a sessão e, numa retomada,
     * valida e restaura a custódia, tudo numa transação. Uma custódia
     * inválida desfaz a reivindicação e pausa a sessão em
     * `paused_resume_state_invalid`, com o evento bloqueado do web.
     */
    public fun preparar(id: String): Preparacao {
        if (sessoes.carregar(id) == null) return Preparacao.SessaoAusente
        // Tudo o que decide o caminho — escala, líder, autor, `isResume` — vem da
        // linha lida DENTRO da transação, depois de reivindicada: uma leitura de
        // fora poderia ser de antes do checkpoint da execução anterior (achado do
        // Codex na #67). Os dois valores abaixo existem só para o bloco `catch`.
        var autorAtual: Provedor = Provedor.CLAUDE
        return try {
            banco.runInTransaction<Preparacao> {
                // O portão está na própria reivindicação (`WHERE status IN (queued, running)`):
                // zero linhas é uma sessão cancelada ou reconciliada, e a transação desfaz a
                // linha de `execucoes` que acabou de nascer. A execução que estava na linha
                // (a que morreu) é encerrada aqui, para não ficar aberta no orçamento.
                val anterior = sessoes.carregar(id) ?: throw CasPerdido()
                anterior.execucaoAtual?.let { banco.execucoes().encerrar(it, agora(), MOTIVO_SUPERADA) }
                val execucao = banco.execucoes().inserir(ExecucaoEntidade(sessaoId = id, inicio = agora()))
                if (banco.sessoes().reivindicar(id, execucao, agora()) == 0) throw CasPerdido()
                val viva = sessoes.carregar(id)!!
                val (lider, escala) = escala(viva)
                // `isResume` (`sessions.ts:3383`) pede texto aceito E autor; aqui basta o
                // autor: uma sessão nova guarda o conteúdo inicial com autor nulo, e um
                // autor gravado com texto vazio é custódia inválida, que `validar` recusa —
                // não uma sessão nova a redigir de novo por dinheiro (achado do Codex na #67).
                val ehRetomada = viva.autorAtual != null
                autorAtual = Agentes.sanear(viva.autorAtual, lider)
                val eventos = sessoes.eventos(id)
                if (!ehRetomada) {
                    return@runInTransaction Preparacao.Nova(sessoes.carregar(id)!!, escala, lider, eventos, execucao, FormatoDeInstante.ler(viva.criadaEm) ?: relogio())
                }
                val custodia = EstadoCircular.validar(viva) { artefatos.um(id, it) }
                val lista = artefatos.daSessao(id)
                val restaurado = EstadoCircular.restaurar(custodia, escala, autorAtual, lista.mapNotNull(EstadoCircular::relatorioDeliberativo))
                    // Um artefato além do contador reserva o número do turno sem virar custódia (`sessions.ts:3515-3521`).
                    .let { it.copy(turnoDoArtefato = maxOf(it.turnoDoArtefato, artefatos.turnoMaximo(id))) }
                val gravadas = banco.sessoes().gravarCustodia(
                    id = id, permitidos = Estados.ATIVOS.toList(), execucao = execucao,
                    autorAtual = autorAtual.agente, textoAtual = custodia.textoAtual,
                    custodiaArtefatoId = restaurado.artefatoDeCustodiaId, artefatoAnteriorId = restaurado.artefatoAnteriorId,
                    rodada = restaurado.rodada, indiceDoTurno = restaurado.indiceDoTurno, turnoDoArtefato = restaurado.turnoDoArtefato,
                    escalaJson = EstadoCircular.agentesJson(escala), agentesValidosJson = EstadoCircular.agentesJson(restaurado.agentesValidos),
                    aprovacoesEstaveisJson = EstadoCircular.agentesJson(restaurado.aprovacoesEstaveis),
                    status = Estados.RODANDO, erro = null, em = agora(),
                )
                if (gravadas == 0) throw CasPerdido()
                val eventoDeRetomada = EventoDaSessao(em = agora(), agente = autorAtual, papel = "draft", status = EventoDaSessao.RODANDO, mensagem = MENSAGEM_RETOMADA)
                banco.eventos().inserir(eventoDeRetomada.paraEntidade(id))
                Preparacao.Retomar(sessoes.carregar(id)!!, escala, lider, eventos + eventoDeRetomada, autorAtual, custodia.textoAtual, restaurado, execucao, relogio())
            }
        } catch (erro: IntegridadeDeLinks.Falha) {
            // A pausa e o evento bloqueado vão numa transação só: um processo morto
            // entre os dois deixaria a sessão ativa com o jornal já dizendo que travou.
            val mensagem = "Circular custody integrity check failed: ${erro.message}"
            val bloqueado = EventoDaSessao(em = agora(), agente = autorAtual, papel = "draft", status = EventoDaSessao.BLOQUEADO, mensagem = mensagem)
            // A reivindicação foi desfeita com a transação, e com ela o fecho da execução
            // que estava na linha: ele é refeito aqui, junto da pausa, para a execução
            // morta não ficar aberta no orçamento (achado do Codex na #67).
            try {
                banco.runInTransaction {
                    if (banco.sessoes().mudarStatus(id, Estados.ATIVOS.toList(), Estados.RETOMADA_INVALIDA, mensagem, agora(), null) == 0) throw CasPerdido()
                    banco.eventos().inserir(bloqueado.paraEntidade(id))
                    banco.encerrarExecucaoDa(id, agora(), MOTIVO_SUPERADA)
                }
                Preparacao.CustodiaInvalida(mensagem)
            } catch (perdido: CasPerdido) {
                // Outro já tirou a sessão de queued/running: nada foi pausado nem anotado, e o
                // resultado diz isso — `CustodiaInvalida` promete o evento gravado.
                Preparacao.Perdida
            }
        } catch (perdido: CasPerdido) {
            Preparacao.Perdida
        }
    }

    public companion object {
        public const val MENSAGEM_MUDOU_DE_ESTADO: String = "Sessao mudou de estado durante a retomada; tente novamente."
        public const val MENSAGEM_RETOMADA: String = "Session resumed: draft phase skipped and circular custody state restored."
        /** O rótulo da execução que outra tomou o lugar (a anterior morreu sem fechar a sua linha). */
        public const val MOTIVO_SUPERADA: String = "superseded"
    }
}

/** O que [PontoDeRetomada.gravarTurno] devolve. */
public sealed interface Gravacao {
    /** Tudo gravado; [artefato] é a linha inserida, quando havia artefato. */
    public data class Gravada(val artefato: ArtefatoEntidade?) : Gravacao

    /** O portão de status ou a cerca de execução falhou: nada foi gravado, nem o artefato, nem o evento. */
    public data object Perdida : Gravacao
}

/**
 * O checkpoint por turno (especificação, seção 4.2): o artefato do turno, a
 * custódia inteira, o status resultante e o evento numa transação só, sob o
 * portão de status e a cerca de execução. Um portão perdido desfaz também o
 * artefato; uma escrita tardia de uma execução superada falha na cerca. O
 * artefato é da sessão do checkpoint, e um status que sai de
 * `queued`/`running` fecha a execução junto.
 */
public class PontoDeRetomada(
    private val banco: BancoDaSessao,
    private val artefatos: RepositorioDeArtefatos,
    private val relogio: () -> Instant,
) {
    /**
     * [custodia] recebe o artefato recém-inserido (ou `null`) e devolve a
     * custódia a gravar, porque o id só existe depois do insert; [status] e
     * [erro] são o estado em que a sessão fica (`running` para seguir, ou uma
     * pausa).
     */
    public fun gravarTurno(
        sessaoId: String,
        execucao: Long,
        artefato: EntradaDeArtefato?,
        custodia: (ArtefatoEntidade?) -> Custodia,
        status: String = Estados.RODANDO,
        erro: String? = null,
        evento: EventoDaSessao? = null,
        seSituacaoEm: Set<String> = Estados.ATIVOS,
    ): Gravacao = try {
        require(artefato == null || artefato.sessaoId == sessaoId) { "checkpoint artifact belongs to another session" }
        banco.runInTransaction<Gravacao> {
            val inserido = artefato?.let(artefatos::inserir)
            val c = custodia(inserido)
            val textoAtual = EstadoCircular.textoCanonico(c.textoAtual)
            MarkdownDoArtefato.conferirTexto(textoAtual)
            val gravadas = banco.sessoes().gravarCustodia(
                id = sessaoId, permitidos = seSituacaoEm.toList(), execucao = execucao,
                autorAtual = c.autorAtual.agente, textoAtual = textoAtual,
                custodiaArtefatoId = c.custodiaArtefatoId, artefatoAnteriorId = c.artefatoAnteriorId,
                rodada = maxOf(1, c.rodada), indiceDoTurno = maxOf(0, c.indiceDoTurno), turnoDoArtefato = maxOf(1, c.turnoDoArtefato),
                escalaJson = EstadoCircular.agentesJson(c.escala), agentesValidosJson = EstadoCircular.agentesJson(c.agentesValidos),
                aprovacoesEstaveisJson = EstadoCircular.agentesJson(c.aprovacoesEstaveis),
                status = status, erro = erro, em = FormatoDeInstante.iso(relogio()),
            )
            if (gravadas == 0) throw CasPerdido()
            if (evento != null) banco.eventos().inserir(evento.paraEntidade(sessaoId))
            if (status !in Estados.ATIVOS) banco.execucoes().encerrar(execucao, FormatoDeInstante.iso(relogio()), status)
            Gravacao.Gravada(inserido)
        }
    } catch (perdido: CasPerdido) {
        Gravacao.Perdida
    }
}
