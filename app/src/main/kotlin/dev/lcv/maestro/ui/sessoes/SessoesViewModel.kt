/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.sessoes

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.Sincronia
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.Configuracoes
import dev.lcv.maestro.sessao.Elegibilidade
import dev.lcv.maestro.sessao.Orcamento
import dev.lcv.maestro.sessao.PedidoDeInicio
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.Resultado
import dev.lcv.maestro.sessao.SessaoEntidade
import dev.lcv.maestro.sessao.TrimJs
import dev.lcv.maestro.ui.Documentos
import dev.lcv.maestro.ui.Mensagem
import dev.lcv.maestro.ui.anexos.AnexosViewModel
import dev.lcv.maestro.ui.motivoDeArmazenamento
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A tela inicial: os cartões de métrica, o formulário **Nova sessão** e as
 * **Sessões recentes** do web (`MaestroAiModule.tsx:760-920`), sobre o Room.
 * Não há `fetch` nem sondagem de quatro segundos: a lista é observada
 * (`observarTodas`, revisão cruzada de 28/09/2026, emenda A4), e o que vem do
 * cofre e das configurações é relido a cada volta da tela ao primeiro plano
 * ([recarregar]), para uma chave guardada em Configurações aparecer aqui.
 */
class SessoesViewModel(private val d: Dependencias) : ViewModel() {

    /** O que a tela mostra, fora o formulário. */
    data class Estado(
        val sessoes: List<SessaoEntidade> = emptyList(),
        /** `activeAgent` da sessão mais recente: o agente do último evento `running`, ou o autor atual. */
        val agenteAtivo: String? = null,
        val configuracoes: Configuracoes? = null,
        val elegibilidade: Map<Provedor, Elegibilidade> = emptyMap(),
        /** O que resta do orçamento de seis horas nas últimas 24 h, pela conta deste aplicativo. */
        val restante: Duration? = null,
        val iniciando: Boolean = false,
    ) {
        /** `readyAgents`: chave e tarifas. */
        val prontos: List<Provedor> get() = Provedor.entries.filter { elegibilidade[it] == Elegibilidade.ELEGIVEL }
    }

    sealed interface Evento {
        data class Aviso(val mensagem: Mensagem) : Evento
        data class Aberta(val id: String) : Evento
    }

    // O formulário, como os `useState` do web.
    var titulo by mutableStateOf(TITULO_PADRAO)
    var pedido by mutableStateOf("")
    var textoInicial by mutableStateOf("")
    var redatorInicial by mutableStateOf(Provedor.CLAUDE)
        private set
    var colegiado by mutableStateOf(Provedor.entries.toList())
        private set

    /**
     * O manifesto de citações escolhido no formulário (especificação, seção
     * 2.2), já lido com o teto dos anexos e com a leitura que a sessão fará
     * ao começar. Ele é gravado entre a criação da sessão e o enfileiramento:
     * a sessão só começa a ler anexos depois do enfileiramento.
     */
    data class ManifestoEscolhido(val nome: String, val tipo: String, val bytes: ByteArray, val leitura: AnexosViewModel.Manifesto)

    var manifesto by mutableStateOf<ManifestoEscolhido?>(null)
        private set

    /**
     * A leitura do manifesto escolhido está em curso. Ligada no mesmo instante
     * em que o seletor devolve o arquivo, antes da leitura, que é assíncrona:
     * sem ela, um toque em Iniciar nesse meio-tempo começaria a sessão sem o
     * arquivo (achado do Codex na #78).
     */
    var lendoManifesto by mutableStateOf(false)
        private set

    private val ajustes = MutableStateFlow(Estado())
    private val eventos = Channel<Evento>(Channel.BUFFERED)
    val avisos: Flow<Evento> = eventos.receiveAsFlow()

    private val sessoes = d.sessoes.observarTodas()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val eventosDaRecente = sessoes
        .map { it.firstOrNull()?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else d.sessoes.observarEventos(id) }

