/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.links

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.protocolo.DecisaoDeRevisao
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import dev.lcv.maestro.provedores.ColetaCancelada
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import dev.lcv.maestro.ui.Documentos
import dev.lcv.maestro.ui.LeiturasDaTela
import dev.lcv.maestro.ui.Mensagem
import dev.lcv.maestro.ui.OrdemDasLeituras
import dev.lcv.maestro.ui.Rotulos
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Os links auditados do texto da sessão e a revisão de cada um (especificação,
 * seção 2.2; MAEANDR-18; plano do `:app`, emendas A7 e A8): o painel de
 * integridade do desktop (`LinkIntegrityPanel.tsx`) mais a captura do
 * operador da tela de evidências (`EvidenceScreen.tsx`), por link. Nada aqui
 * altera o texto: a revisão, as propostas e as capturas ficam nos registros,
 * e a sessão os lê no portão da auditoria final.
 *
 * O que toca o Room ou a rede roda em `Dispatchers.IO`; a mensagem de recusa
 * do motor (`IntegridadeDeLinks.Falha`) vai no aviso, depois da frase do
 * desktop.
 */
class LinksViewModel(private val d: Dependencias, private val id: String) : ViewModel() {

    /** Um link do texto, com as evidências guardadas para o endereço dele. */
    data class Link(val linha: LinhaDeLink, val evidencias: List<RegistroDeEvidencia>)

    data class Estado(
        val carregada: Boolean = false,
        val existe: Boolean = true,
        val titulo: String = "",
        val links: List<Link> = emptyList(),
        val trabalhando: Boolean = false,
        /** Na fila ou em execução: a revisão e as propostas esperam (decisão 24 do operador, 29/09/2026). */
        val emExecucao: Boolean = false,
        /** O motivo de a sessão ou a lista nunca terem sido lidas (decisão 25 estendida, #80). */
        val falhaDeLeitura: String? = null,
    )

    /**
     * O link aberto. Cada leitura da lista o fixa ([aplicar]): o mesmo, se ainda
     * existe; senão o primeiro, que o desktop também abre sozinho.
     */
    var escolhido by mutableStateOf<String?>(null)
        private set

    // O que se digita no link aberto; trocar de link limpa, como o desktop (`useEffect` sobre `selectedId`).
    var notaDaCaptura by mutableStateOf("")
    var consulta by mutableStateOf("")
    var provedor by mutableStateOf(PROVEDORES_DE_BUSCA.first().first)
    var decisao by mutableStateOf<DecisaoDeRevisao?>(null)
    var nota by mutableStateOf("")

    /** A URL e o hash da linha aberta quando o formulário começou ([escolher]). */
    private var versaoEscolhida: Pair<String, String?>? = null

    fun escolher(linkId: String?) {
        escolhido = linkId
        versaoEscolhida = conteudo.value?.firstOrNull { it.linha.linkId == linkId }?.linha?.let(::versao)
        notaDaCaptura = ""
        consulta = ""
        decisao = null
        nota = ""
    }

    /**
     * O link para o qual o seletor de documentos foi aberto. O seletor do sistema demora, e a lista
     * pode mudar enquanto ele está aberto: o arquivo só entra se esse link ainda é o aberto (achado
     * do Codex na #78). Fica aqui, e não na composição, que é refeita nesse meio-tempo.
     */
    private var capturaPedida: String? = null

    fun pedirCaptura(linha: LinhaDeLink) {
        capturaPedida = linha.linkId
    }

    /** O aviso de uma ação e, se ela gravou, o que a tela limpa depois (o desktop limpa a nota e a decisão). */
    private class Saida(val mensagem: Mensagem, val aoGravar: (() -> Unit)? = null)

    private val conteudo = MutableStateFlow<List<Link>?>(null)

    /** A volta em que a releitura da lista falhou; uma ação ou a volta seguinte relê. */
    @Volatile private var releituraFalhouNaVolta = -1

