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
import dev.lcv.maestro.ui.Documentos
import dev.lcv.maestro.ui.Mensagem
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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

    fun escolher(linkId: String?) {
        escolhido = linkId
        notaDaCaptura = ""
        consulta = ""
        decisao = null
        nota = ""
    }

    /** O aviso de uma ação e, se ela gravou, o que a tela limpa depois (o desktop limpa a nota e a decisão). */
    private class Saida(val mensagem: Mensagem, val aoGravar: (() -> Unit)? = null)

    private val conteudo = MutableStateFlow<List<Link>?>(null)
    private val trabalhando = MutableStateFlow(false)
    private val eventos = Channel<Mensagem>(Channel.BUFFERED)
    val avisos: Flow<Mensagem> = eventos.receiveAsFlow()

    val estado: StateFlow<Estado> = combine(d.sessoes.observar(id), conteudo, trabalhando) { linha, lidos, emCurso ->
        Estado(
            carregada = lidos != null,
            existe = linha != null,
            titulo = linha?.titulo.orEmpty(),
            links = lidos.orEmpty(),
            trabalhando = emCurso,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Estado())

    init {
        // A lista é a do texto que a sessão tem agora: relida quando esse texto muda com a tela aberta,
        // durante a execução, e não só na volta à tela (achado do Codex na #78).
        viewModelScope.launch {
            d.sessoes.observar(id).map { linha -> linha?.let { it.textoFinal ?: it.textoAtual } }.distinctUntilChanged().collect {
                aplicar(withContext(Dispatchers.IO) { ler() })
            }
        }
    }

    fun recarregar() {
        viewModelScope.launch { aplicar(withContext(Dispatchers.IO) { ler() }) }
    }

    /**
     * A lista relida. Se o link aberto sumiu dela (o texto mudou), abre o primeiro, e o que se
     * digitou para o anterior é apagado: a nota e a decisão de um link nunca vão para outro
     * (achado do Codex na #78). Linha principal.
     */
    private fun aplicar(links: List<Link>) {
        conteudo.value = links
        val aberto = links.firstOrNull { it.linha.linkId == escolhido }?.linha?.linkId ?: links.firstOrNull()?.linha?.linkId
        if (aberto != escolhido) escolher(aberto)
    }

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
                val passagem = withContext(Dispatchers.IO) {
                    d.importacao.passagem(linha.urlNormalizada) { d.evidencias.existente(it)?.registro }
                }
                val falha = d.navegador.abrir(contexto, passagem.registro.url)
                val links = withContext(Dispatchers.IO) {
                    d.evidencias.guardar(d.importacao.aberta(passagem, falha))
                    ler()
                }
                aplicar(links)
                // O desktop dá o mesmo aviso nos dois casos; aqui, sem navegador, o aviso diz o que o registro anotou.
                if (falha == null) Mensagem.DeRecurso(R.string.passagem_registrada) else Mensagem.DeRecurso(R.string.passagem_sem_navegador, listOf(falha))
            } catch (erro: IntegridadeDeLinks.Falha) {
                Mensagem.DeRecurso(R.string.passagem_falhou, listOf(erro.message.orEmpty()))
            }
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
    fun importar(linha: LinhaDeLink, uri: Uri?, resolver: ContentResolver) {
        if (uri == null) return
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
                    when (val importacao = d.importacao.importar(pedido) { d.evidencias.existente(it)?.registro }) {
                        is ImportacaoDoOperador.Importacao.Recusada -> Saida(Mensagem.DeRecurso(R.string.captura_falhou, listOf(importacao.motivo)))
                        is ImportacaoDoOperador.Importacao.Importada -> {
                            d.evidencias.guardar(importacao.coleta)
                            Saida(Mensagem.DeRecurso(R.string.captura_importada)) { notaDaCaptura = "" }
                        }
                    }
                }
            }
        }
    }

    /** `review_link_integrity` pelo operador, com a decisão e a nota escolhidas, contra a URL e o hash que a tela mostrou. */
    fun revisar(linha: LinhaDeLink) {
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
                    d.links.registro(id),
                    d.relogio(),
                )
                Saida(Mensagem.DeRecurso(R.string.decisao_registrada)) {
                    decisao = null
                    nota = ""
                }
            } catch (erro: IntegridadeDeLinks.Falha) {
                Saida(Mensagem.DeRecurso(R.string.decisao_nao_registrada, listOf(erro.message.orEmpty())))
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
        if (!comecar()) return
        val pedido = IntegridadeDeLinks.PedidoDeCorrecao(linha.linkId, provedor, consulta.trim().takeIf { it.isNotEmpty() }, LIMITE_DE_PROPOSTAS)
        viewModelScope.launch {
            try {
                val busca = withContext(Dispatchers.IO) { d.busca() }
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
                val (saida, links) = try {
                    withContext(Dispatchers.IO) {
                        val saida = try {
                            val proposta = IntegridadeDeLinks.proporCorrecoes(pedido, d.links.registro(id), busca.buscador, d.relogio())
                            Saida(Mensagem.DePlural(R.plurals.propostas_registradas, proposta.candidatosDeCorrecao.size))
                        } catch (erro: IntegridadeDeLinks.Falha) {
                            Saida(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(erro.message.orEmpty())))
                        } catch (erro: ColetaCancelada) {
                            // Só acontece com a tela saindo: o escopo já foi cancelado, e o aviso não sai.
                            Saida(Mensagem.DeRecurso(R.string.propostas_falharam, listOf(erro.message.orEmpty())))
                        }
                        saida to ler()
                    }
                } finally {
                    vigia.cancel()
                }
                aplicar(links)
                eventos.send(saida.mensagem)
            } finally {
                trabalhando.value = false
            }
        }
    }

    private fun comecar(): Boolean {
        if (trabalhando.value) return false
        trabalhando.value = true
        return true
    }

    /** Uma ação por vez: [acao] roda em `Dispatchers.IO`, a lista é relida, a tela limpa o que gravou e o aviso sai. */
    private fun agir(acao: () -> Saida) {
        if (!comecar()) return
        viewModelScope.launch {
            try {
                val (saida, links) = withContext(Dispatchers.IO) { acao() to ler() }
                saida.aoGravar?.invoke()
                aplicar(links)
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