    val estado: StateFlow<Estado> = combine(sessoes, eventosDaRecente, ajustes) { lista, eventosDela, base ->
        val ultimo = eventosDela.lastOrNull()
        base.copy(
            sessoes = lista,
            agenteAtivo = if (ultimo?.status == "running") ultimo.agente else lista.firstOrNull()?.autorAtual,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    /** Configurações, cofre e orçamento, relidos; na primeira leitura e quando os prontos mudam, os padrões do web. */
    fun recarregar() {
        viewModelScope.launch {
            val lidos = withContext(Dispatchers.IO) {
                val configuracoes = d.configuracoes.carregar()
                val elegibilidade = RepositorioDeConfiguracoes.elegibilidade(configuracoes.taxas, d.cofre.chaves())
                val agora = d.relogio()
                Triple(configuracoes, elegibilidade, Orcamento.restanteNaJanela(d.sessoes.execucoesNaJanela(agora), agora))
            }
            val antes = ajustes.value.prontos
            ajustes.update { it.copy(configuracoes = lidos.first, elegibilidade = lidos.second, restante = lidos.third) }
            val prontos = ajustes.value.prontos
            // `applySettings`: o primeiro pronto é o redator inicial e os prontos são o colegiado.
            if (prontos != antes) {
                prontos.firstOrNull()?.let { redatorInicial = it }
                if (prontos.isNotEmpty()) colegiado = prontos
            }
        }
    }

    fun escolherRedator(provedor: Provedor) {
        redatorInicial = provedor
    }

    /** O `onChange` da caixa do colegiado: alterna o agente e mantém o redator inicial no conjunto. */
    fun alternar(provedor: Provedor) {
        val proximo = if (provedor in colegiado) colegiado - provedor else colegiado + provedor
        colegiado = if (redatorInicial in proximo) proximo else listOf(redatorInicial) + proximo
    }

    /** O documento que o seletor devolveu como manifesto; `null` é o seletor cancelado, e nada muda. */
    fun escolherManifesto(uri: Uri?, resolver: ContentResolver) {
        if (uri == null) return
        lendoManifesto = true
        viewModelScope.launch {
            try {
                when (val leitura = withContext(Dispatchers.IO) { Documentos.ler(resolver, uri, AnexosDaSessao.MAX_BYTES) }) {
                    Documentos.Leitura.AcimaDoTeto -> eventos.send(Evento.Aviso(Mensagem.Literal(AnexosDaSessao.MENSAGEM_ACIMA_DO_TETO)))
                    Documentos.Leitura.Falhou -> eventos.send(Evento.Aviso(Mensagem.DeRecurso(R.string.anexo_ilegivel)))
                    is Documentos.Leitura.Lido -> {
                        val tipo = leitura.tipo ?: AnexosViewModel.TIPO_DESCONHECIDO
                        // O protocolo que a sessão nova vai receber é o das configurações (`resolveStartRequest`).
                        val lido = withContext(Dispatchers.IO) {
                            AnexosViewModel.lerManifesto(
                                listOf(ManifestosDosAnexos.Anexo(leitura.nome, tipo) { leitura.bytes }),
                                d.configuracoes.carregar().protocolo,
                            )
                        }
                        manifesto = ManifestoEscolhido(leitura.nome, tipo, leitura.bytes, lido)
                    }
                }
            } finally {
                lendoManifesto = false
            }
        }
    }

    fun tirarManifesto() {
        manifesto = null
    }

    /** As validações do `startSession` do web, antes de pedir permissão ou autenticação; `null` é "pode seguir". */
    fun conferirInicio(): Mensagem? {
        val prontos = ajustes.value.prontos
        val validos = colegiado.filter { it in prontos }
        val doManifesto = motivoDoManifesto(lendoManifesto, manifesto)
        return when {
            TrimJs.aparar(pedido).isEmpty() -> Mensagem.DeRecurso(R.string.erro_pedido_vazio)
            prontos.size < 2 -> Mensagem.DeRecurso(R.string.erro_dois_agentes)
            validos.size < 2 -> Mensagem.DeRecurso(R.string.erro_dois_prontos)
            redatorInicial !in validos -> Mensagem.DeRecurso(R.string.erro_redator_fora)
            doManifesto != null -> Mensagem.DeRecurso(doManifesto)
            else -> null
        }
    }

    /**
     * Chamado pela tela **depois** da permissão de notificações e da
     * autenticação: confere de novo, resolve o pedido com as regras do
     * núcleo (`resolveStartRequest`), grava a sessão e a enfileira sob a
     * mesma trava da reconciliação da abertura (emenda A11).
     */
    fun iniciar() {
        if (ajustes.value.iniciando) return
        conferirInicio()?.let {
            eventos.trySend(Evento.Aviso(it))
            return
        }
        val prontos = ajustes.value.prontos
        val pedidoDeInicio = PedidoDeInicio(
            pedido = pedido,
            titulo = titulo,
            agenteInicial = redatorInicial.agente,
            agentesAtivos = colegiado.filter { it in prontos }.map { it.agente },
            conteudoInicial = textoInicial,
        )
        val anexo = manifesto
        var releitura: AnexosViewModel.Manifesto? = null
        ajustes.update { it.copy(iniciando = true) }
        viewModelScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    val configuracoes = d.configuracoes.carregar()
                    when (val entrada = RepositorioDeConfiguracoes.resolverInicio(pedidoDeInicio, configuracoes, d.cofre.chaves())) {
                        is Resultado.Recusado -> entrada
                        is Resultado.Ok -> {
                            // O protocolo pode ter mudado nas configurações depois da escolha: o manifesto
                            // é lido de novo contra o que a sessão vai receber (achado do Codex na #78).
                            val lido = anexo?.let {
                                AnexosViewModel.lerManifesto(listOf(ManifestosDosAnexos.Anexo(it.nome, it.tipo) { it.bytes }), entrada.valor.protocolo)
                            }
                            if (lido is AnexosViewModel.Manifesto.Recusado) {
                                releitura = lido
                                Resultado.Recusado(lido.motivo)
                            } else {
                                Sincronia.reconciliacao.withLock {
                                    // A sessão e o manifesto são gravados juntos: nenhuma fica na fila sem ele.
                                    val criada = if (anexo == null) {
                                        Resultado.Ok(d.sessoes.criar(entrada.valor))
                                    } else {
                                        d.anexos.criarSessao(d.sessoes, entrada.valor, anexo.nome, anexo.tipo, anexo.bytes)
                                    }
                                    when (criada) {
                                        is Resultado.Recusado -> criada
                                        is Resultado.Ok -> {
                                            d.agendador.enfileirar(criada.valor.id)
                                            Resultado.Ok(criada.valor.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                releitura?.let { lido -> if (manifesto === anexo) manifesto = anexo?.copy(leitura = lido) }
                when (resultado) {
                    is Resultado.Recusado -> eventos.send(Evento.Aviso(Mensagem.Literal(resultado.mensagem)))
                    is Resultado.Ok -> {
                        manifesto = null
                        eventos.send(Evento.Aviso(Mensagem.DeRecurso(R.string.iniciada)))
                        eventos.send(Evento.Aberta(resultado.valor))
                    }
                }
            } catch (erro: Exception) {
                eventos.send(Evento.Aviso(Mensagem.DeRecurso(R.string.gravacao_falhou, listOf(motivoDeArmazenamento(erro)))))
            } finally {
                ajustes.update { it.copy(iniciando = false) }
            }
        }
    }

    /** A autenticação foi recusada ou cancelada: nada começou. */
    fun autenticacaoRecusada() {
        eventos.trySend(Evento.Aviso(Mensagem.DeRecurso(R.string.autenticacao_recusada)))
    }

    companion object {
        /**
         * Só do aparelho: nada começa enquanto o manifesto escolhido é lido, nem com um arquivo que a
         * sessão não leria como manifesto. `null` é "pode seguir".
         */
        fun motivoDoManifesto(lendo: Boolean, escolhido: ManifestoEscolhido?): Int? = when {
            lendo -> R.string.manifesto_em_leitura
            escolhido != null && escolhido.leitura !is AnexosViewModel.Manifesto.Lido -> R.string.erro_manifesto
            else -> null
        }

        /** O título inicial do formulário do web. */
        const val TITULO_PADRAO = "Artigo acadêmico sem título"
    }
}