    /** O motivo da última releitura da lista que falhou; só aparece enquanto nada foi lido. */
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
    ) { linha, lidos, falha, emCurso ->
        Estado(
            carregada = lidos != null || falha != null || linha.falha != null,
            existe = linha.valor != null,
            titulo = linha.valor?.titulo.orEmpty(),
            links = lidos.orEmpty(),
            trabalhando = emCurso,
            emExecucao = Rotulos.emExecucao(linha.valor?.status),
            falhaDeLeitura = linha.falha ?: falha.takeIf { lidos == null },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    /** A ordem das releituras, declarada antes do `init` que começa a primeira. */
    private val ordem = OrdemDasLeituras()

    init {
        // A lista é a do texto que a sessão tem agora: relida quando esse texto muda com a tela aberta,
        // durante a execução, e não só na volta à tela, e também quando a auditoria regrava as linhas
        // ou as evidências do mesmo texto (achados do Codex na #78). Uma auditoria grava link a link:
        // releituras pedidas durante outra viram uma só.
        viewModelScope.launch {
            // O armazenamento que falha nesta observação é o aviso, e ela volta na volta da tela (decisão 25 estendida, #80).
            val texto = d.sessoes.observar(id).map { linha -> linha?.let { it.textoFinal ?: it.textoAtual } }.distinctUntilChanged()
            // A releitura que falhou espera a volta: a gravação de outra sessão nas mesmas tabelas não a refaz (#81).
            leituras.observar(combine(texto, d.links.mudancas()) { _, _ -> }, Unit).conflate().collect {
                if (releituraFalhouNaVolta != leituras.volta.value) reler()
            }
        }
    }

    /** A volta da tela ao primeiro plano; a primeira chamada é a abertura (decisão 25 estendida, #80). */
    fun recarregar() {
        leituras.voltou()
        viewModelScope.launch { reler() }
    }

    /**
     * Relê a lista e a aplica. O armazenamento que falha na releitura mantém a lista que a tela tinha,
     * com o aviso, e não derruba o aplicativo, nem depois de uma ação que já gravou (decisão 25 do
     * operador; achado do Codex na #78).
     */
    private suspend fun reler() {
        // Vale a releitura mais nova que terminou bem (achado do Codex na #78; ver `OrdemDasLeituras`).
        val esta = ordem.comecar()
        val relida = try {
            withContext(Dispatchers.IO) { ler() }
        } catch (erro: Exception) {
            // Sem lista lida, "ela ficou como estava" seria falso: o aviso é o geral, e o motivo fica no lugar (#80).
            val mensagem: (String) -> Mensagem = if (conteudo.value == null) {
                LeiturasDaTela::avisoDeLeitura
            } else {
                { motivo -> Mensagem.DeRecurso(R.string.leitura_falhou, listOf(motivo)) }
            }
            falhaDoConteudo.value = leituras.falhou(erro, mensagem)
            releituraFalhouNaVolta = leituras.volta.value
            return
        }
        releituraFalhouNaVolta = -1
        if (ordem.aplicar(esta)) aplicar(relida)
    }


    /**
     * A lista relida. Se o link aberto sumiu dela (o texto mudou), abre o primeiro, e o que se
     * digitou para o anterior é apagado: a nota e a decisão de um link nunca vão para outro
     * (achado do Codex na #78). O mesmo vale quando a linha aberta volta com outra URL ou outro
     * hash, como depois da auditoria de outra sessão com o mesmo texto: é outro conteúdo a julgar.
     * Linha principal.
     */
    private fun aplicar(links: List<Link>) {
        conteudo.value = links
        val aberto = links.firstOrNull { it.linha.linkId == escolhido }?.linha ?: links.firstOrNull()?.linha
        if (aberto?.linkId != escolhido || aberto?.let(::versao) != versaoEscolhida) escolher(aberto?.linkId)
    }

    /** O que a revisão confere (`urlNormalizadaEsperada` e `sha256Esperado`): o formulário vale só para ela. */
    private fun versao(linha: LinhaDeLink): Pair<String, String?> = linha.urlNormalizada to linha.sha256

    private fun ler(): List<Link> {
        val linhas = d.links.linhas(id)
        val registros = d.evidencias.registrosDe(linhas.flatMap(::enderecos).toSet())
        return linhas.map { linha ->
            val deste = enderecos(linha)
            Link(linha, registros.filter { it.url in deste || it.urlFinal in deste })
        }
    }

    /**
     * "Abrir no navegador" (`open_web_evidence_in_default_browser`): o registro
     * de passagem é montado e validado primeiro, o navegador recebe só a URL
     * validada, e o registro é guardado com o que o disparo disse. Chamada do
     * escopo da tela, que vive com a Activity: [contexto] é o dela, e o
     * trabalho some com ela.
     */
    suspend fun abrirNoNavegador(linha: LinhaDeLink, contexto: Context) {
        if (!comecar()) return
        try {
            val mensagem = try {
                // O registro de passagem é gravado antes de o navegador abrir: depois do `startActivity`, o
                // Android pode parar e matar o aplicativo, e o navegador teria a URL sem o registro que a
                // audita (achado do Codex na #78). Divergência do desktop, que grava uma vez, depois do disparo.
                val passagem = withContext(Dispatchers.IO) {
                    d.importacao.passagem(linha.urlNormalizada) { d.evidencias.existente(it)?.registro }.also { d.evidencias.guardar(it) }
                }
                val falha = d.navegador.abrir(contexto, passagem.registro.url)
                // O registro já existe e o disparo já foi feito: se a anotação do resultado falha, o aviso diz isso, e
                // se o navegador abriu, e não que a passagem não foi registrada (decisão 25 do operador).
                val naoAnotou = try {
                    withContext(Dispatchers.IO) { d.evidencias.guardar(d.importacao.aberta(passagem, falha)) }
                    null
                } catch (erro: Exception) {
                    motivoDeArmazenamento(erro)
                }
                // O desktop dá o mesmo aviso nos dois casos; aqui, sem navegador, o aviso diz o que o registro anotou.
                when {
                    naoAnotou != null && falha != null -> Mensagem.DeRecurso(R.string.passagem_sem_navegador_sem_anotacao, listOf(falha, naoAnotou))
                    naoAnotou != null -> Mensagem.DeRecurso(R.string.passagem_sem_anotacao, listOf(naoAnotou))
                    falha == null -> Mensagem.DeRecurso(R.string.passagem_registrada)
                    else -> Mensagem.DeRecurso(R.string.passagem_sem_navegador, listOf(falha))
                }
            } catch (erro: IntegridadeDeLinks.Falha) {
                Mensagem.DeRecurso(R.string.passagem_falhou, listOf(erro.message.orEmpty()))
            } catch (erro: Exception) {
                Mensagem.DeRecurso(R.string.passagem_falhou, listOf(motivoDeArmazenamento(erro)))
            }
            reler()
            eventos.send(mensagem)
        } finally {
            trabalhando.value = false
        }
    }

    /**
     * O arquivo que o operador salvou, pelo seletor de documentos, como
     * evidência do endereço de [linha]; `null` é o seletor cancelado, e nada
     * muda. A linha de link não muda: só a revisão explícita a libera.
     */
    fun importar(uri: Uri?, resolver: ContentResolver) {
        val pedida = capturaPedida
        capturaPedida = null
        if (uri == null) return
        val linha = conteudo.value?.firstOrNull { it.linha.linkId == pedida }?.linha
        if (pedida == null || pedida != escolhido || linha == null) {
            eventos.trySend(Mensagem.DeRecurso(R.string.captura_link_mudou))
            return
        }
        val notaDoPedido = notaDaCaptura.trim()
        agir {
            when (val leitura = Documentos.ler(resolver, uri, ImportacaoDoOperador.MAX_BYTES)) {
                Documentos.Leitura.AcimaDoTeto -> Saida(Mensagem.DeRecurso(R.string.captura_acima_do_teto))
                Documentos.Leitura.Falhou -> Saida(Mensagem.DeRecurso(R.string.anexo_ilegivel))
                is Documentos.Leitura.Lido -> {
                    val tipo = tipoDaCaptura(leitura.nome, leitura.tipo) ?: return@agir Saida(Mensagem.DeRecurso(R.string.captura_tipo_recusado))
                    val pedido = ImportacaoDoOperador.Pedido(
                        nome = leitura.nome,
                        tipoDeMidia = tipo,
                        bytes = leitura.bytes,
                        url = linha.urlNormalizada,
                        notas = listOfNotNull(notaDoPedido.takeIf { it.isNotEmpty() }),
                    )
                    // A importação consulta a evidência já guardada do endereço (o Room e o corpo em arquivo):
                    // o armazenamento que falha aí também é a falha dela (decisão 25 do operador).
                    val importacao = try {
                        d.importacao.importar(pedido) { d.evidencias.existente(it)?.registro }
                    } catch (erro: Exception) {
                        return@agir Saida(Mensagem.DeRecurso(R.string.captura_falhou, listOf(motivoDeArmazenamento(erro))))
                    }
                    when (importacao) {
                        is ImportacaoDoOperador.Importacao.Recusada -> Saida(Mensagem.DeRecurso(R.string.captura_falhou, listOf(importacao.motivo)))
                        is ImportacaoDoOperador.Importacao.Importada -> try {
                            d.evidencias.guardar(importacao.coleta)
                            Saida(Mensagem.DeRecurso(R.string.captura_importada)) {
                                // Só sai a nota que foi enviada: a digitada durante a importação fica (achado do Codex na #78).
                                if (escolhido == linha.linkId && notaDaCaptura.trim() == notaDoPedido) notaDaCaptura = ""
                            }
                        } catch (erro: Exception) {
                            // O disco que falha é a falha da importação: no desktop, `write_binary_file`
                            // devolve o erro à tela (achado do Codex na #78; decisão 25 do operador).
                            Saida(Mensagem.DeRecurso(R.string.captura_falhou, listOf(motivoDeArmazenamento(erro))))
                        }
                    }
                }
            }
        }
    }

    /** `review_link_integrity` pelo operador, com a decisão e a nota escolhidas, contra a URL e o hash que a tela mostrou. */
    fun revisar(linha: LinhaDeLink) {
        if (bloqueadaPelaExecucao()) return
        val decisaoDoPedido = decisao ?: return
        val notaDoPedido = nota
        agir {
            try {
                IntegridadeDeLinks.revisar(
                    IntegridadeDeLinks.PedidoDeRevisao(
                        linkId = linha.linkId,
                        decisao = decisaoDoPedido,
                        nota = notaDoPedido,
                        revisor = "operator",
                        urlNormalizadaEsperada = linha.urlNormalizada,
                        sha256Esperado = linha.sha256,
                    ),
                    d.links.registroDaTela(id),
                    d.relogio(),
                )
                Saida(Mensagem.DeRecurso(R.string.decisao_registrada)) {
                    // Só sai o que foi enviado: a decisão e a nota digitadas durante a revisão ficam (achado do Codex na #78).
                    if (escolhido == linha.linkId && decisao == decisaoDoPedido && nota == notaDoPedido) {
                        decisao = null
                        nota = ""
                    }
                }
            } catch (erro: IntegridadeDeLinks.Falha) {
                Saida(Mensagem.DeRecurso(R.string.decisao_nao_registrada, listOf(erro.message.orEmpty())))
            } catch (erro: Exception) {
                Saida(Mensagem.DeRecurso(R.string.decisao_nao_registrada, listOf(motivoDeArmazenamento(erro))))
            }
        }
    }

    /**
     * `propose_link_corrections` no Crossref ou no OpenAlex. As propostas só
     * sobrevivem à próxima auditoria numa linha decidida (`preservarRevisao`),
     * e é assim que chegam ao revisor, no pacote do portão (decisão 23 do
     * operador): o aviso diz para decidir o link.
     */
    fun proporCorrecoes(linha: LinhaDeLink) {
        if (bloqueadaPelaExecucao() || !comecar()) return
        val pedido = IntegridadeDeLinks.PedidoDeCorrecao(linha.linkId, provedor, consulta.trim().takeIf { it.isNotEmpty() }, LIMITE_DE_PROPOSTAS)
        viewModelScope.launch {
            try {
                // Montar a busca já lê o Room (o e-mail de contato do agente): a falha de armazenamento é a da busca.
                val busca = try {
                    withContext(Dispatchers.IO) { d.busca() }
                } catch (erro: Exception) {
                    eventos.send(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(motivoDeArmazenamento(erro))))
                    return@launch
                }
                // Sair da tela cancela o escopo, mas não a busca, que bloqueia no HTTP: o vigia chama o
                // cancelamento dela, e nada é gravado depois (achado do Codex na #78). Como na auditoria
                // da `Fabrica`, o vigia também fecha a busca que terminou.
                val vigia = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        busca.cancelar()
                    }
                }
                val saida = try {
                    withContext(Dispatchers.IO) {
                        // A resposta HTTP pode ter chegado antes de a tela sair, e aí não há chamada para o
                        // vigia cancelar: a linha só é gravada com esta corrotina viva (achado do Codex na #78).
                        // O diário (`anotar`) não é barrado: gravada a linha, a entrada dele tem de acompanhá-la.
                        val viva = coroutineContext.job
                        val base = d.links.registroDaTela(id)
                        val registro = object : IntegridadeDeLinks.RegistroDeLinks by base {
                            override fun salvar(linha: LinhaDeLink) {
                                viva.ensureActive()
                                base.salvar(linha)
                            }
                        }
                        val saida = try {
                            val proposta = IntegridadeDeLinks.proporCorrecoes(pedido, registro, busca.buscador, d.relogio())
                            Saida(Mensagem.DePlural(R.plurals.propostas_registradas, proposta.candidatosDeCorrecao.size))
                        } catch (erro: IntegridadeDeLinks.Falha) {
                            Saida(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(erro.message.orEmpty())))
                        } catch (erro: ColetaCancelada) {
                            // Só acontece com a tela saindo: o escopo já foi cancelado, e o aviso não sai.
                            Saida(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(erro.message.orEmpty())))
                        } catch (erro: Exception) {
                            // A busca guarda cada resultado como evidência e o motor grava as propostas na linha:
                            // o armazenamento que falha é a falha dela (decisão 25 do operador).
                            Saida(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(motivoDeArmazenamento(erro))))
                        }
                        saida
                    }
                } finally {
                    vigia.cancel()
                }
                reler()
                eventos.send(saida.mensagem)
            } finally {
                trabalhando.value = false
            }
        }
    }

    /**
     * Com a sessão na fila ou em execução, a auditoria dela regrava as mesmas linhas e guarda o que
     * leu: uma decisão ou uma proposta agora poderia se perder, ou não chegar ao revisor (achado do
     * Codex na #78; decisão 24 do operador, 29/09/2026). A tela já desliga os botões; isto é a trava.
     */
    private fun bloqueadaPelaExecucao(): Boolean {
        if (!estado.value.emExecucao) return false
        eventos.trySend(Mensagem.DeRecurso(R.string.links_em_execucao))
        return true
    }

    private fun comecar(): Boolean {
        if (trabalhando.value) return false
        trabalhando.value = true
        // Uma ação é um pedido novo: a releitura dela que falha é avisada mesmo que outra já tenha sido nesta volta (#80).
        leituras.pedido()
        return true
    }

    /** Uma ação por vez: [acao] roda em `Dispatchers.IO`, a lista é relida, a tela limpa o que gravou e o aviso sai. */
    private fun agir(acao: () -> Saida) {
        if (!comecar()) return
        viewModelScope.launch {
            try {
                val saida = withContext(Dispatchers.IO) { acao() }
                saida.aoGravar?.invoke()
                reler()
                eventos.send(saida.mensagem)
            } finally {
                trabalhando.value = false
            }
        }
    }

    companion object {
        /** O `limit: 8` do painel do desktop. */
        const val LIMITE_DE_PROPOSTAS = 8

        /** Os dois conectores embutidos (especificação, seção 11: os configuráveis não vêm na v1). */
        val PROVEDORES_DE_BUSCA: List<Pair<String, String>> = listOf("crossref" to "Crossref", "openalex" to "OpenAlex")

        /** Os endereços de um link sob os quais as evidências dele são guardadas: o normalizado e o final. */
        fun enderecos(linha: LinhaDeLink): Set<String> = setOfNotNull(linha.urlNormalizada, linha.urlFinal)

        /**
         * `captureMediaType` do desktop (`EvidenceScreen.tsx:127-133`): o tipo
         * informado decide, e só os da lista passam; sem tipo, a extensão do
         * nome. O provedor de documentos do Android informa
         * `application/octet-stream` quando não conhece a extensão — o
         * `File.type` vazio do navegador —, e conta como sem tipo.
         */
        fun tipoDaCaptura(nome: String, tipo: String?): String? {
            val informado = tipo?.lowercase(Locale.ROOT)?.takeUnless { it.isEmpty() || it == TIPO_DESCONHECIDO }
            if (informado != null) return informado.takeIf { it in TIPOS_DA_CAPTURA }
            val minusculo = nome.lowercase(Locale.ROOT)
            return EXTENSOES_DA_CAPTURA.entries.firstOrNull { minusculo.endsWith(it.key) }?.value
        }

        private const val TIPO_DESCONHECIDO = "application/octet-stream"

        /** `acceptedCaptureMimeTypes` (`EvidenceScreen.tsx:61-69`). */
        private val TIPOS_DA_CAPTURA = setOf(
            "text/html", "text/markdown", "text/plain", "application/pdf", "image/png", "image/jpeg", "image/webp",
        )

        /** `acceptedCaptureExtensions` com `captureMimeByExtension` (`EvidenceScreen.tsx:49-81`), na ordem do desktop. */
        private val EXTENSOES_DA_CAPTURA: Map<String, String> = linkedMapOf(
            ".html" to "text/html",
            ".htm" to "text/html",
            ".md" to "text/markdown",
            ".markdown" to "text/markdown",
            ".pdf" to "application/pdf",
            ".png" to "image/png",
            ".jpg" to "image/jpeg",
            ".jpeg" to "image/jpeg",
            ".webp" to "image/webp",
            ".txt" to "text/plain",
        )
    }
}
