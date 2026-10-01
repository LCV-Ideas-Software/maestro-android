/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.anexos

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import dev.lcv.maestro.sessao.AnexoEntidade
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.CitacoesDaSessao
import dev.lcv.maestro.sessao.Resultado
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import dev.lcv.maestro.ui.Documentos
import dev.lcv.maestro.ui.LeiturasDaTela
import dev.lcv.maestro.ui.Mensagem
import dev.lcv.maestro.ui.OrdemDasLeituras
import dev.lcv.maestro.ui.Rotulos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Os anexos de uma sessão e o manifesto de citações (especificação, seção
 * 2.2; MAEANDR-18). A validação é a mesma leitura que a sessão faz ao começar
 * (`ManifestosDosAnexos.extrair`), então o que a tela diz agora é o que o
 * worker vai ver: um manifesto recusado pausaria a sessão em
 * `paused_final_audit` antes de qualquer chamada paga. Com a sessão na fila
 * ou em execução, os anexos não mudam — o worker os lê ao começar.
 */
class AnexosViewModel(private val d: Dependencias, private val id: String) : ViewModel() {

    /** O que a leitura dos anexos diz do manifesto de citações. */
    sealed interface Manifesto {
        data object Ausente : Manifesto

        data class Lido(val citacoes: Int, val fontes: Int, val comAnterior: Boolean) : Manifesto

        data class Recusado(val motivo: String) : Manifesto
    }

    data class Estado(
        val carregada: Boolean = false,
        val existe: Boolean = true,
        val titulo: String = "",
        val emExecucao: Boolean = false,
        val anexos: List<AnexoEntidade> = emptyList(),
        val manifesto: Manifesto = Manifesto.Ausente,
        val trabalhando: Boolean = false,
        /** O motivo de a sessão ou os anexos nunca terem sido lidos (decisão 25 estendida, #80). */
        val falhaDeLeitura: String? = null,
    )

    private data class Conteudo(val anexos: List<AnexoEntidade>, val manifesto: Manifesto)

    private val conteudo = MutableStateFlow<Conteudo?>(null)

    /** O motivo da última releitura dos anexos que falhou; só aparece enquanto nada foi lido. */
    private val falhaDoConteudo = MutableStateFlow<String?>(null)
    private val trabalhando = MutableStateFlow(false)
    private val eventos = Channel<Mensagem>(Channel.BUFFERED)
    val avisos: Flow<Mensagem> = eventos.receiveAsFlow()

    /** As leituras da tela sob a decisão 25 estendida (#80): aviso com o motivo, a tela segue, a volta lê de novo. */
    private val leituras = LeiturasDaTela { eventos.send(it) }

