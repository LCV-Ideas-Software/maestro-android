/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.sessao

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import dev.lcv.maestro.sessao.DetalheDoArtefato
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.EventoDaSessao
import dev.lcv.maestro.sessao.ProjecaoDaSessao
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.CampoDeTexto
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.CartaoDeMetrica
import dev.lcv.maestro.ui.Diff
import dev.lcv.maestro.ui.Formatos
import dev.lcv.maestro.ui.Legenda
import dev.lcv.maestro.ui.LinhaDeMetricas
import dev.lcv.maestro.ui.LinhaEscolhivel
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.Pilula
import dev.lcv.maestro.ui.RotuloDeCampo
import dev.lcv.maestro.ui.Rotulos
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.TextoPreformatado
import dev.lcv.maestro.ui.VazioDeResultado
import dev.lcv.maestro.ui.rememberAutenticacaoDaTela
import dev.lcv.maestro.ui.textofinal.TextoFinalViewModel

/** As cinco abas dos autos, com os rótulos e os ícones do web. */
private enum class Aba(val rotulo: Int, val icone: Int, val nome: String) {
    TEXTO(R.string.aba_texto, R.drawable.simbolo_description, "texto"),
    DIFF(R.string.aba_diff, R.drawable.simbolo_compare_arrows, "diff"),
    RELATORIO(R.string.aba_relatorio, R.drawable.simbolo_warning, "relatorio"),
    LINKS(R.string.aba_links, R.drawable.simbolo_link, "links"),
    METADADOS(R.string.aba_metadados, R.drawable.simbolo_smart_toy, "metadados"),
}

/** Quantos eventos o **Rastreamento** mostra antes de expandir (`events.slice(-8)`). */
private const val EVENTOS_VISIVEIS = 8

@Composable
fun SessaoScreen(vm: SessaoViewModel, aoAbrirTextoFinal: () -> Unit, aoAbrirAnexos: () -> Unit, aoAbrirLinks: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val autenticacao = rememberAutenticacaoDaTela()
    val recursos = LocalResources.current

    LifecycleResumeEffect(vm) {
        vm.recarregar()
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) { vm.avisos.collect { avisos.mostrar(it.em(recursos)) } }

    if (vm.dialogoAberto) {
        DialogoDeRetomada(vm, estado, autenticacao.emCurso) {
            val recusa = vm.conferirRetomada()
            if (recusa != null) {
                avisos.mostrar(recusa.em(recursos))
            } else {
                autenticacao.pedir(aoAutenticar = vm::retomar, aoRecusar = vm::autenticacaoRecusada)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        val sessao = estado.sessao
        val falhaDeLeitura = estado.falhaDeLeitura
        when {
            !estado.carregada -> Unit
            // A sessão que nunca foi lida não é "não encontrada": o motivo fica no lugar (decisão 25 estendida, #80).
            sessao == null && falhaDeLeitura != null ->
                VazioDeResultado(stringResource(R.string.tela_sem_leitura, falhaDeLeitura), Modifier.testTag(Marcas.LEITURA_FALHOU))
            sessao == null -> VazioDeResultado(stringResource(R.string.sessao_nao_encontrada))
            else -> {
                Cabecalho(R.drawable.simbolo_smart_toy, stringResource(R.string.secao_sessao), sessao.titulo)
                Acoes(sessao, estado.trabalhando, aoCancelar = vm::cancelar, aoRetomar = vm::abrirRetomada)
                Metricas(sessao)
                Rastreamento(sessao)
                Autos(estado, vm::escolherArtefato, aoAbrirLinks)
                TextoDaSessao(sessao, aoAbrirTextoFinal, aoReportar = { contexto, assunto, corpo -> vm.reportarConteudo(contexto, assunto, corpo) })
                Erro(sessao, estado.ultimaParada)
                // Os anexos da sessão, com o manifesto de citações, e os links auditados do texto (seção 2.2, MAEANDR-18).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotaoFantasma(
                        stringResource(R.string.abrir_anexos),
                        aoClicar = aoAbrirAnexos,
                        icone = R.drawable.simbolo_description,
                        modifier = Modifier.testTag(Marcas.ABRIR_ANEXOS),
                    )
                    BotaoFantasma(
                        stringResource(R.string.abrir_links),
                        aoClicar = aoAbrirLinks,
                        icone = R.drawable.simbolo_link,
                        modifier = Modifier.testTag(Marcas.ABRIR_LINKS),
                    )
                }
            }
        }
    }
}

