/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.R
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update

/** Um valor lido do aparelho; [falha] é o motivo de a leitura ter falhado antes de haver o que mostrar (#80). */
internal data class Lida<T>(val valor: T, val falha: String? = null)

/**
 * As leituras de uma tela sob a decisão 25 do operador, estendida em 30/09/2026 (#80), "aviso e segue": o armazenamento
 * que falha numa leitura ao abrir ou ao voltar à tela, ou numa observação, vira aviso com o motivo; a tela fica com o
 * que mostrava, ou mostra o motivo se nunca leu; e a próxima volta da tela ao primeiro plano lê de novo, sem laço.
 * Um disco que derruba várias leituras dá um aviso por volta. Cada ViewModel que a usa tem a sua; Configurações e
 * Licenças, que leem até carregar e depois não relêem, seguem a mesma regra à parte.
 */
internal class LeiturasDaTela(private val avisar: suspend (Mensagem) -> Unit) {

    private val voltas = MutableStateFlow(0)

    /** Linha principal: a primeira [voltou] é a abertura da tela. */
    private var aberta = false

    /** A volta em que a última falha foi avisada. */
    private val avisadaNaVolta = AtomicInteger(-1)

    /** A volta corrente, para o `combine` que relê a cada volta algo que não é observado. */
    val volta: StateFlow<Int> = voltas.asStateFlow()

    /**
     * Chamado a cada ON_RESUME da tela, na linha principal. A primeira chamada é a abertura, não uma volta: não relê o
     * que acabou de falhar nem avisa de novo (a coleta pode ter começado antes, em ON_START).
     */
    fun voltou() {
        if (aberta) voltas.update { it + 1 } else aberta = true
    }

    /** Um toque é um pedido novo: a falha dele é avisada, mesmo que outra já tenha sido nesta volta. */
    fun pedido() {
        avisadaNaVolta.set(-1)
    }

    /**
     * O motivo da falha de armazenamento [erro], avisado uma vez por volta com [mensagem]. O que não é armazenamento,
     * inclusive o cancelamento da corrotina, segue adiante.
     */
    suspend fun falhou(erro: Throwable, mensagem: (String) -> Mensagem = ::avisoDeLeitura): String {
        val motivo = motivoDeArmazenamento(erro as? Exception ?: throw erro)
        val esta = voltas.value
        if (avisadaNaVolta.getAndSet(esta) != esta) avisar(mensagem(motivo))
        return motivo
    }

    /** Suspende até a primeira volta depois de [desde]. */
    suspend fun esperarVolta(desde: Int) {
        voltas.first { volta -> volta > desde }
    }

    /**
     * [fluxo] sob a regra: enquanto nada foi lido, a falha entrega [vazio] com o motivo; depois, não entrega nada, e a
     * tela fica no que mostrava. Em qualquer caso, a observação para até a próxima volta e então lê de novo. [leu] é o
     * que já foi mostrado; quem recria a observação para o mesmo conteúdo passa o mesmo, para não apagá-lo.
     */
    fun <T> observar(fluxo: Flow<T>, vazio: T, leu: AtomicBoolean = AtomicBoolean(false)): Flow<Lida<T>> =
        fluxo.map { valor ->
            leu.set(true)
            Lida(valor)
        }.retryWhen { erro, _ ->
            val desde = voltas.value
            val motivo = falhou(erro)
            if (!leu.get()) emit(Lida(vazio, motivo))
            esperarVolta(desde)
            true
        }

    companion object {
        /** O aviso passageiro da leitura que falhou. O vazio no lugar usa `tela_sem_leitura`. */
        fun avisoDeLeitura(motivo: String): Mensagem = Mensagem.DeRecurso(R.string.leitura_do_aparelho_falhou, listOf(motivo))
    }
}
