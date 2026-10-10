/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.configuracoes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import android.content.Context
import dev.lcv.maestro.R
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.seguranca.Remocao
import dev.lcv.maestro.sessao.Campo
import dev.lcv.maestro.sessao.Configuracoes
import dev.lcv.maestro.sessao.PedidoDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.Resultado
import dev.lcv.maestro.sessao.ResultadoDoTeste
import dev.lcv.maestro.sessao.TrimJs
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import dev.lcv.maestro.ui.Mensagem
import dev.lcv.maestro.ui.OrdemDasLeituras
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A seção **Configurações** do web (`MaestroAiModule.tsx:1170-1440`) com o
 * que só existe no Android: as chaves vão ao cofre do aparelho, uma por vez,
 * e nunca voltam à tela; o nível da chave do Keystore; a chave perdida com a
 * trava de tela (emenda A14); o contato para o Crossref; o teste de chaves
 * atrás de confirmação e autenticação (decisão 20). Custos, protocolo e
 * e-mail são salvos juntos, pelas regras do `RepositorioDeConfiguracoes`.
 */
class ConfiguracoesViewModel(private val d: Dependencias) : ViewModel() {

    data class Estado(
        val carregado: Boolean = false,
        val chaves: Map<Provedor, Boolean?> = emptyMap(),
        val nivel: NivelDoCofre? = null,
        val travaDeTela: Boolean = true,
        val salvando: Boolean = false,
        val testando: Boolean = false,
        val resultados: List<ResultadoDoTeste> = emptyList(),
        /** O cofre respondeu que não há trava de tela: a tela oferece os ajustes de segurança. */
        val semTrava: Boolean = false,
        /** A leitura das configurações que falhou antes de o formulário ter o que mostrar (decisão 25 estendida, #80). */
        val falhaDaLeitura: String? = null,
    )

    sealed interface Evento {
        data class Aviso(val mensagem: Mensagem) : Evento

        /** O cofre pediu autenticação para guardar a chave deste provedor. */
        data class Autenticar(val provedor: Provedor) : Evento
    }

    /** Uma linha de tarifas como o usuário a digita. */
    data class TaxasDigitadas(val entrada: String, val saida: String, val busca: String)

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()
    private val eventos = Channel<Evento>(Channel.BUFFERED)
    val avisos: Flow<Evento> = eventos.receiveAsFlow()

    // O formulário. As chaves digitadas nunca são preenchidas a partir do cofre.
    val chavesDigitadas = mutableStateMapOf<Provedor, String>()
    var teto by mutableStateOf("")
    var limiteDeMinutos by mutableStateOf("")
    val taxas = mutableStateMapOf<Provedor, TaxasDigitadas>()
    var protocolo by mutableStateOf("")
    var email by mutableStateOf("")

    /**
     * `loadSettings`, a cada volta da tela ao primeiro plano até o formulário receber o que está gravado. O
     * armazenamento que falha é aviso, e o motivo fica no lugar do formulário; a volta seguinte lê de novo
     * (decisão 25 estendida, #80). Depois de lido, o formulário é o que a pessoa digita: a volta não o sobrescreve.
     */
    fun carregar() {
        if (_estado.value.carregado) return
        viewModelScope.launch {
            val lidas = try {
                withContext(Dispatchers.IO) { d.configuracoes.carregar() }
            } catch (erro: Exception) {
                val motivo = motivoDeArmazenamento(erro)
                // Outra retomada já carregou o formulário: a falha desta, mais antiga, não decide nada (#81).
                if (_estado.value.carregado) return@launch
                _estado.update { it.copy(falhaDaLeitura = motivo) }
                avisar(Mensagem.DeRecurso(R.string.leitura_do_aparelho_falhou, listOf(motivo)))
                return@launch
            }
            // Outra retomada pode ter lido e aplicado enquanto esta lia: o formulário já é o que a pessoa digita.
            if (_estado.value.carregado) return@launch
            aplicar(lidas)
        }
    }

    /** O formulário de custos, limite de tempo, tarifas, protocolo e e-mail como foi enviado a uma gravação. */
    private data class Formulario(
        val teto: String,
        val limiteDeMinutos: String,
        val taxas: Map<Provedor, TaxasDigitadas>,
        val protocolo: String,
        val email: String,
    )