/** `isRunning` → **Cancelar**; `isResumable` → **Retomar**. Não há **Atualizar**: a tela observa o banco. */
@Composable
private fun Acoes(sessao: ProjecaoDaSessao, trabalhando: Boolean, aoCancelar: () -> Unit, aoRetomar: () -> Unit) {
    val emExecucao = Rotulos.emExecucao(sessao.status)
    val retomavel = Rotulos.retomavel(sessao.status, sessao.textoFinal)
    if (!emExecucao && !retomavel) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (emExecucao) {
            BotaoFantasma(
                stringResource(R.string.acao_cancelar),
                aoCancelar,
                modifier = Modifier.testTag(Marcas.CANCELAR),
                icone = R.drawable.simbolo_block,
                carregando = trabalhando,
            )
        }
        if (retomavel) {
            BotaoFantasma(
                stringResource(R.string.acao_retomar),
                aoRetomar,
                modifier = Modifier.testTag(Marcas.RETOMAR),
                icone = R.drawable.simbolo_play_arrow,
                carregando = trabalhando,
            )
        }
    }
}

/** `activeAgent`: o agente do último evento `running`, ou o autor atual. */
private fun agenteAtivo(sessao: ProjecaoDaSessao): String? {
    val ultimo = sessao.eventos.lastOrNull()
    return if (ultimo?.status == EventoDaSessao.RODANDO) ultimo.agente?.agente else sessao.autorAtual
}

@Composable
private fun Metricas(sessao: ProjecaoDaSessao) {
    LinhaDeMetricas {
        CartaoDeMetrica(stringResource(R.string.metrica_sessao), Rotulos.status(sessao.status), Marcas.METRICA_SESSAO, Modifier.weight(1f))
        CartaoDeMetrica(
            stringResource(R.string.metrica_com_o_trabalho),
            Rotulos.agente(agenteAtivo(sessao)),
            Marcas.METRICA_COM_O_TRABALHO,
            Modifier.weight(1f),
        )
    }
    CartaoDeMetrica(
        stringResource(R.string.metrica_custo_acumulado),
        stringResource(
            R.string.metrica_custo_valor,
            Custo.paraExibir(sessao.custoObservadoUsd).toPlainString(),
            Formatos.usd(sessao.tetoDeCustoUsd, 2),
        ),
        Marcas.METRICA_CUSTO,
    )
}

@Composable
private fun Rastreamento(sessao: ProjecaoDaSessao) {
    var todos by rememberSaveable { mutableStateOf(false) }
    Cartao {
        Cabecalho(
            if (sessao.status == Estados.CONVERGIDA) R.drawable.simbolo_check_circle else R.drawable.simbolo_warning,
            stringResource(R.string.rastreamento),
            stringResource(R.string.agente_ativo, Rotulos.agente(agenteAtivo(sessao))),
        )
        if (sessao.eventos.isEmpty()) {
            VazioDeResultado(stringResource(R.string.sem_eventos))
        } else {
            val visiveis = if (todos) sessao.eventos else sessao.eventos.takeLast(EVENTOS_VISIVEIS)
            Column(modifier = Modifier.testTag(Marcas.EVENTOS), verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
                visiveis.forEach { evento ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Pilula(evento.status)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(Rotulos.agente(evento.agente?.agente), fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                            Legenda(
                                listOfNotNull(
                                    Formatos.dataDoEvento(evento.em),
                                    evento.mensagem,
                                    evento.custoUsd?.let { stringResource(R.string.evento_custo, Formatos.usd(it, 4)) },
                                ).joinToString(" · "),
                            )
                        }
                    }
                }
            }
            if (sessao.eventos.size > EVENTOS_VISIVEIS) {
                TextButton(onClick = { todos = !todos }, modifier = Modifier.testTag(Marcas.MOSTRAR_EVENTOS)) {
                    Text(stringResource(if (todos) R.string.mostrar_ultimos_eventos else R.string.mostrar_todos_os_eventos))
                }
            }
        }
    }
}

