/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.lcv.maestro.Dependencias
import dev.lcv.maestro.R
import dev.lcv.maestro.ui.configuracoes.ConfiguracoesScreen
import dev.lcv.maestro.ui.configuracoes.ConfiguracoesViewModel
import dev.lcv.maestro.ui.licencas.LicencasScreen
import dev.lcv.maestro.ui.sessao.SessaoScreen
import dev.lcv.maestro.ui.sessao.SessaoViewModel
import dev.lcv.maestro.ui.sessoes.SessoesScreen
import dev.lcv.maestro.ui.sessoes.SessoesViewModel
import kotlinx.serialization.Serializable

/** A tela inicial: métricas, nova sessão e sessões recentes. */
@Serializable
data object TelaSessoes : NavKey

/** A tela de uma sessão, que é a superfície canônica de acompanhamento (especificação, seção 4.1). */
@Serializable
data class TelaSessao(val id: String) : NavKey

@Serializable
data object TelaConfiguracoes : NavKey

@Serializable
data object TelaLicencas : NavKey

/**
 * A casca do aplicativo: a barra superior com a marca, o `Snackbar` único dos
 * avisos e a pilha de telas da Navigation 3 (plano do `:app`, ponto C1). A
 * pilha é uma lista de chaves que o aplicativo possui e guarda no estado
 * salvo; cada tela recebe o seu `ViewModel`, descartado quando ela sai da
 * pilha. O toque na notificação de uma sessão chega como [sessaoPedida] e
 * empilha a tela dela sobre a inicial.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaestroApp(
    dependencias: Dependencias,
    autenticador: Autenticador,
    versao: String,
    sessaoPedida: String?,
    aoConsumirSessaoPedida: () -> Unit,
) {
    val pilha = rememberNavBackStack(TelaSessoes)
    val estadoDosAvisos = remember { SnackbarHostState() }
    val escopo = rememberCoroutineScope()
    val avisos = remember(estadoDosAvisos, escopo) { Avisos(estadoDosAvisos, escopo) }

    LaunchedEffect(sessaoPedida) {
        val id = sessaoPedida ?: return@LaunchedEffect
        val tela = TelaSessao(id)
        if (pilha.lastOrNull() != tela) pilha.add(tela)
        aoConsumirSessaoPedida()
    }

    CompositionLocalProvider(LocalAutenticador provides autenticador, LocalAvisos provides avisos) {
        Scaffold(
            containerColor = Tema.cores.fundo,
            topBar = { BarraSuperior(pilha) },
            snackbarHost = { SnackbarHost(estadoDosAvisos) },
        ) { espaco ->
            NavDisplay(
                backStack = pilha,
                modifier = Modifier.padding(espaco),
                // A tela inicial nunca sai: a `NavDisplay` exige pilha não vazia, e
                // o voltar nela é o do sistema, que fecha o aplicativo.
                onBack = { if (pilha.size > 1) pilha.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<TelaSessoes> {
                        SessoesScreen(
                            vm = viewModel { SessoesViewModel(dependencias) },
                            aoAbrirSessao = { id -> pilha.add(TelaSessao(id)) },
                        )
                    }
                    entry<TelaSessao> { tela ->
                        SessaoScreen(vm = viewModel { SessaoViewModel(dependencias, tela.id) })
                    }
                    entry<TelaConfiguracoes> {
                        ConfiguracoesScreen(
                            vm = viewModel { ConfiguracoesViewModel(dependencias) },
                            versao = versao,
                            aoAbrirLicencas = { pilha.add(TelaLicencas) },
                        )
                    }
                    entry<TelaLicencas> { LicencasScreen() }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarraSuperior(pilha: NavBackStack<NavKey>) {
    val atual = pilha.lastOrNull()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Tema.cores.fundo),
        navigationIcon = {
            if (pilha.size > 1) {
                IconButton(onClick = { pilha.removeLastOrNull() }, modifier = Modifier.testTag(Marcas.VOLTAR)) {
                    Icon(painterResource(R.drawable.simbolo_arrow_back), contentDescription = stringResource(R.string.acao_voltar))
                }
            }
        },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.marca_lcv),
                    contentDescription = stringResource(R.string.marca_descricao),
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(stringResource(R.string.titulo), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Tema.cores.texto)
                    Text(
                        text = stringResource(
                            when (atual) {
                                is TelaSessao -> R.string.secao_sessao
                                TelaConfiguracoes -> R.string.configuracoes
                                TelaLicencas -> R.string.acao_licencas
                                else -> R.string.subtitulo
                            },
                        ),
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = Tema.cores.textoFraco,
                    )
                }
            }
        },
        actions = {
            if (atual == TelaSessoes) {
                IconButton(onClick = { pilha.add(TelaConfiguracoes) }, modifier = Modifier.testTag(Marcas.IR_PARA_CONFIGURACOES)) {
                    Icon(painterResource(R.drawable.simbolo_settings), contentDescription = stringResource(R.string.acao_configuracoes))
                }
            }
        },
    )
}
