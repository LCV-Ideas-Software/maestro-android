/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.sessao

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.Sincronia
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agendador
import dev.lcv.maestro.sessao.Agentes
import dev.lcv.maestro.sessao.DetalheDoArtefato
import dev.lcv.maestro.sessao.Elegibilidade
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.ProjecaoDaSessao
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.Resultado
import dev.lcv.maestro.sessao.ResumoDoArtefato
import dev.lcv.maestro.ui.Mensagem
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A tela de uma sessão: as ações **Cancelar** e **Retomar**, o
 * **Rastreamento**, os **Autos** com as cinco abas e o **Texto** do web
 * (`MaestroAiModule.tsx:736-1140`), mais o custo acumulado ao vivo com o
 * teto da sessão (revisão cruzada de 28/09/2026, emenda A2). A sessão, os
 * eventos e os artefatos são observados no Room: cada checkpoint grava o
 * artefato e toca a linha da sessão na mesma transação, e cada custo somado
 * também toca a linha — a tela se atualiza sem sondagem.
 */
class SessaoViewModel(private val d: Dependencias, private val id: String) : ViewModel() {

    data class Estado(
        val carregada: Boolean = false,
        /** `null` depois de carregada: a sessão não existe. */
        val sessao: ProjecaoDaSessao? = null,
        val artefatos: List<ResumoDoArtefato> = emptyList(),
        val detalhe: DetalheDoArtefato? = null,
        /** O rótulo do `stopReason` do WorkManager para uma sessão em `error` (seção 4.1, item 2). */
        val ultimaParada: String? = null,
        val trabalhando: Boolean = false,
        /** `readyAgents` para o diálogo de retomada: chave no cofre e tarifas nas configurações. */
        val prontos: List<Provedor> = emptyList(),
    )

    private data class Lido(val sessao: ProjecaoDaSessao?, val artefatos: List<ResumoDoArtefato>, val ultimaParada: String?)

    private val escolha = MutableStateFlow<String?>(null)
    private val ajustes = MutableStateFlow(Estado())
    private val eventos = Channel<Mensagem>(Channel.BUFFERED)
    val avisos: Flow<Mensagem> = eventos.receiveAsFlow()

    // O diálogo de retomada.
    var dialogoAberto by mutableStateOf(false)
        private set
    var lider by mutableStateOf<Provedor?>(null)
        private set
    var painel by mutableStateOf<List<Provedor>>(emptyList())
        private set
    var novoTeto by mutableStateOf("")

    private val lido: Flow<Lido> = combine(d.sessoes.observar(id), d.sessoes.observarEventos(id)) { linha, eventosDaSessao ->
        if (linha == null) {
            Lido(null, emptyList(), null)
        } else {
            Lido(
                sessao = ProjecaoDaSessao.de(linha, eventosDaSessao),
                artefatos = d.artefatos.daSessao(id).map(ResumoDoArtefato::de),
                ultimaParada = if (linha.status == Estados.ERRO) d.agendador.ultimaParada(id)?.let(Agendador::rotuloDaParada) else null,
            )
        }
    }.flowOn(Dispatchers.IO).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** `selectedArtifactSummary`: o escolhido, se ainda está na lista; senão o último. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detalhe: Flow<DetalheDoArtefato?> = combine(lido.map { it.artefatos }, escolha) { artefatos, escolhido ->
        (artefatos.firstOrNull { it.id == escolhido } ?: artefatos.lastOrNull())?.id
    }.mapLatest { artefatoId ->
        artefatoId?.let { d.artefatos.um(id, it) }?.let { linha ->
            DetalheDoArtefato.de(linha, linha.artefatoAnteriorId?.let { anterior -> d.artefatos.um(id, anterior) })
        }
    }.flowOn(Dispatchers.IO)