    /**
     * `applySettings`: o formulário volta ao que está gravado. Depois de uma gravação, com o formulário [enviado],
     * só o campo que ainda tem o que foi enviado recebe o valor gravado, já normalizado pelo núcleo; o que a pessoa
     * digitou enquanto a gravação corria fica (MAEANDR-40).
     */
    private fun aplicar(configuracoes: Configuracoes, enviado: Formulario? = null) {
        fun gravadoSeNaoMudou(atual: String, doEnvio: String?, gravado: String) =
            if (enviado == null || atual == doEnvio) gravado else atual
        teto = gravadoSeNaoMudou(teto, enviado?.teto, configuracoes.tetoDeCustoUsd.toPlainString())
        limiteDeMinutos = gravadoSeNaoMudou(limiteDeMinutos, enviado?.limiteDeMinutos, configuracoes.tetoDeMinutos?.toString() ?: "")
        Provedor.entries.forEach { agente ->
            val atuais = configuracoes.taxas[agente]
            val digitadas = taxas[agente] ?: TaxasDigitadas("", "", "")
            val enviadas = enviado?.taxas?.get(agente)
            taxas[agente] = TaxasDigitadas(
                entrada = gravadoSeNaoMudou(digitadas.entrada, enviadas?.entrada, atuais?.entradaPorMilhao?.toPlainString() ?: ""),
                saida = gravadoSeNaoMudou(digitadas.saida, enviadas?.saida, atuais?.saidaPorMilhao?.toPlainString() ?: ""),
                busca = gravadoSeNaoMudou(digitadas.busca, enviadas?.busca, atuais?.requisicoesPorMil?.toPlainString() ?: ""),
            )
        }
        protocolo = gravadoSeNaoMudou(protocolo, enviado?.protocolo, configuracoes.protocolo)
        email = gravadoSeNaoMudou(email, enviado?.email, configuracoes.emailDeContato ?: "")
        _estado.update { it.copy(carregado = true) }
    }

    /** O estado do cofre, relido a cada volta da tela ao primeiro plano: a trava de tela pode ter mudado lá fora. */
    fun recarregarCofre() {
        // Vale a releitura mais nova: uma antiga que termine depois não repõe uma chave já removida (achado na #81).
        val esta = ordemDoCofre.comecar()
        viewModelScope.launch {
            val chaves = withContext(Dispatchers.IO) { d.cofre.chaves() }
            val nivel = withContext(Dispatchers.IO) { d.cofre.nivel() }
            if (ordemDoCofre.aplicar(esta)) _estado.update { it.copy(chaves = chaves, nivel = nivel, travaDeTela = d.cofre.travaDeTela()) }
        }
    }

    /** A ordem das releituras do cofre: a da volta da tela e a de depois de guardar ou remover uma chave. */
    private val ordemDoCofre = OrdemDasLeituras()

    /**
     * Guarda a chave digitada. Se a janela de autenticação venceu, a tela pede
     * a autenticação e chama de novo com [depoisDeAutenticar]; uma segunda
     * recusa do cofre depois da autenticação é falha, não um laço.
     */
    fun salvarChave(provedor: Provedor, depoisDeAutenticar: Boolean = false) {
        val chave = chavesDigitadas[provedor].orEmpty()
        if (chave.isBlank()) return
        viewModelScope.launch {
            when (val guarda = withContext(Dispatchers.IO) { d.cofre.guardar(provedor, chave) }) {
                is Guarda.Guardada -> {
                    // Só sai a chave que foi enviada: a digitada durante a gravação fica (MAEANDR-40).
                    if (chavesDigitadas[provedor] == chave) chavesDigitadas[provedor] = ""
                    avisar(Mensagem.DeRecurso(R.string.chave_guardada))
                    recarregarCofre()
                }
                Guarda.ExigeAutenticacao ->
                    if (depoisDeAutenticar) avisar(Mensagem.DeRecurso(R.string.cofre_falhou)) else eventos.send(Evento.Autenticar(provedor))
                Guarda.SemTravaDeTela -> _estado.update { it.copy(semTrava = true) }
                is Guarda.Falhou -> avisar(Mensagem.DeRecurso(R.string.cofre_nao_gravou, listOf(guarda.motivo)))
                Guarda.ChaveInvalida -> avisar(Mensagem.DeRecurso(R.string.chave_invalida))
            }
        }
    }

    fun removerChave(provedor: Provedor) {
        viewModelScope.launch {
            when (val remocao = withContext(Dispatchers.IO) { d.cofre.apagar(provedor) }) {
                Remocao.Removida -> avisar(Mensagem.DeRecurso(R.string.chave_removida))
                is Remocao.Falhou -> avisar(Mensagem.DeRecurso(R.string.chave_nao_removida, listOf(remocao.motivo)))
            }
            recarregarCofre()
        }
    }

    fun fecharSemTrava() {
        _estado.update { it.copy(semTrava = false) }
    }

    /**
     * `saveSettings`: custos, limite de tempo, tarifas, protocolo e e-mail. O
     * que não é número vira a mensagem de número. Antes de gravar, as três
     * recusas do cliente do web, na ordem dele (`MaestroAiModule.tsx:515-532`):
     * protocolo curto, teto que não é positivo, limite fora da faixa — inclusive
     * o 0, que o núcleo trataria como "limpar", como o backend do web, mas que
     * o formulário recusa ("vazio = sem limite"). O núcleo confere tudo de novo,
     * e as regras que só ele tem (e-mail, teto máximo) vêm com as mensagens dele.
     */
    fun salvarConfiguracoes() {
        if (_estado.value.salvando) return
        val pedido = montarPedido() ?: return avisar(Mensagem.DeRecurso(R.string.erro_numero))
        recusaDoCliente(pedido)?.let { return avisar(Mensagem.Literal(it)) }
        val enviado = Formulario(teto, limiteDeMinutos, taxas.toMap(), protocolo, email)
        _estado.update { it.copy(salvando = true) }
        viewModelScope.launch {
            try {
                when (val resultado = withContext(Dispatchers.IO) { d.configuracoes.salvar(pedido) }) {
                    is Resultado.Ok -> {
                        aplicar(resultado.valor, enviado)
                        avisar(Mensagem.DeRecurso(R.string.configuracoes_salvas))
                    }
                    is Resultado.Recusado -> avisar(Mensagem.Literal(resultado.mensagem))
                }
            } catch (erro: Exception) {
                avisar(Mensagem.DeRecurso(R.string.gravacao_falhou, listOf(motivoDeArmazenamento(erro))))
            } finally {
                _estado.update { it.copy(salvando = false) }
            }
        }
    }

