/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.sessoes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo
import dev.lcv.maestro.sessao.Orcamento
import dev.lcv.maestro.ui.AvisoDeOrcamento
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.CampoDeTexto
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.CartaoDeMetrica
import dev.lcv.maestro.ui.Formatos
import dev.lcv.maestro.ui.Legenda
import dev.lcv.maestro.ui.LinhaDeMetricas
import dev.lcv.maestro.ui.LinhaEscolhivel
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.RotuloDeCampo
import dev.lcv.maestro.ui.Rotulos
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.VazioDeResultado
import dev.lcv.maestro.ui.anexos.AnexosViewModel
import dev.lcv.maestro.ui.rememberAutenticacaoDaTela
import java.math.BigDecimal

/**
 * A tela inicial. O caminho de **Iniciar sessão** é o do web com as duas
 * etapas que só existem no Android, nesta ordem (especificação, seções 4.1 e
 * 6.2): as validações do web; a permissão de notificações, pedida com a
 * explicação uma vez por visita à tela e seguida qualquer que seja a resposta
 * (nada essencial se perde sem ela); a autenticação, sem a qual nada começa;
 * e só então a gravação e o enfileiramento.
 */
@Composable
fun SessoesScreen(vm: SessoesViewModel, aoAbrirSessao: (String) -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val autenticacao = rememberAutenticacaoDaTela()
    val contexto = LocalContext.current
    val recursos = LocalResources.current
    var perguntouNotificacoes by rememberSaveable { mutableStateOf(false) }
    var mostrarRazao by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(vm) {
        vm.recarregar()
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) {
        vm.avisos.collect { evento ->
            when (evento) {
                is SessoesViewModel.Evento.Aviso -> avisos.mostrar(evento.mensagem.em(recursos))
                is SessoesViewModel.Evento.Aberta -> aoAbrirSessao(evento.id)
            }
        }
    }

    fun autenticarEIniciar() {
        autenticacao.pedir(aoAutenticar = vm::iniciar, aoRecusar = vm::autenticacaoRecusada)
    }

    val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { autenticarEIniciar() }

    fun aoIniciar() {
        val recusa = vm.conferirInicio()
        when {
            recusa != null -> avisos.mostrar(recusa.em(recursos))
            !perguntouNotificacoes &&
                contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                mostrarRazao = true
            else -> autenticarEIniciar()
        }
    }

    if (mostrarRazao) {
        AlertDialog(
            onDismissRequest = { mostrarRazao = false },
            title = { Text(stringResource(R.string.notificacoes)) },
            text = { Text(stringResource(R.string.notificacoes_razao), modifier = Modifier.testTag(Marcas.RAZAO_DAS_NOTIFICACOES)) },
            confirmButton = {
                TextButton(onClick = {
                    mostrarRazao = false
                    perguntouNotificacoes = true
                    permissao.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.notificacoes_permitir)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    mostrarRazao = false
                    perguntouNotificacoes = true
                    autenticarEIniciar()
                }) { Text(stringResource(R.string.notificacoes_seguir_sem)) }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        Metricas(estado)
        Cabecalho(R.drawable.simbolo_smart_toy, stringResource(R.string.secao_operacional), stringResource(R.string.secao_sessao))
        NovaSessao(vm, estado, autenticacao.emCurso, ::aoIniciar)
        Recentes(estado, aoAbrirSessao)
    }
}

@Composable
private fun Metricas(estado: SessoesViewModel.Estado) {
    val recente = estado.sessoes.firstOrNull()
    LinhaDeMetricas {
        CartaoDeMetrica(
            stringResource(R.string.metrica_sessao),
            recente?.let { Rotulos.status(it.status) } ?: stringResource(R.string.metrica_sem_sessao),
            Marcas.METRICA_SESSAO,
            Modifier.weight(1f),
        )
        CartaoDeMetrica(
            stringResource(R.string.metrica_com_o_trabalho),
            Rotulos.agente(estado.agenteAtivo),
            Marcas.METRICA_COM_O_TRABALHO,
            Modifier.weight(1f),
        )
    }
    LinhaDeMetricas {
        CartaoDeMetrica(
            stringResource(R.string.metrica_agentes_prontos),
            stringResource(R.string.metrica_agentes_prontos_valor, estado.prontos.size, Provedor.entries.size),
            Marcas.METRICA_AGENTES_PRONTOS,
            Modifier.weight(1f),
        )
        CartaoDeMetrica(
            stringResource(R.string.metrica_teto),
            stringResource(R.string.valor_usd, Formatos.usd(estado.configuracoes?.tetoDeCustoUsd ?: BigDecimal.ZERO, 2)),
            Marcas.METRICA_TETO,
            Modifier.weight(1f),
        )
    }
}

/**
 * Só do aparelho (especificação, seção 2.2): o manifesto de citações opcional,
 * lido na hora com a leitura que a sessão fará ao começar. Um arquivo que ela
 * não leria como manifesto impede o início, com o motivo.
 */
@Composable
private fun ManifestoDoFormulario(vm: SessoesViewModel) {
    val resolver = LocalContext.current.contentResolver
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.escolherManifesto(it, resolver) }
    RotuloDeCampo(stringResource(R.string.campo_manifesto))
    val manifesto = vm.manifesto
    if (manifesto == null) {
        BotaoFantasma(
            stringResource(R.string.escolher_manifesto),
            aoClicar = { escolher.launch(arrayOf("application/json", "*/*")) },
            icone = R.drawable.simbolo_description,
            carregando = vm.lendoManifesto,
            modifier = Modifier.testTag(Marcas.ESCOLHER_MANIFESTO),
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(manifesto.nome, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Tema.cores.texto)
            val leitura = manifesto.leitura
            Text(
                text = when (leitura) {
                    is AnexosViewModel.Manifesto.Lido -> stringResource(
                        if (leitura.comAnterior) R.string.manifesto_lido_com_anterior else R.string.manifesto_lido,
                        pluralStringResource(R.plurals.citacoes, leitura.citacoes, leitura.citacoes),
                        pluralStringResource(R.plurals.fontes, leitura.fontes, leitura.fontes),
                    )
                    is AnexosViewModel.Manifesto.Recusado -> stringResource(R.string.manifesto_recusado, leitura.motivo)
                    AnexosViewModel.Manifesto.Ausente -> stringResource(R.string.manifesto_nao_reconhecido)
                },
                fontSize = 13.sp,
                color = if (leitura is AnexosViewModel.Manifesto.Lido) Tema.cores.textoFraco else Tema.cores.erro,
                modifier = Modifier.testTag(Marcas.MANIFESTO_DO_FORMULARIO),
            )
        }
        BotaoFantasma(
            stringResource(R.string.acao_remover),
            aoClicar = vm::tirarManifesto,
            icone = R.drawable.simbolo_delete,
            modifier = Modifier.testTag(Marcas.TIRAR_MANIFESTO),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NovaSessao(vm: SessoesViewModel, estado: SessoesViewModel.Estado, autenticando: Boolean, aoIniciar: () -> Unit) {
    val prontos = estado.prontos
    Cartao {
        Cabecalho(R.drawable.simbolo_play_arrow, stringResource(R.string.nova_sessao), stringResource(R.string.pedido_editorial))
        CampoDeTexto(vm.titulo, { vm.titulo = it }, stringResource(R.string.campo_titulo), marca = Marcas.CAMPO_TITULO)
        CampoDeTexto(
            vm.pedido,
            { vm.pedido = it },
            stringResource(R.string.campo_pedido),
            exemplo = stringResource(R.string.pedido_exemplo),
            linhas = 7,
            marca = Marcas.CAMPO_PEDIDO,
        )
        CampoDeTexto(
            vm.textoInicial,
            { vm.textoInicial = it },
            stringResource(R.string.campo_texto_inicial),
            exemplo = stringResource(R.string.texto_inicial_exemplo),
            linhas = 5,
            marca = Marcas.CAMPO_TEXTO_INICIAL,
        )
        ManifestoDoFormulario(vm)
        // `<select>` do web: cada agente é uma opção, desabilitada se não está pronto.
        RotuloDeCampo(stringResource(R.string.campo_redator_inicial))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provedor.entries.forEach { agente ->
                FilterChip(
                    selected = vm.redatorInicial == agente,
                    onClick = { vm.escolherRedator(agente) },
                    label = { Text(agente.rotulo) },
                    enabled = agente in prontos,
                    modifier = Modifier.testTag(Marcas.redator(agente)),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Tema.cores.acentoSuave,
                        selectedLabelColor = Tema.cores.acento,
                    ),
                )
            }
        }
        RotuloDeCampo(stringResource(R.string.campo_colegiado))
        Column(verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
            Provedor.entries.forEach { agente ->
                val pronto = agente in prontos
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(agente.rotulo, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                        Legenda(stringResource(if (pronto) R.string.agente_pronto else R.string.agente_configure))
                    }
                    Checkbox(
                        checked = agente in vm.colegiado,
                        onCheckedChange = { vm.alternar(agente) },
                        enabled = pronto,
                        modifier = Modifier.testTag(Marcas.colegiado(agente)),
                        colors = CheckboxDefaults.colors(checkedColor = Tema.cores.acento),
                    )
                }
            }
        }
        val restante = estado.restante
        val configuracoes = estado.configuracoes
        if (restante != null && configuracoes != null && AvisoDeOrcamento.mostrar(restante, configuracoes.tetoDeMinutos)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Icon(painterResource(R.drawable.simbolo_warning), contentDescription = null, tint = Tema.cores.erro, modifier = Modifier.size(18.dp))
                Legenda(
                    stringResource(
                        R.string.orcamento_aviso,
                        Formatos.duracao(Orcamento.TETO.minus(restante)),
                        Formatos.duracao(restante),
                    ),
                    modifier = Modifier.testTag(Marcas.AVISO_DE_ORCAMENTO),
                )
            }
        }
        BotaoPrimario(
            stringResource(R.string.acao_iniciar),
            aoIniciar,
            modifier = Modifier.testTag(Marcas.INICIAR),
            icone = R.drawable.simbolo_play_arrow,
            carregando = estado.iniciando || autenticando,
        )
    }
}

@Composable
private fun Recentes(estado: SessoesViewModel.Estado, aoAbrirSessao: (String) -> Unit) {
    Cartao {
        Cabecalho(R.drawable.simbolo_groups, stringResource(R.string.historico), stringResource(R.string.sessoes_recentes))
        if (estado.sessoes.isEmpty()) {
            VazioDeResultado(stringResource(R.string.nenhuma_sessao))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
                estado.sessoes.forEach { sessao ->
                    LinhaEscolhivel(aoClicar = { aoAbrirSessao(sessao.id) }, marca = Marcas.sessao(sessao.id)) {
                        Text(sessao.titulo, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Tema.cores.botaoFantasmaTexto)
                        Text(Rotulos.status(sessao.status), color = Tema.cores.botaoFantasmaTexto)
                    }
                }
            }
        }
    }
}
