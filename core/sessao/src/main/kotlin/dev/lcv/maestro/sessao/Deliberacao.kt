package dev.lcv.maestro.sessao

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.Escalonamento
import dev.lcv.maestro.protocolo.EspacoUnicode
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.GuardaDeQualidade
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.PromptsDaSessao
import dev.lcv.maestro.protocolo.TravaDeConteudo
import dev.lcv.maestro.protocolo.TurnoSerial
import dev.lcv.maestro.protocolo.ValorJson
import dev.lcv.maestro.protocolo.bonito
import dev.lcv.maestro.provedores.MAX_TOKENS_DE_SAIDA
import dev.lcv.maestro.provedores.Pedido
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado as RespostaDoProvedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.math.BigDecimal
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Quem faz a chamada paga: em produção, `ClienteDeProvedores::chamar`; em teste, respostas combinadas. */
public fun interface Chamador {
    public suspend fun chamar(provedor: Provedor, pedido: Pedido, tempoRestante: Duration?): RespostaDoProvedor
}

/**
 * A auditoria do candidato final com o motor de links da sessão: em produção,
 * um [dev.lcv.maestro.provedores.ColetorHttp] novo por auditoria, cancelado
 * junto com a corrotina (especificação, seção 5.4); em teste, um resultado
 * determinístico. É a costura que separa a orquestração da rede.
 */
public fun interface AuditoriaDaSessao {
    public suspend fun falha(sessaoId: String, texto: String, citacoes: AuditoriaFinal.ContextoDeCitacoes): AuditoriaFinal.Falha?
}

/** O que a execução vê a cada checkpoint, para a notificação espelhar (seção 4.1). */
public data class Progresso(val rodada: Int, val agente: Provedor?, val custoObservadoUsd: BigDecimal)

/** Como uma execução de [Deliberacao.executar] terminou. Nada aqui é uma nova tentativa do WorkManager. */
public sealed interface Desfecho {
    /** A linha não existe. */
    public data object SessaoAusente : Desfecho

    /** Outra execução, um cancelamento ou a reconciliação tirou a sessão desta execução; nada foi escrito por ela depois disso. */
    public data object Interrompida : Desfecho

    /** [Preparacao.CustodiaInvalida]: pausada em `paused_resume_state_invalid` antes do primeiro turno. */
    public data class CustodiaInvalida(val mensagem: String) : Desfecho

    /** [Preparacao.ChamadaIndeterminada]: a execução anterior morreu com uma chamada paga em voo; só o operador retoma. */
    public data class ChamadaIndeterminada(val provedor: Provedor) : Desfecho

    /** A sessão parou num status retomável ([status]), gravado com o seu evento. */
    public data class Pausada(val status: String) : Desfecho

    /** `converged`, com o texto final gravado. */
    public data object Convergida : Desfecho
}

/**
 * O `runSession` do web (`sessions.ts:3320-4300`) sobre as transações da 3a
 * — reivindicação, checkpoint por turno com a cerca de execução, transições
 * tipadas —, com os acréscimos do desktop decididos em 24/09/2026: a
 * auditoria de cinco estágios com o contexto de citações, a pausa por
 * evidência do operador no topo de cada iteração e o bloco do manifesto nos
 * prompts. Tudo o que o web guarda em variáveis locais fica em locais de uma
 * execução; tudo o que é durável passa pelas transações com a cerca.
 *
 * Os nove pontos de cancelamento cooperativo do web são [parado]: a linha
 * relida diz `running` e ainda aponta para esta execução, e o worker não
 * pediu para parar. Uma chamada paga só sai depois de gravado o marcador da
 * execução (decisão 16 do operador, 27/09/2026), e um `CancellationException`
 * do cliente sobe sem gravar resultado.
 */
