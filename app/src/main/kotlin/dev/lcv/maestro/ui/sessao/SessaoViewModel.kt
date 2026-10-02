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
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import dev.lcv.maestro.ui.LeiturasDaTela
import dev.lcv.maestro.ui.Lida
import dev.lcv.maestro.ui.Mensagem
import dev.lcv.maestro.ui.OrdemDasLeituras
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
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
        /** O motivo de a sessão nunca ter sido lida (decisão 25 estendida, #80). */
        val falhaDeLeitura: String? = null,
        /** O motivo de os autos (a lista ou o artefato) nunca terem sido lidos. */
        val falhaDosAutos: String? = null,
    )

    private data class Lido(
        val sessao: ProjecaoDaSessao?,
        val artefatos: List<ResumoDoArtefato>,
        val ultimaParada: String?,
        /** O motivo de a lista dos autos nunca ter sido lida. */
        val falhaDosAutos: String? = null,
    )

    /** Um toque num artefato: [vez] faz de cada toque um valor novo, que o `StateFlow` não descarta (achado na #81). */
    private data class Pedido(val id: String, val vez: Long)

    private val escolha = MutableStateFlow<Pedido?>(null)
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

    /** As leituras da tela sob a decisão 25 estendida (#80): aviso com o motivo, a tela segue, a volta lê de novo. */
    private val leituras = LeiturasDaTela { eventos.send(it) }

    /** A ordem das leituras dos agentes prontos, a da volta e a da abertura da retomada (achado na revisão da #81). */
    private val ordemDosProntos = OrdemDasLeituras()

    /** Uma abertura da retomada em curso: o toque seguinte não abre outra. Linha principal. */
    private var abrindoRetomada = false

    /** O rótulo da parada que a tela mostra, e o artefato mostrado: uma falha depois não os troca. */
    @Volatile private var paradaMostrada: String? = null

    /** A volta em que a leitura da parada falhou: outra emissão na mesma volta não consulta o WorkManager de novo (#81). */
    @Volatile private var paradaFalhouNaVolta = -1

    private val detalheMostrado = AtomicReference<Lida<DetalheDoArtefato?>?>(null)

    /**
     * Os autos, observados pela tabela de artefatos: o custo e o jornal, que mudam a
     * cada passo da sessão, não relêem a lista nem o artefato escolhido (achado do
     * Codex na #72).
     */
    private val resumos: Flow<Lida<List<ResumoDoArtefato>>> = leituras.observar(d.artefatos.observarResumos(id), emptyList())
        .flowOn(Dispatchers.IO).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** A volta da tela também é fonte: a parada no WorkManager, que o Room não observa, é relida a cada volta. */
    private val lido: Flow<Lida<Lido>> = leituras.observar(
        combine(d.sessoes.observar(id), d.sessoes.observarEventos(id), resumos, leituras.volta) { linha, eventosDaSessao, autos, volta ->
            if (linha == null) {
                Lido(null, emptyList(), null)
            } else {
                Lido(
                    sessao = ProjecaoDaSessao.de(linha, eventosDaSessao),
                    artefatos = autos.valor,
                    ultimaParada = if (linha.status == Estados.ERRO) {
                        paradaDe(volta)
                    } else {
                        // O rótulo guardado é o deste erro: fora dele, uma falha depois não traz o de outra execução, e
                        // um erro novo na mesma volta é outra leitura.
                        paradaMostrada = null
                        paradaFalhouNaVolta = -1
                        null
                    },
                    falhaDosAutos = autos.falha,
                )
            }
        },
        Lido(null, emptyList(), null),
    ).flowOn(Dispatchers.IO).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /**
     * O rótulo da última parada no WorkManager. O `get()` do futuro embrulha o erro do banco do WorkManager em
     * `ExecutionException`, que o classificador desembrulha; falhando, a tela fica com o rótulo que mostrava e o resto
     * da sessão segue ao vivo (decisão 25 estendida, #80). Uma leitura que falhou espera a volta seguinte: a escrita de
     * outra sessão, que reemite os fluxos do Room, não a refaz ([volta] é a que o `combine` recebeu; achado do Codex na
     * #81).
     */
    private suspend fun paradaDe(volta: Int): String? {
        if (paradaFalhouNaVolta == volta) return paradaMostrada
        return try {
            d.agendador.ultimaParada(id)?.let(Agendador::rotuloDaParada).also { paradaMostrada = it }
        } catch (erro: Exception) {
            leituras.falhou(erro)
            paradaFalhouNaVolta = volta
            paradaMostrada
        }
    }

    /**
     * `selectedArtifactSummary`: o escolhido, se ainda está na lista; senão o último. O artefato que não se lê é o
     * aviso; a tela fica no artefato que mostrava, ou mostra o motivo no lugar se nenhum foi mostrado, e lê de novo na
     * volta da tela; outra escolha, um toque de novo no mesmo ou um artefato novo cancelam a espera (decisão 25
     * estendida, #80). A leitura recriada pelo `WhileSubscribed` que falha reentrega o artefato mostrado, para a tela
     * não congelar (achado do Codex na #81).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val detalhe: Flow<Lida<DetalheDoArtefato?>> = combine(resumos, escolha) { autos, escolhido ->
        (autos.valor.firstOrNull { it.id == escolhido?.id } ?: autos.valor.lastOrNull())?.id
    }.flatMapLatest { artefatoId ->
        leituras.ler(null, detalheMostrado, { it != null }) {
            artefatoId?.let { d.artefatos.um(id, it) }?.let { linha ->
                DetalheDoArtefato.de(linha, linha.artefatoAnteriorId?.let { anterior -> d.artefatos.um(id, anterior) })
            }
        }
    }.flowOn(Dispatchers.IO)

    val estado: StateFlow<Estado> = combine(lido, detalhe, ajustes) { lido, detalhe, base ->
        base.copy(
            carregada = true,
            sessao = lido.valor.sessao,
            artefatos = lido.valor.artefatos,
            detalhe = detalhe.valor,
            ultimaParada = lido.valor.ultimaParada,
            falhaDeLeitura = lido.falha,
            falhaDosAutos = lido.valor.falhaDosAutos ?: detalhe.falha,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    fun escolherArtefato(artefatoId: String) {
        // Um toque é um pedido novo: se o artefato não se lê, o aviso sai mesmo que outro já tenha saído nesta volta (#80).
        leituras.pedido()
        escolha.update { Pedido(artefatoId, (it?.vez ?: 0) + 1) }
    }

    /**
     * Os agentes prontos, relidos a cada volta da tela ao primeiro plano (emenda A4); a volta também relê o que
     * falhou. O armazenamento que falha é o aviso, e os prontos ficam os que a tela tinha (decisão 25 estendida, #80).
     */
    fun recarregar() {
        leituras.voltou()
        viewModelScope.launch {
            val esta = ordemDosProntos.comecar()
            val prontos = try {
                withContext(Dispatchers.IO) { lerProntos() }
            } catch (erro: Exception) {
                // Uma releitura já superada por outra mais nova que deu certo não decide nada: nem aviso, nem o aviso da
                // volta gasto; o que não é armazenamento segue adiante (achado do Codex na #81).
                if (!ordemDosProntos.valeAFalha(esta)) {
                    motivoDeArmazenamento(erro)
                    return@launch
                }
                leituras.falhou(erro)
                return@launch
            }
            if (ordemDosProntos.aplicar(esta)) ajustes.update { it.copy(prontos = prontos) }
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
            } catch (erro: Exception) {
                eventos.send(Mensagem.DeRecurso(R.string.gravacao_falhou, listOf(motivoDeArmazenamento(erro))))
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
        // Um toque durante a abertura não abre outra: ela leria de novo e reporia o que a pessoa já marcou (#81).
        if (abrindoRetomada) return
        abrindoRetomada = true
        viewModelScope.launch {
            try {
                val esta = ordemDosProntos.comecar()
                // Os agentes prontos saem das configurações, no Room: o armazenamento que falha é a falha de abrir (decisão 25).
                val lidos = try {
                    withContext(Dispatchers.IO) { lerProntos() }
                } catch (erro: Exception) {
                    val motivo = motivoDeArmazenamento(erro)
                    // Superada por uma releitura mais nova que deu certo, a falha não decide: abre com os prontos dela (#81).
                    if (ordemDosProntos.valeAFalha(esta)) {
                        eventos.send(Mensagem.DeRecurso(R.string.leitura_do_aparelho_falhou, listOf(motivo)))
                        return@launch
                    }
                    null
                }
                // Vale a leitura mais nova: uma da volta que terminou depois desta já pôs os prontos dela.
                if (lidos != null && ordemDosProntos.aplicar(esta)) ajustes.update { it.copy(prontos = lidos) }
                val prontos = ajustes.value.prontos
                lider = Agentes.porChave(sessao.liderDoCiclo)
                painel = sessao.agentesAtivos.filter { it in prontos }
                novoTeto = if (sessao.status == Estados.LIMITE_DE_CUSTO) {
                    sessao.tetoDeCustoUsd.max(sessao.custoObservadoUsd).setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE).toPlainString()
                } else {
                    ""
                }
                dialogoAberto = true
            } finally {
                abrindoRetomada = false
            }
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
     * Chamado pela tela **depois** da autenticação (emenda A6): `POST /resume`
     * e o enfileiramento, sob a mesma trava da reconciliação da abertura
     * (emenda A11). Numa pausa por custo, o teto novo vai no próprio pedido e
     * sobe na transação da retomada: recusada a retomada, o teto fica onde
     * estava (achado do Codex na #72).
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
                        d.retomada.pedir(id, liderEscolhido.agente, validos.map { it.agente }, d.cofre.chaves(), teto).also {
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
            } catch (erro: Exception) {
                eventos.send(Mensagem.DeRecurso(R.string.gravacao_falhou, listOf(motivoDeArmazenamento(erro))))
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