    val estado: StateFlow<Estado> = combine(lido, detalhe, ajustes) { lido, detalhe, base ->
        base.copy(carregada = true, sessao = lido.sessao, artefatos = lido.artefatos, detalhe = detalhe, ultimaParada = lido.ultimaParada)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    fun escolherArtefato(artefatoId: String) {
        escolha.value = artefatoId
    }

    /** Os agentes prontos, relidos a cada volta da tela ao primeiro plano (emenda A4). */
    fun recarregar() {
        viewModelScope.launch {
            val prontos = withContext(Dispatchers.IO) { lerProntos() }
            ajustes.update { it.copy(prontos = prontos) }
        }
    }

    private suspend fun lerProntos(): List<Provedor> {
        val elegibilidade = RepositorioDeConfiguracoes.elegibilidade(d.configuracoes.carregar().taxas, d.cofre.chaves())
        return Provedor.entries.filter { elegibilidade[it] == Elegibilidade.ELEGIVEL }
    }

    /** `cancelSession`: grava o cancelamento e só então cancela o trabalho, na ordem do receptor da notificação. */
    fun cancelar() {
        if (ajustes.value.trabalhando) return
        ajustes.update { it.copy(trabalhando = true) }
        viewModelScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    d.sessoes.cancelar(id).also { if (it is Resultado.Ok) d.agendador.cancelar(id) }
                }
                eventos.send(
                    when (resultado) {
                        is Resultado.Ok -> Mensagem.DeRecurso(R.string.cancelada)
                        is Resultado.Recusado -> Mensagem.Literal(resultado.mensagem)
                    },
                )
            } finally {
                ajustes.update { it.copy(trabalhando = false) }
            }
        }
    }

    /**
     * Abre o diálogo de retomada com os padrões da linha: o líder do ciclo, o
     * colegiado da sessão entre os prontos e, numa pausa por custo, um teto
     * novo no primeiro dólar inteiro acima do teto atual e do custo observado
     * (emenda A9).
     */
    fun abrirRetomada() {
        val sessao = estado.value.sessao ?: return
        viewModelScope.launch {
            val prontos = withContext(Dispatchers.IO) { lerProntos() }
            ajustes.update { it.copy(prontos = prontos) }
            lider = Agentes.porChave(sessao.liderDoCiclo)
            painel = sessao.agentesAtivos.filter { it in prontos }
            novoTeto = if (sessao.status == Estados.LIMITE_DE_CUSTO) {
                sessao.tetoDeCustoUsd.max(sessao.custoObservadoUsd).setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE).toPlainString()
            } else {
                ""
            }
            dialogoAberto = true
        }
    }

    fun fecharRetomada() {
        dialogoAberto = false
    }

    fun escolherLider(provedor: Provedor) {
        lider = provedor
    }

    /**
     * O `onChange` das caixas do web, que a retomada compartilha com o início:
     * alterna o agente e devolve o líder ao colegiado se ele sair.
     */
    fun alternarNoPainel(provedor: Provedor) {
        val proximo = if (provedor in painel) painel - provedor else painel + provedor
        val liderAtual = lider
        painel = if (liderAtual == null || liderAtual in proximo) proximo else listOf(liderAtual) + proximo
    }

    /** As validações do `resumeSession` do web, e o teto novo de uma pausa por custo; `null` é "pode seguir". */
    fun conferirRetomada(): Mensagem? {
        val validos = painel.filter { it in ajustes.value.prontos }
        return when {
            validos.size < 2 -> Mensagem.DeRecurso(R.string.erro_retomar_dois)
            lider !in validos -> Mensagem.DeRecurso(R.string.erro_retomar_lider)
            estado.value.sessao?.status == Estados.LIMITE_DE_CUSTO && tetoInformado() == null -> Mensagem.DeRecurso(R.string.erro_teto_invalido)
            else -> null
        }
    }

    private fun tetoInformado(): BigDecimal? = novoTeto.trim().toBigDecimalOrNull()

    /**
     * Chamado pela tela **depois** da autenticação (emenda A6): numa pausa por
     * custo, sobe o teto primeiro (a sessão só pode ser retomada com teto acima
     * do que ela já gastou); depois `POST /resume` e o enfileiramento, sob a
     * mesma trava da reconciliação da abertura (emenda A11).
     */
    fun retomar() {
        if (ajustes.value.trabalhando) return
        conferirRetomada()?.let {
            eventos.trySend(it)
            return
        }
        val sessao = estado.value.sessao ?: return
        val liderEscolhido = lider ?: return
        val validos = painel.filter { it in ajustes.value.prontos }
        val teto = if (sessao.status == Estados.LIMITE_DE_CUSTO) tetoInformado() else null
        dialogoAberto = false
        ajustes.update { it.copy(trabalhando = true) }
        viewModelScope.launch {
            try {
                val resultado = withContext(Dispatchers.IO) {
                    Sincronia.reconciliacao.withLock {
                        val subiu = teto?.let { d.sessoes.subirTeto(id, it) }
                        if (subiu is Resultado.Recusado) return@withLock subiu
                        d.retomada.pedir(id, liderEscolhido.agente, validos.map { it.agente }, d.cofre.chaves()).also {
                            if (it is Resultado.Ok) d.agendador.enfileirar(id)
                        }
                    }
                }
                eventos.send(
                    when (resultado) {
                        is Resultado.Ok -> Mensagem.DeRecurso(R.string.retomada)
                        is Resultado.Recusado -> Mensagem.Literal(resultado.mensagem)
                    },
                )
            } finally {
                ajustes.update { it.copy(trabalhando = false) }
            }
        }
    }

    fun autenticacaoRecusada() {
        eventos.trySend(Mensagem.DeRecurso(R.string.autenticacao_recusada_retomada))
    }

    companion object {
        /** Decisão 16 do operador (27/09/2026): a execução morreu com uma chamada paga sem resultado. */
        fun chamadaIndeterminada(erro: String?): Boolean =
            erro != null && Provedor.entries.any { erro == RepositorioDeSessoes.mensagemDeChamadaIndeterminada(it) }
    }
}
