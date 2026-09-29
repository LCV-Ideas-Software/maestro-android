/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.links

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.protocolo.DecisaoDeRevisao
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.CampoDeTexto
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.Formatos
import dev.lcv.maestro.ui.Legenda
import dev.lcv.maestro.ui.LinhaEscolhivel
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.RotuloDeCampo
import dev.lcv.maestro.ui.Rotulos
import dev.lcv.maestro.ui.Sobrelinha
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.VazioDeResultado
import kotlinx.coroutines.launch

/**
 * Os tipos que o seletor oferece na captura: os do desktop
 * (`EvidenceScreen.tsx:61-69`) e o tipo desconhecido, que é como o provedor de
 * documentos informa uma extensão que ele não conhece — a extensão decide
 * depois ([LinksViewModel.tipoDaCaptura]).
 */
private val TIPOS_DO_SELETOR = arrayOf(
    "text/html", "text/markdown", "text/plain", "application/pdf", "image/png", "image/jpeg", "image/webp", "application/octet-stream",
)

/**
 * Os links auditados do texto da sessão e a revisão de cada um (especificação,
 * seção 2.2; MAEANDR-18): a lista do painel de integridade do desktop, e, no
 * link aberto, o detalhe, as evidências do endereço, a captura do operador,
 * os candidatos de correção e o julgamento.
 */