/** O vazio dos autos; a leitura que falhou mostra o motivo no lugar (decisão 25 estendida, #80). */
@Composable
private fun VazioDosAutos(falha: String?, @StringRes vazio: Int) {
    if (falha != null) {
        VazioDeResultado(stringResource(R.string.tela_sem_leitura, falha), Modifier.testTag(Marcas.FALHA_DOS_AUTOS))
    } else {
        VazioDeResultado(stringResource(vazio))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Autos(estado: SessaoViewModel.Estado, aoEscolher: (String) -> Unit, aoAbrirLinks: () -> Unit) {
    var aba by rememberSaveable { mutableStateOf(Aba.TEXTO) }
    Cartao {
        Cabecalho(R.drawable.simbolo_description, stringResource(R.string.autos), stringResource(R.string.cadeia_viva))
        if (estado.artefatos.isEmpty()) {
            VazioDosAutos(estado.falhaDosAutos, R.string.sem_artefatos)
            return@Cartao
        }
        val escolhido = estado.detalhe?.resumo?.id
        Column(verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
            estado.artefatos.forEach { artefato ->
                LinhaEscolhivel(
                    aoClicar = { aoEscolher(artefato.id) },
                    marca = Marcas.artefato(artefato.id),
                    selecionada = artefato.id == escolhido,
                ) {
                    Column {
                        Text(
                            stringResource(R.string.artefato_titulo, artefato.ciclo, artefato.turno, Rotulos.agente(artefato.agente)),
                            color = Tema.cores.botaoFantasmaTexto,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Legenda(
                            listOfNotNull(
                                artefato.papel,
                                artefato.status,
                                Formatos.bytes(artefato.bytesDoConteudo),
                                artefato.linksInvalidos.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.artefato_links_invalidos, it, it) },
                            ).joinToString(" · "),
                        )
                    }
                }
            }
        }
        val detalhe = estado.detalhe
        if (detalhe == null) {
            VazioDosAutos(estado.falhaDosAutos, R.string.selecione_artefato)
            return@Cartao
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Aba.entries.forEach { cada ->
                val modificador = Modifier.testTag(Marcas.aba(cada.nome))
                if (cada == aba) {
                    BotaoPrimario(stringResource(cada.rotulo), { aba = cada }, modificador, icone = cada.icone)
                } else {
                    BotaoFantasma(stringResource(cada.rotulo), { aba = cada }, modificador, icone = cada.icone)
                }
            }
        }
        Column(modifier = Modifier.testTag(Marcas.CONTEUDO_DA_ABA)) { ConteudoDaAba(aba, detalhe, aoAbrirLinks) }
    }
}

@Composable
private fun ConteudoDaAba(aba: Aba, detalhe: DetalheDoArtefato, aoAbrirLinks: () -> Unit) {
    when (aba) {
        Aba.TEXTO -> SelectionContainer { TextoPreformatado(detalhe.conteudoMd) }
        Aba.DIFF -> SelectionContainer {
            TextoPreformatado(
                Diff.texto(Diff.simples(detalhe.conteudoAnteriorMd, detalhe.conteudoMd)).ifEmpty { stringResource(R.string.primeiro_artefato) },
            )
        }
        Aba.RELATORIO -> SelectionContainer { TextoPreformatado(detalhe.relatorioDeRevisao.ifEmpty { "{}" }) }
        // A deliberação do aparelho não audita links por artefato: a auditoria é da sessão, no portão
        // da auditoria final, e a aba leva à tela dela (a lista por artefato ficaria sempre vazia).
        Aba.LINKS -> Column(verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
            Legenda(stringResource(R.string.links_nos_autos))
            BotaoPrimario(
                stringResource(R.string.abrir_links_auditados),
                aoClicar = aoAbrirLinks,
                icone = R.drawable.simbolo_link,
                modifier = Modifier.testTag(Marcas.ABRIR_LINKS_DOS_AUTOS),
            )
        }
        Aba.METADADOS -> SelectionContainer { TextoPreformatado(detalhe.resumo.metadadosJson()) }
    }
}

/**
 * "Texto final" quando convergiu, senão "Texto atual" (`final_text || current_text`), no `<pre>` do web.
 * No lugar do **Criar Post** do web, o texto liberado abre a tela da seção 4.4, formatado e exportável.
 */
@Composable
private fun TextoDaSessao(sessao: ProjecaoDaSessao, aoAbrirTextoFinal: () -> Unit, aoReportar: (Context, String, String) -> Unit) {
    val convergiu = sessao.status == Estados.CONVERGIDA
    val liberado = TextoFinalViewModel.liberado(sessao.status, sessao.textoFinal) != null
    val contexto = LocalContext.current
    val assunto = stringResource(R.string.reportar_assunto, sessao.id)
    val corpo = stringResource(R.string.reportar_corpo, sessao.id, sessao.titulo)
    Cartao {
        // Política de Conteúdo Gerado por IA do Google Play (seção 9): reportar conteúdo ofensivo sem sair do
        // aplicativo; o relato vai por e-mail, pelo aplicativo de e-mail do aparelho.
        BotaoFantasma(
            stringResource(R.string.acao_reportar_conteudo),
            aoClicar = { aoReportar(contexto, assunto, corpo) },
            icone = R.drawable.simbolo_warning,
            modifier = Modifier.testTag(Marcas.REPORTAR_CONTEUDO),
        )
        Cabecalho(
            if (convergiu) R.drawable.simbolo_check_circle else R.drawable.simbolo_description,
            stringResource(R.string.secao_texto),
            stringResource(if (convergiu) R.string.texto_final else R.string.texto_atual),
        )
        if (liberado) {
            BotaoPrimario(
                stringResource(R.string.abrir_texto_final),
                aoClicar = aoAbrirTextoFinal,
                icone = R.drawable.simbolo_description,
                modifier = Modifier.testTag(Marcas.ABRIR_TEXTO_FINAL),
            )
        }
        val texto = sessao.textoFinal?.takeIf { it.isNotEmpty() } ?: sessao.textoAtual
        SelectionContainer {
            TextoPreformatado(
                texto.ifEmpty { stringResource(R.string.sem_texto) },
                modifier = Modifier.testTag(Marcas.TEXTO_DA_SESSAO),
            )
        }
    }
}

/** O cartão **Erro operacional** do web, com o rótulo da parada e o aviso da decisão 16 quando cabem. */
@Composable
private fun Erro(sessao: ProjecaoDaSessao, ultimaParada: String?) {
    val erro = sessao.erro ?: return
    Cartao(borda = Tema.cores.erroBorda) {
        Text(stringResource(R.string.erro_operacional), fontWeight = FontWeight.Bold, color = Tema.cores.texto)
        Text(erro, modifier = Modifier.testTag(Marcas.ERRO_OPERACIONAL), color = Tema.cores.texto)
        if (ultimaParada != null) {
            Legenda(stringResource(R.string.parada_pelo_sistema, ultimaParada), modifier = Modifier.testTag(Marcas.PARADA_PELO_SISTEMA))
        }
        if (SessaoViewModel.chamadaIndeterminada(erro)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.simbolo_warning), contentDescription = null, tint = Tema.cores.erro, modifier = Modifier.size(18.dp))
                Legenda(stringResource(R.string.chamada_indeterminada_aviso), modifier = Modifier.testTag(Marcas.CHAMADA_INDETERMINADA))
            }
        }
    }
}

