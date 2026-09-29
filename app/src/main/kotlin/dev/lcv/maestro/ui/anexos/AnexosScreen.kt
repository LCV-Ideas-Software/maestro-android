/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.anexos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.Formatos
import dev.lcv.maestro.ui.Legenda
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.VazioDeResultado

/**
 * Os anexos da sessão e o manifesto de citações (especificação, seção 2.2;
 * MAEANDR-18): anexar pelo seletor de documentos do sistema, ver na hora o que
 * a sessão vai ler dele, e remover.
 */
@Composable
fun AnexosScreen(vm: AnexosViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val recursos = LocalResources.current
    val resolver = LocalContext.current.contentResolver
    LaunchedEffect(vm) { vm.avisos.collect { avisos.mostrar(it.em(recursos)) } }
    LifecycleResumeEffect(vm) {
        vm.recarregar()
        onPauseOrDispose { }
    }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.anexar(it, resolver) }

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
                Cabecalho(R.drawable.simbolo_description, stringResource(R.string.anexos), estado.titulo)
                Cartao {
                    Text(stringResource(R.string.manifesto_de_citacoes), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Tema.cores.texto)
                    Legenda(stringResource(R.string.manifesto_explicacao))
                    Text(
                        text = when (val manifesto = estado.manifesto) {
                            AnexosViewModel.Manifesto.Ausente -> stringResource(R.string.manifesto_ausente)
                            is AnexosViewModel.Manifesto.Lido -> stringResource(
                                if (manifesto.comAnterior) R.string.manifesto_lido_com_anterior else R.string.manifesto_lido,
                                pluralStringResource(R.plurals.citacoes, manifesto.citacoes, manifesto.citacoes),
                                pluralStringResource(R.plurals.fontes, manifesto.fontes, manifesto.fontes),
                            )
                            is AnexosViewModel.Manifesto.Recusado -> stringResource(R.string.manifesto_recusado, manifesto.motivo)
                        },
                        fontSize = 14.sp,
                        color = if (estado.manifesto is AnexosViewModel.Manifesto.Recusado) Tema.cores.erro else Tema.cores.texto,
                        modifier = Modifier.testTag(Marcas.MANIFESTO_RESULTADO),
                    )
                    if (estado.emExecucao) {
                        Legenda(stringResource(R.string.anexos_em_execucao), Modifier.testTag(Marcas.ANEXOS_EM_EXECUCAO))
                    }
                    BotaoPrimario(
                        stringResource(R.string.anexar_manifesto),
                        aoClicar = { escolher.launch(arrayOf("application/json", "*/*")) },
                        icone = R.drawable.simbolo_description,
                        habilitado = !estado.emExecucao,
                        carregando = estado.trabalhando,
                        modifier = Modifier.testTag(Marcas.ANEXAR_MANIFESTO),
                    )
                }
                if (estado.anexos.isEmpty()) {
                    VazioDeResultado(stringResource(R.string.sem_anexos))
                }
                estado.anexos.forEach { anexo ->
                    Cartao(modifier = Modifier.testTag(Marcas.anexo(anexo.id))) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(anexo.nomeOriginal, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Tema.cores.texto)
                                Legenda("${anexo.tipoDeMidia} · ${Formatos.bytes(anexo.bytes)} · ${anexo.sha256.take(12)}")
                                Legenda(Formatos.dataDoEvento(anexo.criadoEm))
                            }
                            BotaoFantasma(
                                stringResource(R.string.acao_remover),
                                aoClicar = { vm.remover(anexo.id) },
                                icone = R.drawable.simbolo_delete,
                                habilitado = !estado.emExecucao && !estado.trabalhando,
                                modifier = Modifier.testTag(Marcas.removerAnexo(anexo.id)),
                            )
                        }
                    }
                }
            }
        }
    }
}