@Composable
fun LinksScreen(vm: LinksViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val recursos = LocalResources.current
    val contexto = LocalContext.current
    val escopo = rememberCoroutineScope()
    LaunchedEffect(vm) { vm.avisos.collect { avisos.mostrar(it.em(recursos)) } }
    LifecycleResumeEffect(vm) {
        vm.recarregar()
        onPauseOrDispose { }
    }
    // O link aberto é o que o ViewModel fixou ao ler a lista; a tela não escolhe outro por conta própria.
    val aberto = estado.links.firstOrNull { it.linha.linkId == vm.escolhido }
    // O link do pedido é o que o ViewModel guardou ao abrir o seletor, e não o aberto na volta.
    val importar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> vm.importar(uri, contexto.contentResolver) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        when {
            !estado.carregada -> Unit
            !estado.existe -> VazioDeResultado(stringResource(R.string.sessao_nao_encontrada))
            else -> {
                Cabecalho(R.drawable.simbolo_link, stringResource(R.string.links_auditados), estado.titulo)
                Cartao {
                    Legenda(stringResource(R.string.links_do_texto))
                    Legenda(stringResource(R.string.links_fronteira))
                }
                if (estado.links.isEmpty()) {
                    VazioDeResultado(stringResource(R.string.sem_links_auditados), Modifier.testTag(Marcas.SEM_LINKS_AUDITADOS))
                }
                estado.links.forEach { link ->
                    val linha = link.linha
                    val eAberto = linha.linkId == aberto?.linha?.linkId
                    LinhaEscolhivel(
                        aoClicar = { if (!eAberto) vm.escolher(linha.linkId) },
                        marca = Marcas.link(linha.linkId),
                        selecionada = eAberto,
                    ) {
                        // Duas linhas de uma linha só, como as outras listas: a pílula corta o começo de um texto
                        // mais alto que ela. O contexto, que o desktop põe na linha, fica na ficha do link aberto.
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                linha.textoDaAncora ?: linha.urlOriginal,
                                fontWeight = FontWeight.SemiBold,
                                color = Tema.cores.botaoFantasmaTexto,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${Rotulos.classificacao(linha.classificacao)} · " +
                                    stringResource(R.string.link_revisao, Rotulos.revisao(linha.statusDaRevisao)),
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = Tema.cores.textoFraco,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (eAberto) {
                        Detalhe(
                            link = link,
                            vm = vm,
                            trabalhando = estado.trabalhando,
                            emExecucao = estado.emExecucao,
                            aoAbrirNoNavegador = { escopo.launch { vm.abrirNoNavegador(linha, contexto) } },
                            aoImportar = {
                                vm.pedirCaptura(linha)
                                importar.launch(TIPOS_DO_SELETOR)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Detalhe(
    link: LinksViewModel.Link,
    vm: LinksViewModel,
    trabalhando: Boolean,
    emExecucao: Boolean,
    aoAbrirNoNavegador: () -> Unit,
    aoImportar: () -> Unit,
) {
    val linha = link.linha
    Cartao(modifier = Modifier.testTag(Marcas.DETALHE_DO_LINK)) {
        Text(Rotulos.classificacao(linha.classificacao), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Tema.cores.texto)
        Legenda(Rotulos.suporte(linha))
        Ficha(linha)
        Evidencias(link)
        // A captura não mexe nas linhas de link e segue liberada; a revisão e as propostas esperam a sessão parar.
        Captura(vm, trabalhando, aoAbrirNoNavegador, aoImportar)
        if (emExecucao) {
            Legenda(stringResource(R.string.links_em_execucao), Modifier.testTag(Marcas.LINKS_EM_EXECUCAO))
        }
        Candidatos(linha, vm, trabalhando || emExecucao)
        Julgamento(linha, vm, trabalhando || emExecucao)
    }
}

/** A `detail-list` do desktop (`LinkIntegrityPanel.tsx:415-505`). */
@Composable
private fun Ficha(linha: LinhaDeLink) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Campo(R.string.campo_ancora, linha.textoDaAncora ?: stringResource(R.string.sem_ancora))
            Campo(R.string.campo_contexto, linha.textoAoRedor.ifEmpty { stringResource(R.string.nao_preservado) })
            Campo(R.string.campo_original, linha.urlOriginal)
            Campo(R.string.campo_normalizada, linha.urlNormalizada)
            Campo(R.string.campo_final, linha.urlFinal ?: stringResource(R.string.sem_url_final))
            Campo(
                R.string.campo_resposta,
                stringResource(
                    R.string.resposta_http,
                    linha.statusHttp?.toString() ?: stringResource(R.string.sem_resposta),
                    linha.tipoDeConteudo ?: stringResource(R.string.tipo_desconhecido),
                ),
            )
            Campo(R.string.campo_sha256, linha.sha256 ?: stringResource(R.string.nao_calculado))
            Campo(R.string.campo_verificada, Formatos.dataDoEvento(linha.verificadoEm))
            Campo(
                R.string.campo_revisao,
                stringResource(
                    R.string.revisao_e_decisao,
                    Rotulos.revisao(linha.statusDaRevisao),
                    linha.decisaoDeRevisao?.let(Rotulos::decisao) ?: stringResource(R.string.decisao_nao_registrada_ainda),
                ),
            )
            if (linha.mudancasDaNormalizacao.isNotEmpty()) {
                Campo(R.string.normalizacao, linha.mudancasDaNormalizacao.joinToString("\n"))
            }
            if (linha.cadeiaDeRedirecionamento.isNotEmpty()) {
                Campo(R.string.redirecionamentos, linha.cadeiaDeRedirecionamento.joinToString("\n") { "${it.status} · ${it.url}" })
            }
        }
    }
}

@Composable
private fun Campo(rotulo: Int, valor: String) {
    Column {
        RotuloDeCampo(stringResource(rotulo))
        Text(valor, fontSize = 13.sp, color = Tema.cores.texto)
    }
}

/** As evidências guardadas para o endereço do link: a coleta do motor, a passagem ao navegador e os arquivos importados. */
@Composable
private fun Evidencias(link: LinksViewModel.Link) {
    Sobrelinha(stringResource(R.string.evidencias_do_endereco))
    if (link.evidencias.isEmpty()) {
        Legenda(stringResource(R.string.sem_evidencias))
    }
    link.evidencias.forEach { registro ->
        Column(modifier = Modifier.testTag(Marcas.evidencia(registro.id))) {
            Text(
                "${Rotulos.modoDeAcesso(registro.modoDeAcesso)} · ${Rotulos.estadoDaEvidencia(registro.estado)}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Tema.cores.texto,
            )
            Legenda(registro.nomeDoArtefato ?: registro.titulo ?: registro.urlFinal ?: registro.url)
            Legenda(listOfNotNull(Formatos.dataDoEvento(registro.atualizadaEm), registro.sha256?.take(12)).joinToString(" · "))
        }
    }
}

/** A captura do operador (`EvidenceScreen.tsx:325-470, 628-679`), para o endereço deste link. */
@Composable
private fun Captura(vm: LinksViewModel, trabalhando: Boolean, aoAbrirNoNavegador: () -> Unit, aoImportar: () -> Unit) {
    Sobrelinha(stringResource(R.string.captura_do_operador))
    Legenda(stringResource(R.string.captura_explicacao))
    BotaoFantasma(
        stringResource(R.string.abrir_no_navegador),
        aoClicar = aoAbrirNoNavegador,
        icone = R.drawable.simbolo_link,
        habilitado = !trabalhando,
        modifier = Modifier.testTag(Marcas.ABRIR_NO_NAVEGADOR),
    )
    CampoDeTexto(
        valor = vm.notaDaCaptura,
        aoMudar = { vm.notaDaCaptura = it },
        rotulo = stringResource(R.string.campo_nota_de_proveniencia),
        exemplo = stringResource(R.string.nota_de_proveniencia_dica),
        linhas = 2,
        marca = Marcas.NOTA_DA_CAPTURA,
    )
    Legenda(stringResource(R.string.captura_limite))
    BotaoPrimario(
        stringResource(R.string.importar_captura),
        aoClicar = aoImportar,
        icone = R.drawable.simbolo_description,
        habilitado = !trabalhando,
        modifier = Modifier.testTag(Marcas.IMPORTAR_CAPTURA),
    )
}

/** Os candidatos de correção (`LinkIntegrityPanel.tsx:507-568`), só com os dois conectores embutidos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Candidatos(linha: LinhaDeLink, vm: LinksViewModel, trabalhando: Boolean) {
    Sobrelinha(stringResource(R.string.propostas_nao_alteracoes))
    Text(stringResource(R.string.candidatos_de_correcao), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Tema.cores.texto)
    CampoDeTexto(
        valor = vm.consulta,
        aoMudar = { vm.consulta = it },
        rotulo = stringResource(R.string.campo_consulta_de_correcao),
        marca = Marcas.CONSULTA_DE_CORRECAO,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinksViewModel.PROVEDORES_DE_BUSCA.forEach { (id, rotulo) ->
            FilterChip(
                selected = vm.provedor == id,
                onClick = { vm.provedor = id },
                label = { Text(rotulo) },
                modifier = Modifier.testTag(Marcas.provedorDeBusca(id)),
            )
        }
    }
    BotaoFantasma(
        stringResource(R.string.buscar_propostas),
        aoClicar = { vm.proporCorrecoes(linha) },
        icone = R.drawable.simbolo_refresh,
        habilitado = !trabalhando,
        modifier = Modifier.testTag(Marcas.BUSCAR_PROPOSTAS),
    )
    Legenda(stringResource(R.string.propostas_ajuda))
    if (linha.candidatosDeCorrecao.isEmpty()) {
        Legenda(stringResource(R.string.sem_candidatos))
    }
    linha.candidatosDeCorrecao.forEach { candidato ->
        Column(modifier = Modifier.testTag(Marcas.candidato(candidato.candidatoId))) {
            Text(
                "${Rotulos.acaoDeCorrecao(candidato.acao)} · ${candidato.titulo ?: candidato.url ?: stringResource(R.string.sem_url_proposta)}",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Tema.cores.texto,
            )
            candidato.url?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Tema.cores.texto) }
            Legenda(candidato.justificativa)
            val origem = "${candidato.provedor} · ${Formatos.dataDoEvento(candidato.propostoEm)}"
            Legenda(candidato.consulta?.let { stringResource(R.string.candidato_consulta, origem, it) } ?: origem)
        }
    }
}

/** O julgamento (`LinkIntegrityPanel.tsx:570-633`): a decisão explícita, a fundamentação e a última decisão registrada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Julgamento(linha: LinhaDeLink, vm: LinksViewModel, trabalhando: Boolean) {
    Sobrelinha(stringResource(R.string.decisao_humana))
    Text(stringResource(R.string.registrar_julgamento), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Tema.cores.texto)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DecisaoDeRevisao.entries.forEach { cada ->
            BotaoFantasma(
                // Os botões do desktop dizem "Quarentena"; o rótulo longo fica para a decisão registrada.
                if (cada == DecisaoDeRevisao.QUARENTENA) stringResource(R.string.decisao_quarentena) else Rotulos.decisao(cada),
                aoClicar = { vm.decisao = cada },
                habilitado = !trabalhando,
                selecionado = vm.decisao == cada,
                modifier = Modifier.testTag(Marcas.decisao(cada.name.lowercase())),
            )
        }
    }
    CampoDeTexto(
        valor = vm.nota,
        aoMudar = { vm.nota = it },
        rotulo = stringResource(R.string.campo_fundamentacao),
        exemplo = stringResource(R.string.fundamentacao_dica),
        linhas = 3,
        marca = Marcas.NOTA_DA_REVISAO,
    )
    BotaoPrimario(
        stringResource(R.string.registrar_decisao),
        aoClicar = { vm.revisar(linha) },
        icone = R.drawable.simbolo_check_circle,
        habilitado = !trabalhando && vm.decisao != null && vm.nota.isNotBlank(),
        modifier = Modifier.testTag(Marcas.REGISTRAR_DECISAO),
    )
    linha.notaDaRevisao?.let { nota ->
        Column {
            RotuloDeCampo(stringResource(R.string.ultima_decisao))
            Text(nota, fontSize = 13.sp, color = Tema.cores.texto)
            Legenda("${linha.revisadoPor ?: "operador"} · ${linha.revisadoEm?.let { Formatos.dataDoEvento(it) }.orEmpty()}")
        }
    }
}