/**
 * A retomada do web (líder e colegiado) num diálogo, com os padrões da linha;
 * numa pausa por custo, o teto novo, obrigatório (revisão cruzada de
 * 28/09/2026, emenda A9 e ponto C7: retomar com o mesmo teto pausaria de novo).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogoDeRetomada(vm: SessaoViewModel, estado: SessaoViewModel.Estado, autenticando: Boolean, aoConfirmar: () -> Unit) {
    val sessao = estado.sessao ?: return
    AlertDialog(
        onDismissRequest = vm::fecharRetomada,
        title = { Text(stringResource(R.string.retomar_titulo)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RotuloDeCampo(stringResource(R.string.retomar_lider))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Provedor.entries.forEach { agente ->
                        FilterChip(
                            selected = vm.lider == agente,
                            onClick = { vm.escolherLider(agente) },
                            label = { Text(agente.rotulo) },
                            enabled = agente in estado.prontos,
                            modifier = Modifier.testTag(Marcas.lider(agente)),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Tema.cores.acentoSuave,
                                selectedLabelColor = Tema.cores.acento,
                            ),
                        )
                    }
                }
                RotuloDeCampo(stringResource(R.string.retomar_painel))
                Provedor.entries.forEach { agente ->
                    val pronto = agente in estado.prontos
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(agente.rotulo, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                            Legenda(stringResource(if (pronto) R.string.agente_pronto else R.string.agente_configure))
                        }
                        Checkbox(
                            checked = agente in vm.painel,
                            onCheckedChange = { vm.alternarNoPainel(agente) },
                            enabled = pronto,
                            modifier = Modifier.testTag(Marcas.painel(agente)),
                            colors = CheckboxDefaults.colors(checkedColor = Tema.cores.acento),
                        )
                    }
                }
                if (sessao.status == Estados.LIMITE_DE_CUSTO) {
                    CampoDeTexto(
                        vm.novoTeto,
                        { vm.novoTeto = it },
                        stringResource(R.string.retomar_novo_teto),
                        teclado = KeyboardType.Decimal,
                        ajuda = stringResource(
                            R.string.retomar_novo_teto_ajuda,
                            Formatos.usd(sessao.tetoDeCustoUsd, 2),
                            Custo.paraExibir(sessao.custoObservadoUsd).toPlainString(),
                        ),
                        marca = Marcas.NOVO_TETO,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = aoConfirmar, enabled = !autenticando, modifier = Modifier.testTag(Marcas.CONFIRMAR_RETOMADA)) {
                Text(stringResource(R.string.acao_retomar))
            }
        },
        dismissButton = { TextButton(onClick = vm::fecharRetomada) { Text(stringResource(R.string.acao_fechar)) } },
    )
}