    val estado: StateFlow<Estado> = combine(
        leituras.observar(d.sessoes.observar(id), null),
        conteudo,
        falhaDoConteudo,
        trabalhando,
    ) { linha, lido, falha, emCurso ->
        Estado(
            carregada = lido != null || falha != null,
            existe = linha.valor != null,
            titulo = linha.valor?.titulo.orEmpty(),
            emExecucao = Rotulos.emExecucao(linha.valor?.status),
            anexos = lido?.anexos.orEmpty(),
            manifesto = lido?.manifesto ?: Manifesto.Ausente,
            trabalhando = emCurso,
            falhaDeLeitura = linha.falha ?: falha.takeIf { lido == null },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    /** A ordem das releituras, declarada antes do `init` que começa a primeira. */
    private val ordem = OrdemDasLeituras()

    init {
        viewModelScope.launch { reler() }
    }

    /**
     * A volta da tela ao primeiro plano. A primeira chamada é a abertura, e a leitura dela e a do `init` falham na
     * mesma volta: um aviso só (decisão 25 estendida, #80).
     */
    fun recarregar() {
        leituras.voltou()
        viewModelScope.launch { reler() }
    }

    /**
     * Relê os anexos. O armazenamento que falha na releitura mantém a lista que a tela tinha, com o
     * aviso, e não passa por falha da gravação que já foi feita (decisão 25; achado do Codex na #78).
     */
    private suspend fun reler() {
        // Vale a releitura mais nova que terminou bem (achado do Codex na #78; ver `OrdemDasLeituras`).
        val esta = ordem.comecar()
        val lido = try {
            withContext(Dispatchers.IO) { ler() }
        } catch (erro: Exception) {
            // Sem lista lida, "a lista ficou como estava" seria falso: o aviso é o geral, e o motivo fica no lugar (#80).
            val mensagem: (String) -> Mensagem = if (conteudo.value == null) {
                LeiturasDaTela::avisoDeLeitura
            } else {
                { motivo -> Mensagem.DeRecurso(R.string.anexos_leitura_falhou, listOf(motivo)) }
            }
            falhaDoConteudo.value = leituras.falhou(erro, mensagem)
            return
        }
        if (ordem.aplicar(esta)) conteudo.value = lido
    }


    private fun ler(): Conteudo {
        val protocolo = d.sessoes.carregar(id)?.protocolo.orEmpty()
        return Conteudo(d.anexos.daSessao(id), lerManifesto(d.anexos.listar(id), protocolo))
    }

    /** O documento que o seletor devolveu; `null` é o seletor cancelado, e nada muda. */
    fun anexar(uri: Uri?, resolver: ContentResolver) {
        if (uri == null || !podeMexer()) return
        mexer {
            when (val leitura = Documentos.ler(resolver, uri, AnexosDaSessao.MAX_BYTES)) {
                Documentos.Leitura.AcimaDoTeto -> Mensagem.Literal(AnexosDaSessao.MENSAGEM_ACIMA_DO_TETO)
                Documentos.Leitura.Falhou -> Mensagem.DeRecurso(R.string.anexo_ilegivel)
                is Documentos.Leitura.Lido -> when (
                    val salvo = d.anexos.adicionar(id, leitura.nome, leitura.tipo ?: TIPO_DESCONHECIDO, leitura.bytes)
                ) {
                    is Resultado.Recusado -> Mensagem.Literal(salvo.mensagem)
                    is Resultado.Ok -> Mensagem.DeRecurso(R.string.anexo_adicionado)
                }
            }
        }
    }

    fun remover(anexoId: String) {
        if (!podeMexer()) return
        mexer {
            when (val removido = d.anexos.remover(anexoId)) {
                is Resultado.Recusado -> Mensagem.Literal(removido.mensagem)
                is Resultado.Ok -> Mensagem.DeRecurso(R.string.anexo_removido)
            }
        }
    }

    private fun podeMexer(): Boolean {
        if (trabalhando.value) return false
        if (estado.value.emExecucao) {
            eventos.trySend(Mensagem.Literal(AnexosDaSessao.MENSAGEM_EM_EXECUCAO))
            return false
        }
        return true
    }

    private fun mexer(acao: () -> Mensagem) {
        trabalhando.value = true
        // Uma ação é um pedido novo: a releitura dela que falha é avisada mesmo que outra já tenha sido nesta volta (#80).
        leituras.pedido()
        viewModelScope.launch {
            try {
                val mensagem = try {
                    withContext(Dispatchers.IO) { acao() }
                } catch (erro: Exception) {
                    Mensagem.DeRecurso(R.string.gravacao_falhou, listOf(motivoDeArmazenamento(erro)))
                }
                reler()
                eventos.send(mensagem)
            } finally {
                trabalhando.value = false
            }
        }
    }

    companion object {
        const val TIPO_DESCONHECIDO = "application/octet-stream"

        /**
         * A leitura que a sessão faz dos seus anexos ao começar, no que ela diz do manifesto: a
         * extração (`ManifestosDosAnexos.extrair`) e o vínculo com [protocolo], o texto do protocolo
         * da sessão (`CitacoesDaSessao.recusaDoVinculo`).
         */
        fun lerManifesto(anexos: List<ManifestosDosAnexos.Anexo>, protocolo: String): Manifesto = when (val saida = ManifestosDosAnexos.extrair(anexos)) {
            is ManifestosDosAnexos.Saida.Recusados -> Manifesto.Recusado(saida.motivo)
            is ManifestosDosAnexos.Saida.Lidos -> saida.manifestos.atual?.let {
                CitacoesDaSessao.recusaDoVinculo(it, protocolo)?.let(Manifesto::Recusado)
                    ?: Manifesto.Lido(it.citacoes.size, it.fontes.size, saida.manifestos.anterior != null)
            } ?: Manifesto.Ausente
        }
    }
}