public class Deliberacao(
    private val sessoes: RepositorioDeSessoes,
    private val retomada: Retomada,
    private val ponto: PontoDeRetomada,
    private val artefatos: RepositorioDeArtefatos,
    private val anexos: AnexosDaSessao,
    private val chamador: Chamador,
    private val auditoria: AuditoriaDaSessao,
    private val relogio: () -> Instant,
    /** A semente do redesenho: justiça de escalonamento, não criptografia (o web usa `Math.random`). */
    private val semente: () -> Long = { Random.nextLong(0, 1L shl 32) },
    /** `isStopped` do worker. */
    private val parar: () -> Boolean = { false },
    private val aoAvancar: suspend (Progresso) -> Unit = {},
) {
    /** A execução reivindicada por [executar], para o worker rotular uma parada do sistema sob a cerca. */
    @Volatile
    public var execucaoEmCurso: Long? = null
        private set

    public suspend fun executar(id: String): Desfecho {
        val preparacao = retomada.preparar(id)
        val execucao = when (preparacao) {
            Preparacao.SessaoAusente -> return Desfecho.SessaoAusente
            Preparacao.Perdida -> return Desfecho.Interrompida
            is Preparacao.CustodiaInvalida -> return Desfecho.CustodiaInvalida(preparacao.mensagem)
            is Preparacao.ChamadaIndeterminada -> return Desfecho.ChamadaIndeterminada(preparacao.provedor)
            is Preparacao.Nova -> Execucao(id, preparacao.sessao, preparacao.escala, preparacao.lider, preparacao.execucao, preparacao.ancora)
            is Preparacao.Retomar -> Execucao(id, preparacao.sessao, preparacao.escala, preparacao.lider, preparacao.execucao, preparacao.ancora).also {
                it.restaurar(preparacao)
            }
        }
        execucaoEmCurso = execucao.execucao
        return try {
            execucao.rodar(preparacao is Preparacao.Nova)
        } catch (cancelada: CancellationException) {
            throw cancelada
        } catch (erro: Exception) {
            // O `catch` do web (`sessions.ts:4274-4298`): qualquer erro vira `error`, com a mensagem.
            val mensagem = erro.message?.takeIf { it.isNotEmpty() } ?: MENSAGEM_ERRO_DESCONHECIDO
            val evento = EventoDaSessao(em = agora(), status = EventoDaSessao.ERRO, mensagem = mensagem)
            if (sessoes.transicionar(id, Estados.ERRO, mensagem, evento, execucao = execucao.execucao)) {
                Desfecho.Pausada(Estados.ERRO)
            } else {
                Desfecho.Interrompida
            }
        }
    }

    private fun agora(): String = FormatoDeInstante.iso(relogio())

    /** Os locais de uma execução do `runSession`. */
    private inner class Execucao(
        val id: String,
        sessao: SessaoEntidade,
        val escala: List<Provedor>,
        val lider: Provedor,
        val execucao: Long,
        val ancora: Instant,
    ) {
        val titulo = sessao.titulo
        val tetoDeMinutos = sessao.tetoDeMinutos
        val teto: BigDecimal = Dinheiro.deE8(sessao.tetoDeCustoE8)
        val taxas: Map<Provedor, Custo.Taxas> = Taxas.lerJson(sessao.taxasJson)
        val ativos: List<Provedor> = RepositorioDeSessoes.lerAgentes(sessao.agentesAtivosJson).ifEmpty { Provedor.entries }
        val pedido = PromptsDaSessao.PedidoDaSessao(sessao.titulo, sessao.pedido, sessao.textoAtual, sessao.protocolo)
        val protocolo = sessao.protocolo

        // `observedCost`: o acumulado da sessão inteira — o teto vale sobre ele (emenda A13,
        // 25/09/2026). É o total gravado, relido antes de cada guarda e devolvido por cada
        // soma: outra execução que se cruzou com esta (cancelar e retomar com a chamada antiga
        // ainda em voo) também conta (achado do Codex na #70).
        var observado: BigDecimal = Dinheiro.deE8(sessao.custoObservadoE8)
        var autorAtual: Provedor = lider
        var textoAtual = ""
        var turnoDoArtefato = 0
        var artefatoAnteriorId: String? = null
        var custodiaArtefatoId: String? = null
        /**
         * `correctiveRetryCounts`, por `rodada:revisor:texto` (o índice do turno
         * é função do revisor dentro da rodada e sai da chave). Na retomada é
         * semeado dos artefatos bloqueados desta rodada sobre o texto atual
         * (achado do Codex na #70): um worker parado no meio das tentativas
         * não ganha três tentativas novas ao voltar.
         */
        val contadoresDeRetentativa = HashMap<String, Int>()
        val relatorios = ArrayList<PromptsDaSessao.RelatorioDeTurno>()
        var rodada = 1
        var indiceDoTurno = 0
        var turnosSeriais = 0
        val validos = LinkedHashSet<Provedor>()
        val estaveis = LinkedHashSet<Provedor>()
        var panesConsecutivas = 0
        val turnosPorRodada = escala.size
        val maxTurnosSeriais = maxOf(turnosPorRodada * 4, turnosPorRodada)

        // Plano D do web: dentro de UMA execução a auditoria do texto de custódia é
        // memorizada por texto; a finalização a refaz do zero.
        val cacheDaAuditoria = HashMap<String, AuditoriaFinal.Falha?>()
        lateinit var citacoes: AuditoriaFinal.ContextoDeCitacoes
        var blocoDeEvidencias = ""

        fun restaurar(preparacao: Preparacao.Retomar) {
            val progresso = preparacao.progresso
            textoAtual = preparacao.textoAtual
            autorAtual = preparacao.autorAtual
            rodada = progresso.rodada
            indiceDoTurno = progresso.indiceDoTurno
            validos += progresso.agentesValidos
            estaveis += progresso.aprovacoesEstaveis
            custodiaArtefatoId = progresso.artefatoDeCustodiaId
            turnoDoArtefato = progresso.turnoDoArtefato
            artefatoAnteriorId = progresso.artefatoAnteriorId
            relatorios += progresso.relatorios
            for (bloqueado in artefatos.daSessao(id)) {
                if (bloqueado.papel != "revision" || bloqueado.status != "blocked" || bloqueado.ciclo != rodada || bloqueado.textoAceito != textoAtual) continue
                val tentativa = Json.tolerante(bloqueado.relatorioDeRevisaoJson)?.get("attempt")?.takeIf { it.canConvertToInt() }?.intValue() ?: continue
                val chave = chaveDeRetentativa(Agentes.porChave(bloqueado.agente) ?: continue)
                contadoresDeRetentativa[chave] = maxOf(contadoresDeRetentativa[chave] ?: 0, tentativa)
            }
        }

        fun chaveDeRetentativa(revisor: Provedor): String = "$rodada:${revisor.agente}:$textoAtual"

        // ── o que o web relê e escreve a cada passo ──────────────────────────

        /**
         * `runnerStopRequested` + a posse desta execução + `isStopped` do worker.
         * Depois de uma chamada paga a releitura é só do banco ([soBanco]): um
         * worker parado pelo sistema ainda grava o resultado que já pagou; o que
         * não se grava é o resultado de uma sessão que já não está `running` ou
         * já não é desta execução.
         */
        fun parado(soBanco: Boolean = false): Boolean {
            if (!soBanco && parar()) return true
            val viva = sessoes.carregar(id) ?: return true
            return viva.status != Estados.RODANDO || viva.execucaoAtual != execucao
        }

        fun evento(
            status: String,
            mensagem: String,
            agente: Provedor? = null,
            papel: String? = null,
            custo: Custo.Observado? = null,
            custoUsd: BigDecimal? = custo?.valor,
            modelo: String? = null,
            auditoriaFinal: AuditoriaFinal.Falha? = null,
        ) = EventoDaSessao(
            em = agora(),
            status = status,
            mensagem = mensagem,
            agente = agente,
            papel = papel,
            custoUsd = custoUsd,
            fonteDoCusto = custo?.fonte,
            modelo = modelo,
            auditoriaDeLinks = auditoriaFinal?.let(::linhasDaFalha),
            auditoriaFinal = auditoriaFinal?.let(::campoDaAuditoria),
        )

        fun anotar(evento: EventoDaSessao): Boolean = sessoes.anotar(id, evento, execucao = execucao)

        /** Uma pausa antes de haver custódia nova a gravar (`persistSession({status})`). */
        fun pausar(status: String, erro: String?, evento: EventoDaSessao?): Desfecho =
            if (sessoes.transicionar(id, status, erro, evento, execucao = execucao)) Desfecho.Pausada(status) else Desfecho.Interrompida

        fun custodia(artefato: ArtefatoEntidade?) = Custodia(
            autorAtual = autorAtual,
            textoAtual = textoAtual,
            custodiaArtefatoId = custodiaArtefatoId ?: artefato?.id ?: error("no custody artifact"),
            artefatoAnteriorId = artefatoAnteriorId ?: artefato?.id ?: error("no previous artifact"),
            rodada = rodada,
            indiceDoTurno = indiceDoTurno,
            escala = escala,
            agentesValidos = validos.toSet(),
            aprovacoesEstaveis = estaveis.toSet(),
            turnoDoArtefato = turnoDoArtefato,
        )

        /** `persistCircularProgress` sem artefato: a custódia dos locais, com um status e um evento opcionais. */
        suspend fun gravarCustodia(status: String = Estados.RODANDO, erro: String? = null, evento: EventoDaSessao? = null): Boolean {
            val gravado = ponto.gravarTurno(id, execucao, null, ::custodia, status, erro, evento) is Gravacao.Gravada
            if (gravado) aoAvancar(Progresso(rodada, autorAtual, observado))
            return gravado
        }

        /** Uma pausa depois de haver custódia: custódia e status vão na mesma transação. */
        suspend fun pausarComCustodia(status: String, erro: String?, evento: EventoDaSessao? = null): Desfecho =
            if (gravarCustodia(status, erro, evento)) Desfecho.Pausada(status) else Desfecho.Interrompida

        fun tempoEsgotado(): Boolean = OrcamentoDeTempo.esgotado(ancora, tetoDeMinutos, relogio())

        fun tempoRestante(): Duration? = OrcamentoDeTempo.restanteMs(ancora, tetoDeMinutos, relogio())?.milliseconds

        fun taxasDe(provedor: Provedor): Custo.Taxas = taxas[provedor] ?: Custo.Taxas(null, null)

        /** `estimateCost(prompt, MAX_OUTPUT_TOKENS, rates)` com o teto de saída que a chamada realmente pede. */
        fun estimativa(provedor: Provedor, prompt: String): BigDecimal? =
            Custo.estimar(prompt, MAX_TOKENS_DE_SAIDA.toLong(), taxasDe(provedor))

        fun sistema(provedor: Provedor): String =
            "You are ${provedor.rotulo} inside Maestro Editorial AI. Internal coordination must be in en_US. " +
                "Operator-facing deliverables must be in pt_BR. Follow the current Maestro role contract exactly."

        /**
         * A chamada paga: o marcador da execução vai antes do envio (decisão
         * 16); zero linhas é "já não sou a dona" e nada sai. `null` é parar.
         */
        suspend fun pagar(provedor: Provedor, prompt: String): RespostaDoProvedor? {
            if (parado()) return null
            if (!sessoes.marcarChamadaEmVoo(execucao, provedor)) return null
            return chamador.chamar(provedor, Pedido(sistema(provedor), prompt), tempoRestante())
        }

        /**
         * `calculateObservedCost` + a soma atômica, logo depois de cada chamada
         * paga; [observado] passa a ser o total gravado. Uma resposta
         * [incompleta] sem contagem de saída é cobrada como se tivesse gerado o
         * teto de saída inteiro: ela pode ser uma geração parada nos 64 mil
         * tokens, e cobrar zero deixaria passar chamadas além do teto (achado
         * do Codex na #70).
         */
        fun cobrar(provedor: Provedor, prompt: String, texto: String, uso: dev.lcv.maestro.provedores.Uso, incompleta: Boolean = false): Custo.Observado {
            val saida = uso.tokensDeSaida ?: if (incompleta) MAX_TOKENS_DE_SAIDA.toLong() else null
            val custo = Custo.observar(taxasDe(provedor), prompt, texto, uso.tokensDeEntrada, saida, uso.custoInformadoUsd)
                ?: Custo.Observado(BigDecimal.ZERO, Custo.Fonte.ESTIMATIVA)
            observado = sessoes.somarCusto(id, custo.valor)
            return custo
        }

        /** `estimateCost` + a regra do teto sobre o total gravado agora, não sobre um valor local que outra execução pode ter deixado velho. */
        fun admitido(provedor: Provedor, prompt: String): Pair<BigDecimal?, Boolean> {
            val projetado = estimativa(provedor, prompt)
            observado = sessoes.custoObservado(id)
            return projetado to (Custo.admitir(observado, projetado, teto) is Custo.Admissao.Permitida)
        }

        fun corta(texto: String, maximo: Int): String = Texto.sanear(texto, maximo)

        suspend fun auditoriaMemorizada(texto: String): AuditoriaFinal.Falha? {
            if (cacheDaAuditoria.containsKey(texto)) return cacheDaAuditoria[texto]
            val falha = auditoria.falha(id, texto, citacoes)
            cacheDaAuditoria[texto] = falha
            return falha
        }

        // ── a execução ──────────────────────────────────────────────────────

        suspend fun rodar(nova: Boolean): Desfecho {
            // As citações da sessão, antes de qualquer chamada paga (`session_orchestration.rs:343`).
            when (val lidas = CitacoesDaSessao.de(anexos.listar(id), protocolo)) {
                is Citacoes.Recusadas -> return pausar(
                    Estados.AUDITORIA_FINAL,
                    lidas.motivo,
                    evento(EventoDaSessao.BLOQUEADO, "Final release audit failed (citation_manifest): ${corta(lidas.motivo, 300)}"),
                )
                is Citacoes.Lidas -> {
                    citacoes = lidas.contexto
                    blocoDeEvidencias = lidas.bloco
                }
            }
            if (parado()) return Desfecho.Interrompida
            if (nova) redigir()?.let { return it }
            laco()?.let { return it }
            return finalizar()
        }

        /** A fase de rascunho (`sessions.ts:3564-3743`): o líder redige; falha operacional ou texto vazio passa ao próximo. */
        suspend fun redigir(): Desfecho? {
            val prompt = PromptsDaSessao.rascunho(pedido, id, blocoDeEvidencias)
            val candidatos = listOf(lider) + ativos.filter { it != lider }
            var rascunho: String? = null
            var autor: Provedor? = null
            var custoDoRascunho: Custo.Observado? = null
            for (agente in candidatos) {
                if (tempoEsgotado()) {
                    anotar(evento(EventoDaSessao.BLOQUEADO, "Time guard blocked draft call before ${agente.rotulo}.", agente, "draft"))
                    return pausar(Estados.LIMITE_DE_TEMPO, null, null)
                }
                val (projetado, cabe) = admitido(agente, prompt)
                if (!cabe) {
                    anotar(evento(EventoDaSessao.BLOQUEADO, "Cost guard blocked draft call before ${agente.rotulo}.", agente, "draft", custoUsd = projetado))
                    return pausar(Estados.LIMITE_DE_CUSTO, null, null)
                }
                anotar(evento(EventoDaSessao.RODANDO, if (agente == lider) "Draft call started." else "Draft fallback call started.", agente, "draft"))
                val resposta = pagar(agente, prompt) ?: return Desfecho.Interrompida
                var custo: Custo.Observado? = null
                val falha: String = when (resposta) {
                    is RespostaDoProvedor.Concluida -> {
                        custo = cobrar(agente, prompt, resposta.texto, resposta.uso)
                        if (resposta.texto.isBlank()) {
                            "O provedor ${agente.rotulo} retornou um rascunho vazio (texto em branco)."
                        } else {
                            rascunho = resposta.texto
                            autor = agente
                            custoDoRascunho = custo
                            break
                        }
                    }
                    is RespostaDoProvedor.Incompleta -> {
                        custo = cobrar(agente, prompt, "", resposta.uso, incompleta = true)
                        resposta.motivo
                    }
                    RespostaDoProvedor.ExigeAutenticacao -> return pausar(
                        Estados.AGUARDANDO_AUTENTICACAO,
                        Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO,
                        evento(EventoDaSessao.BLOQUEADO, Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO, agente, "draft"),
                    )
                    else -> mensagemOperacional(agente, resposta)
                }
                anotar(evento(EventoDaSessao.BLOQUEADO, "Draft attempt failed with ${agente.rotulo}: ${corta(falha, 300)}. Trying next active agent.", agente, "draft", custo))
            }
            val texto = rascunho
            val redator = autor
            if (texto == null || redator == null) return pausar(Estados.SEM_RASCUNHO, "All active agents failed to produce an initial draft.", null)
            if (parado(soBanco = true)) return Desfecho.Interrompida
            textoAtual = EstadoCircular.textoCanonico(texto)
            autorAtual = redator
            turnoDoArtefato += 1
            val artefato = EntradaDeArtefato(
                sessaoId = id, ciclo = 0, turno = turnoDoArtefato, agente = redator, papel = "draft", status = "ready", titulo = titulo,
                texto = texto,
                relatorioDeRevisao = "{\"reviewer\":\"${redator.agente}\",\"role\":\"initial_drafter\",\"status\":\"ready\",\"custody\":\"created\"}",
                auditoriaDeLinks = emptyList(), custoUsd = custoDoRascunho?.valor, artefatoAnteriorId = null, modelo = redator.modelo,
            )
            val gravacao = ponto.gravarTurno(
                id, execucao, artefato,
                { inserido -> custodia(inserido).copy(custodiaArtefatoId = inserido!!.id, artefatoAnteriorId = inserido.id, rodada = 1, indiceDoTurno = 0) },
                Estados.RODANDO, null,
                evento(EventoDaSessao.PRONTO, "Initial draft produced.", redator, "draft", custoDoRascunho, modelo = redator.modelo),
            )
            val inserido = (gravacao as? Gravacao.Gravada)?.artefato ?: return Desfecho.Interrompida
            custodiaArtefatoId = inserido.id
            artefatoAnteriorId = inserido.id
            rodada = 1
            indiceDoTurno = 0
            aoAvancar(Progresso(rodada, autorAtual, observado))
            return null
        }

        /** O que o web trata como `operationalError` ou erro lançado pelo `callProvider`. */
        fun mensagemOperacional(agente: Provedor, resposta: RespostaDoProvedor): String = when (resposta) {
            is RespostaDoProvedor.FalhaHttp -> resposta.mensagem
            is RespostaDoProvedor.FalhaDeRede -> resposta.mensagem
            is RespostaDoProvedor.RespostaInvalida -> resposta.mensagem
            is RespostaDoProvedor.Incompleta -> resposta.motivo
            RespostaDoProvedor.SemChave -> "${agente.rotulo} sem chave configurada neste aparelho."
            RespostaDoProvedor.ChaveInvalida -> "${agente.rotulo}: a chave configurada tem caractere invalido; cole a chave de novo."
            RespostaDoProvedor.SegredoIrrecuperavel -> "${agente.rotulo}: a chave guardada foi invalidada pelo aparelho; informe a chave de novo."
            RespostaDoProvedor.ChaveIndisponivel -> "${agente.rotulo}: a chave nao pode ser lida agora; tente de novo."
            RespostaDoProvedor.ExigeAutenticacao, is RespostaDoProvedor.Concluida -> error("not an operational failure")
        }

        /** `consumeSerialTurnBudget`: `null` é "há orçamento"; um desfecho já gravou a pausa (ou a perdeu). */
        suspend fun consumirOrcamentoDeTurnos(): Desfecho? {
            turnosSeriais += 1
            if (turnosSeriais <= maxTurnosSeriais) return null
            return pausarComCustodia(
                Estados.LIMITE_DE_CICLOS,
                "Serial turn cap of $maxTurnosSeriais turns reached without unanimity.",
                evento(EventoDaSessao.BLOQUEADO, "Serial turn cap reached ($maxTurnosSeriais); session stopped without unanimity."),
            )
        }

        /**
         * `handleOperationalFailure` (Plano C): pula o turno preservando as
         * aprovações; três seguidas escalam para `paused_reviewer_outage`;
         * uma falha no turno de fechamento da rodada pausa `paused_round_incomplete`.
         * `null` é "siga"; um desfecho é "pare".
         */
        suspend fun pane(revisor: Provedor, mensagem: String, custo: Custo.Observado? = null, modelo: String? = null): Desfecho? {
            panesConsecutivas += 1
            anotar(
                evento(
                    EventoDaSessao.BLOQUEADO,
                    "Operational turn failure ($panesConsecutivas/$LIMITE_DE_PANES): ${corta(mensagem, 300)}",
                    revisor, "revision", custo, modelo = modelo,
                ),
            )
            if (panesConsecutivas >= LIMITE_DE_PANES) {
                return pausarComCustodia(
                    Estados.PANE_DE_REVISORES,
                    "$LIMITE_DE_PANES consecutive reviewer turns failed operationally; session paused for operator action.",
                )
            }
            indiceDoTurno += 1
            if (indiceDoTurno >= turnosPorRodada) {
                rodada += 1
                indiceDoTurno = 0
                validos.clear()
                return pausarComCustodia(Estados.RODADA_INCOMPLETA, "Operational failure at the end of the round; review circuit incomplete.")
            }
            return if (gravarCustodia()) null else Desfecho.Interrompida
        }

        /** `pause_final_reference_audit!`: a pausa com o contexto estruturado da auditoria no evento. */
        suspend fun pausarPelaAuditoria(falha: AuditoriaFinal.Falha): Desfecho = pausarComCustodia(
            Estados.AUDITORIA_FINAL,
            falha.motivo,
            evento(EventoDaSessao.BLOQUEADO, "Final release audit failed (${portao(falha)}): ${corta(falha.motivo, 300)}", auditoriaFinal = falha),
        )

        /** O laço serial (`sessions.ts:3795-4209`). `null` é "convergiu". */
        suspend fun laco(): Desfecho? {
            while (true) {
                if (parado()) return Desfecho.Interrompida
                // Desktop (`session_orchestration.rs:970-977`): o que só o operador pode dar pausa
                // antes da convergência e do teto de turnos, e antes de qualquer revisor pago.
                AuditoriaFinal.falhaDeEvidenciaDoOperador(textoAtual, citacoes.hashDoProtocolo, citacoes.manifesto, citacoes.manifestoAnterior, relogio())
                    ?.let { return pausarPelaAuditoria(it) }
                if (Escalonamento.temTodasAsAprovacoesIndependentes(escala, autorAtual, estaveis.toSet())) return null
                consumirOrcamentoDeTurnos()?.let { return it }
                val escolhido = Escalonamento.escolherRevisor(escala, indiceDoTurno, autorAtual, lider, validos.toSet(), estaveis.toSet(), semente())
                    ?: return pausarComCustodia(Estados.RODADA_INCOMPLETA, "No eligible reviewer could be scheduled before convergence.")
                val revisor = escala[escolhido]
                if (escolhido != indiceDoTurno % turnosPorRodada) {
                    val motivo = Escalonamento.motivoDoRedesenho(escala, indiceDoTurno, autorAtual, lider, validos.toSet(), estaveis.toSet())
                    anotar(evento(EventoDaSessao.RODANDO, "Reviewer redrawn: $motivo.", revisor, "revision"))
                }
                indiceDoTurno = escolhido
                if (revisor == autorAtual) {
                    return pausarComCustodia(Estados.AUTORREVISAO, "Scheduler invariant violation: selected reviewer is the current version author.")
                }
                if (tempoEsgotado()) {
                    anotar(evento(EventoDaSessao.BLOQUEADO, "Time guard blocked provider call before ${revisor.rotulo}.", revisor, "revision"))
                    return pausar(Estados.LIMITE_DE_TEMPO, null, null)
                }
                turno(revisor)?.let { return it }
            }
        }

        /** Um turno do revisor com as tentativas corretivas (`sessions.ts:3891-4208`). `null` é "próxima iteração". */
        suspend fun turno(revisor: Provedor): Desfecho? {
            var tentativa = 0
            while (true) {
                if (tentativa > 0) {
                    consumirOrcamentoDeTurnos()?.let { return it }
                    // Cada tentativa corretiva é uma chamada paga nova: o teto de tempo vale antes dela,
                    // como no desktop, onde a tentativa volta ao topo do laço (achado do Codex na #70).
                    if (tempoEsgotado()) {
                        anotar(evento(EventoDaSessao.BLOQUEADO, "Time guard blocked provider call before ${revisor.rotulo}.", revisor, "revision"))
                        return pausar(Estados.LIMITE_DE_TEMPO, null, null)
                    }
                }
                anotar(
                    evento(
                        EventoDaSessao.RODANDO,
                        if (tentativa > 0) {
                            "Corrective retry $tentativa/${PromptsDaSessao.MAX_TENTATIVAS_CORRETIVAS_POR_TURNO} started in round $rodada."
                        } else {
                            "Serial revision turn started in round $rodada."
                        },
                        revisor, "revision",
                    ),
                )
                var prompt = PromptsDaSessao.revisao(
                    pedido, id, indiceDoTurno + 1, textoAtual, autorAtual.agente, revisor.agente, relatorios,
                    turnoDeFechamento = revisor == lider && revisor != autorAtual, blocoDeEvidencias = blocoDeEvidencias,
                )
                if (tentativa > 0) {
                    prompt += PromptsDaSessao.secaoDeTentativaCorretiva(tentativa)
                    // Desktop (`session_orchestration.rs:1266-1280`): o pacote do portão do texto atual.
                    auditoriaMemorizada(textoAtual)?.let { prompt += PromptsDaSessao.pacoteDoPortao(it.motivo, it.contexto.bonito()) }
                }
                val (projetado, cabe) = admitido(revisor, prompt)
                if (!cabe) {
                    anotar(evento(EventoDaSessao.BLOQUEADO, "Cost guard blocked provider call before ${revisor.rotulo}.", revisor, "revision", custoUsd = projetado))
                    return pausar(Estados.LIMITE_DE_CUSTO, null, null)
                }
                val resposta = pagar(revisor, prompt) ?: return Desfecho.Interrompida
                val texto: String
                val custo: Custo.Observado
                when (resposta) {
                    is RespostaDoProvedor.Concluida -> {
                        custo = cobrar(revisor, prompt, resposta.texto, resposta.uso)
                        texto = resposta.texto
                    }
                    is RespostaDoProvedor.Incompleta -> {
                        val cobrado = cobrar(revisor, prompt, "", resposta.uso, incompleta = true)
                        return pane(revisor, resposta.motivo, cobrado, revisor.modelo)
                    }
                    RespostaDoProvedor.ExigeAutenticacao -> return pausar(
                        Estados.AGUARDANDO_AUTENTICACAO,
                        Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO,
                        evento(EventoDaSessao.BLOQUEADO, Estados.MENSAGEM_AGUARDANDO_AUTENTICACAO, revisor, "revision"),
                    )
                    else -> return pane(revisor, mensagemOperacional(revisor, resposta))
                }
                if (parado(soBanco = true)) return Desfecho.Interrompida
                if (texto.isBlank()) return pane(revisor, "O provedor ${revisor.rotulo} retornou uma revisao vazia (texto em branco).")

                val status = TurnoSerial.extrairStatus(texto) ?: "NOT_READY"
                var statusEfetivo = status
                var motivoDaRecusa: String? = null
                var auditoriaDoTexto: AuditoriaFinal.Falha? = null
                var saida: TurnoSerial.Saida? = null
                var erroDeContrato: String? = when (val validado = TurnoSerial.validar(texto, status)) {
                    is TurnoSerial.Resultado.Valido -> {
                        saida = validado.saida
                        null
                    }
                    is TurnoSerial.Resultado.Violado -> validado.motivo
                }
                val lida = saida
                if (erroDeContrato == null && lida != null && lida.textoFinal == null) {
                    // Turno sem revisão é tentativa de finalização: a auditoria inteira do texto de custódia.
                    val falha = auditoriaMemorizada(textoAtual)
                    when (AuditoriaFinal.decisaoComFalhaDaLiberacao(status, lida, falha)?.decisao) {
                        AuditoriaFinal.DecisaoDoTurnoSemRevisao.PRONTO_RECUSADO -> {
                            statusEfetivo = "NOT_READY"
                            motivoDaRecusa = falha!!.motivo
                            auditoriaDoTexto = falha
                        }
                        AuditoriaFinal.DecisaoDoTurnoSemRevisao.REPETICAO_CORRETIVA_EXIGIDA -> {
                            auditoriaDoTexto = falha
                            erroDeContrato = AuditoriaFinal.decisaoComFalhaDaLiberacao(status, lida, falha)!!.motivo
                        }
                        null -> Unit
                    }
                }
                if (erroDeContrato == null && lida != null && lida.textoFinal != null) {
                    (TurnoSerial.validarTrava(textoAtual, lida) as? TravaDeConteudo.Veredito.Violada)?.let { erroDeContrato = it.motivo }
                }
                val textoRevisado = lida?.textoFinal
                val mudou = textoRevisado != null && GuardaDeQualidade.mudancaSubstantiva(textoAtual, textoRevisado)
                if (erroDeContrato == null && textoRevisado != null && mudou &&
                    GuardaDeQualidade.bloqueiaRevisao(autorAtual.agente, revisor.agente, textoAtual, textoRevisado, true)
                ) {
                    erroDeContrato = MENSAGEM_CATRACA_DE_QUALIDADE
                }
                val relatorio = lida?.relatorio ?: TurnoSerial.extrairRelatorio(texto)
                if (erroDeContrato != null) {
                    val chave = chaveDeRetentativa(revisor)
                    val contagem = (contadoresDeRetentativa[chave] ?: 0) + 1
                    contadoresDeRetentativa[chave] = contagem
                    turnoDoArtefato += 1
                    val bloqueado = EntradaDeArtefato(
                        sessaoId = id, ciclo = rodada, turno = turnoDoArtefato, agente = revisor, papel = "revision", status = "blocked",
                        titulo = titulo, texto = textoAtual,
                        relatorioDeRevisao = ValorJson.objeto(
                            "guard" to ValorJson.texto("serial_turn_contract"),
                            "reclassified" to ValorJson.texto("CONTRACT_VIOLATION"),
                            "reason" to ValorJson.texto(erroDeContrato),
                            "attempt" to ValorJson.numero(contagem),
                            "attempted_report" to ValorJson.texto(EspacoUnicode.primeirosPontosDeCodigo(relatorio ?: "", 2000)),
                        ).bonito(),
                        auditoriaDeLinks = emptyList(), custoUsd = custo.valor, artefatoAnteriorId = artefatoAnteriorId, modelo = revisor.modelo,
                    )
                    // RejectedAttempt: aprovações preservadas, relatório fora do histórico; o artefato bloqueado
                    // é evidência só acrescentada, e vai na mesma transação da custódia (inalterada) e do evento.
                    val gravacao = ponto.gravarTurno(
                        id, execucao, bloqueado, ::custodia, Estados.RODANDO, null,
                        evento(
                            EventoDaSessao.BLOQUEADO, "Reclassificado para CONTRACT_VIOLATION: ${corta(erroDeContrato, 300)}",
                            revisor, "revision", custo, modelo = revisor.modelo, auditoriaFinal = auditoriaDoTexto,
                        ),
                    )
                    if (gravacao !is Gravacao.Gravada) return Desfecho.Interrompida
                    aoAvancar(Progresso(rodada, revisor, observado))
                    if (contagem <= PromptsDaSessao.MAX_TENTATIVAS_CORRETIVAS_POR_TURNO) {
                        tentativa = contagem
                        continue
                    }
                    return pane(revisor, "Corrective retries exhausted; reviewer turn skipped without a vote.")
                }

                // Turno aceito (`sessions.ts:4120-4206`).
                val candidato = if (textoRevisado != null && mudou) textoRevisado else textoAtual
                turnoDoArtefato += 1
                val aceito = EntradaDeArtefato(
                    sessaoId = id, ciclo = rodada, turno = turnoDoArtefato, agente = revisor, papel = "revision",
                    status = if (motivoDaRecusa != null) "ready_rejected" else statusEfetivo.lowercase(),
                    titulo = titulo, texto = candidato, relatorioDeRevisao = relatorio ?: "", auditoriaDeLinks = emptyList(),
                    custoUsd = custo.valor, artefatoAnteriorId = artefatoAnteriorId, modelo = revisor.modelo,
                )
                val recusado = motivoDaRecusa != null
                val transferiu = textoRevisado != null && mudou
                val novoAutor = if (transferiu) revisor else autorAtual
                val novosValidos = LinkedHashSet(validos).apply { if (!recusado) add(revisor) }
                val novasEstaveis = LinkedHashSet(estaveis).apply {
                    if (transferiu) clear() else if (statusEfetivo == "READY") add(revisor)
                }
                var novaRodada = rodada
                var novoIndice = indiceDoTurno + 1
                if (novoIndice >= turnosPorRodada) {
                    novaRodada += 1
                    novoIndice = 0
                    novosValidos.clear()
                }
                val mensagem = when {
                    motivoDaRecusa != null -> "READY rejected by release gate: ${corta(motivoDaRecusa, 300)}"
                    mudou -> "Reviewer revised custody text."
                    else -> "Reviewer left custody unchanged."
                }
                val gravacao = ponto.gravarTurno(
                    id, execucao, aceito,
                    { inserido ->
                        Custodia(
                            autorAtual = novoAutor,
                            textoAtual = candidato,
                            custodiaArtefatoId = if (transferiu) inserido!!.id else custodiaArtefatoId!!,
                            artefatoAnteriorId = inserido!!.id,
                            rodada = novaRodada,
                            indiceDoTurno = novoIndice,
                            escala = escala,
                            agentesValidos = novosValidos.toSet(),
                            aprovacoesEstaveis = novasEstaveis.toSet(),
                            turnoDoArtefato = turnoDoArtefato,
                        )
                    },
                    Estados.RODANDO, null,
                    evento(
                        if (statusEfetivo == "READY") EventoDaSessao.PRONTO else EventoDaSessao.NAO_PRONTO, mensagem,
                        revisor, "revision", custo, modelo = revisor.modelo, auditoriaFinal = if (recusado) auditoriaDoTexto else null,
                    ),
                )
                val inserido = (gravacao as? Gravacao.Gravada)?.artefato ?: return Desfecho.Interrompida
                artefatoAnteriorId = inserido.id
                if (!recusado) relatorios += PromptsDaSessao.RelatorioDeTurno(revisor.rotulo, "review", statusEfetivo, relatorio, inserido.id)
                panesConsecutivas = 0
                if (transferiu) {
                    textoAtual = EstadoCircular.textoCanonico(candidato)
                    autorAtual = revisor
                    custodiaArtefatoId = inserido.id
                }
                validos.clear()
                validos += novosValidos
                estaveis.clear()
                estaveis += novasEstaveis
                rodada = novaRodada
                indiceDoTurno = novoIndice
                aoAvancar(Progresso(rodada, revisor, observado))
                return null
            }
        }

        /** A auditoria fresca da finalização (`sessions.ts:4211-4267`, desktop 984) e o `converged`. */
        suspend fun finalizar(): Desfecho {
            auditoria.falha(id, textoAtual, citacoes)?.let { return pausarPelaAuditoria(it) }
            val evento = evento(EventoDaSessao.TERMINADO, "All eligible reviewers returned READY.")
            return if (sessoes.concluir(id, execucao, textoAtual, Estados.CONVERGIDA, evento)) Desfecho.Convergida else Desfecho.Interrompida
        }
    }

    public companion object {
        /** `REVIEWER_OUTAGE_ESCALATION_THRESHOLD`. */
        public const val LIMITE_DE_PANES: Int = 3
        public const val MENSAGEM_ERRO_DESCONHECIDO: String = "Unknown Maestro AI failure."
        public const val MENSAGEM_CATRACA_DE_QUALIDADE: String =
            "Anti-impoverishment quality ratchet rejected a lower-tier revision that shrank stronger custody text beyond the allowed ratio. " +
                "Preserve the accepted text and correct only protocol-grounded defects."

        /** `finalAuditEventField`: portão, motivo e o contexto sem as linhas (elas vão em `auditoriaDeLinks`). */
        internal fun campoDaAuditoria(falha: AuditoriaFinal.Falha): ObjectNode {
            val contexto = falha.contexto as? ValorJson.Objeto
            val resumo = ValorJson.Objeto(contexto?.campos?.filterKeys { it != "rows" } ?: emptyMap())
            val campo = ValorJson.objeto(
                "gate" to ValorJson.texto(portao(falha)),
                "reason" to ValorJson.texto(falha.motivo),
                "context" to resumo,
            )
            return Json.ESTRITO.readTree(campo.bonito()) as ObjectNode
        }

        internal fun portao(falha: AuditoriaFinal.Falha): String =
            ((falha.contexto as? ValorJson.Objeto)?.campos?.get("gate") as? ValorJson.Texto)?.valor ?: "unknown"

        /** As linhas do motor de links que a falha carrega (`context.rows`), para `link_audit`. */
        internal fun linhasDaFalha(falha: AuditoriaFinal.Falha): List<LinhaDeLink>? {
            val linhas = (falha.contexto as? ValorJson.Objeto)?.campos?.get("rows") as? ValorJson.Lista ?: return null
            return linhas.itens.mapNotNull { FormatoDeLinks.lerLinha(it.bonito()) }
        }
    }
}