    private fun recusaDoCliente(pedido: PedidoDeConfiguracoes): String? {
        val limite = (pedido.tetoDeMinutos as? Campo.Presente)?.valor
        return when {
            TrimJs.aparar(protocolo).length < 100 -> RepositorioDeConfiguracoes.MENSAGEM_PROTOCOLO_CURTO
            (pedido.tetoDeCustoUsd?.signum() ?: 0) <= 0 -> RepositorioDeConfiguracoes.MENSAGEM_TETO_POSITIVO
            limite != null && (limite < 1 || limite > RepositorioDeConfiguracoes.TETO_DE_MINUTOS) ->
                RepositorioDeConfiguracoes.MENSAGEM_LIMITE_DE_MINUTOS
            else -> null
        }
    }

    private fun montarPedido(): PedidoDeConfiguracoes? {
        // `Number('')` é 0: o teto vazio chega às regras como zero, e a recusa é a do web.
        val tetoLido = numero(teto) ?: return null
        val limite = limiteDeMinutos.trim().let { if (it.isEmpty()) null else it.toIntOrNull() ?: return null }
        val taxasLidas = Provedor.entries.associateWith { agente ->
            val digitadas = taxas[agente] ?: return null
            Custo.Taxas(
                entradaPorMilhao = numero(digitadas.entrada) ?: return null,
                saidaPorMilhao = numero(digitadas.saida) ?: return null,
                requisicoesPorMil = numero(digitadas.busca) ?: return null,
            )
        }
        return PedidoDeConfiguracoes(
            protocolo = protocolo,
            tetoDeCustoUsd = tetoLido,
            tetoDeMinutos = Campo.Presente(limite),
            taxas = taxasLidas,
            emailDeContato = Campo.Presente(email),
        )
    }

    /** `updateRate`: vazio é zero (`Number('')`), e zero cai no padrão pelas regras das tarifas. */
    private fun numero(texto: String): BigDecimal? = texto.trim().let { if (it.isEmpty()) BigDecimal.ZERO else it.toBigDecimalOrNull() }

    /** Chamado pela tela depois da confirmação e da autenticação (decisão 20). */
    fun testar() {
        if (_estado.value.testando) return
        _estado.update { it.copy(testando = true, resultados = emptyList()) }
        viewModelScope.launch {
            try {
                // As tarifas saem das configurações, no Room: o armazenamento que falha é a falha do teste (decisão 25).
                val (taxas, chaves) = try {
                    withContext(Dispatchers.IO) { d.configuracoes.carregar().taxas to d.cofre.chaves() }
                } catch (erro: Exception) {
                    avisar(Mensagem.DeRecurso(R.string.leitura_do_aparelho_falhou, listOf(motivoDeArmazenamento(erro))))
                    return@launch
                }
                val resultados = withContext(Dispatchers.IO) { d.testeDeChaves.testar(taxas, chaves) }
                _estado.update { it.copy(resultados = resultados) }
                val falhas = resultados.count { !it.ok }
                avisar(
                    if (falhas > 0) {
                        Mensagem.DePlural(R.plurals.testar_exigem_atencao, falhas)
                    } else {
                        Mensagem.DeRecurso(R.string.testar_todos_responderam)
                    },
                )
            } finally {
                _estado.update { it.copy(testando = false) }
            }
        }
    }

    fun autenticacaoRecusada() {
        avisar(Mensagem.DeRecurso(R.string.autenticacao_recusada_configuracoes))
    }

    /**
     * A política de privacidade no navegador do sistema: a política de Dados do Usuário do Google Play exige o
     * link também dentro do aplicativo. É a mesma página que o Console da Play aponta; sem navegador, o aviso
     * diz o motivo, como na passagem da tela de links.
     */
    fun abrirPolitica(contexto: Context) {
        val falha = d.navegador.abrir(contexto, URL_DA_POLITICA)
        if (falha != null) avisar(Mensagem.DeRecurso(R.string.privacidade_sem_navegador, listOf(falha)))
    }

    private fun avisar(mensagem: Mensagem) {
        eventos.trySend(Evento.Aviso(mensagem))
    }

    companion object {
        /** A política de privacidade dos aplicativos Android da LCV Ideas & Software, a mesma da ficha da Play. */
        const val URL_DA_POLITICA: String = "https://www.lcv.dev/privacy/"
    }
}
