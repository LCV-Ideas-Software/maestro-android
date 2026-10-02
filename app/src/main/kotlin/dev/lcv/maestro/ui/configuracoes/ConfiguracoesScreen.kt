/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.configuracoes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lcv.maestro.R
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.Agentes.rotulo
import dev.lcv.maestro.ui.BotaoFantasma
import dev.lcv.maestro.ui.BotaoPrimario
import dev.lcv.maestro.ui.Cabecalho
import dev.lcv.maestro.ui.CampoDeTexto
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.Legenda
import dev.lcv.maestro.ui.LocalAvisos
import dev.lcv.maestro.ui.Marcas
import dev.lcv.maestro.ui.Pilula
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.VazioDeResultado
import dev.lcv.maestro.ui.rememberAutenticacaoDaTela

@Composable
fun ConfiguracoesScreen(vm: ConfiguracoesViewModel, versao: String, aoAbrirLicencas: () -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val avisos = LocalAvisos.current
    val autenticacao = rememberAutenticacaoDaTela()
    val contexto = LocalContext.current
    val recursos = LocalResources.current
    // A chave de API vai ao cofre do aparelho e a nenhum outro lugar: o campo é de
    // senha, e o Compose o entrega ao serviço de preenchimento automático, que o
    // salvaria ao sair da tela (o equivalente do `autoComplete="off"` do web). O
    // meio oficial de descartar o que o serviço guardou é `AutofillManager.cancel`
    // (documentação "Autofill in Compose"): a cada tecla, ao salvar e ao sair.
    val preenchimento = LocalAutofillManager.current
    DisposableEffect(preenchimento) { onDispose { preenchimento?.cancel() } }
    var confirmarTeste by rememberSaveable { mutableStateOf(false) }
    var notificacoesPermitidas by rememberSaveable { mutableStateOf(true) }

    LifecycleResumeEffect(vm) {
        vm.carregar()
        vm.recarregarCofre()
        notificacoesPermitidas =
            contexto.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    LaunchedEffect(vm) {
        vm.avisos.collect { evento ->
            when (evento) {
                is ConfiguracoesViewModel.Evento.Aviso -> avisos.mostrar(evento.mensagem.em(recursos))
                is ConfiguracoesViewModel.Evento.Autenticar -> autenticacao.pedir(
                    aoAutenticar = { vm.salvarChave(evento.provedor, depoisDeAutenticar = true) },
                    aoRecusar = vm::autenticacaoRecusada,
                )
            }
        }
    }

    if (estado.semTrava) {
        AlertDialog(
            onDismissRequest = vm::fecharSemTrava,
            modifier = Modifier.testTag(Marcas.SEM_TRAVA),
            title = { Text(stringResource(R.string.sem_trava_titulo)) },
            text = { Text(stringResource(R.string.sem_trava_texto)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.fecharSemTrava()
                    contexto.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }) { Text(stringResource(R.string.sem_trava_abrir)) }
            },
            dismissButton = { TextButton(onClick = vm::fecharSemTrava) { Text(stringResource(R.string.acao_fechar)) } },
        )
    }
    if (confirmarTeste) {
        AlertDialog(
            onDismissRequest = { confirmarTeste = false },
            title = { Text(stringResource(R.string.testar_titulo)) },
            text = { Text(stringResource(R.string.testar_texto)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmarTeste = false
                        autenticacao.pedir(aoAutenticar = vm::testar, aoRecusar = vm::autenticacaoRecusada)
                    },
                    modifier = Modifier.testTag(Marcas.CONFIRMAR_TESTE),
                ) { Text(stringResource(R.string.acao_testar_chaves)) }
            },
            dismissButton = { TextButton(onClick = { confirmarTeste = false }) { Text(stringResource(R.string.acao_fechar)) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        Cabecalho(R.drawable.simbolo_key, stringResource(R.string.secao_operacional), stringResource(R.string.configuracoes))
        Chaves(vm, estado, autenticacao.emCurso, aoDescartarPreenchimento = { preenchimento?.cancel() }) { confirmarTeste = true }
        // Sem a leitura das configurações, o formulário vazio pareceria o gravado: no lugar dele, o motivo (decisão 25 estendida, #80).
        val falhaDaLeitura = estado.falhaDaLeitura.takeIf { !estado.carregado }
        if (falhaDaLeitura != null) {
            VazioDeResultado(stringResource(R.string.tela_sem_leitura, falhaDaLeitura), Modifier.testTag(Marcas.LEITURA_FALHOU))
        } else {
            Custos(vm)
        }
        Modelos()
        if (falhaDaLeitura == null) {
            Contato(vm)
            Protocolo(vm, estado)
        }
        Notificacoes(notificacoesPermitidas) {
            contexto.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, contexto.packageName),
            )
        }
        Sobre(versao, aoAbrirLicencas)
    }
}

@Composable
private fun Chaves(
    vm: ConfiguracoesViewModel,
    estado: ConfiguracoesViewModel.Estado,
    autenticando: Boolean,
    aoDescartarPreenchimento: () -> Unit,
    aoTestar: () -> Unit,
) {
    Cartao {
        Cabecalho(R.drawable.simbolo_key, stringResource(R.string.ajustes), stringResource(R.string.chaves_dos_agentes))
        Legenda(stringResource(R.string.chaves_explicacao))
        // Seção 4.2 e emenda A14: sem trava de tela o Keystore não gera a chave que cifra as de
        // API, e remover a trava apaga a que havia. O cofre não distingue "nunca guardou" de
        // "perdeu com a trava"; por isso o aviso é um só, verdadeiro nos dois casos.
        if (!estado.travaDeTela) {
            Legenda(stringResource(R.string.sem_trava_aviso), modifier = Modifier.testTag(Marcas.SEM_TRAVA_AVISO))
        }
        Provedor.entries.forEach { agente ->
            val configurada = estado.chaves[agente]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agente.rotulo, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                    Pilula(
                        stringResource(
                            when (configurada) {
                                true -> R.string.pilula_configurada
                                false -> R.string.pilula_nao_configurada
                                null -> R.string.pilula_nao_verificavel
                            },
                        ),
                        modifier = Modifier.testTag(Marcas.pilulaDaChave(agente)),
                    )
                }
                CampoDeTexto(
                    vm.chavesDigitadas[agente].orEmpty(),
                    {
                        vm.chavesDigitadas[agente] = it
                        aoDescartarPreenchimento()
                    },
                    stringResource(R.string.chave_do_agente, agente.rotulo),
                    exemplo = stringResource(
                        when (configurada) {
                            true -> R.string.chave_configurada_placeholder
                            false -> R.string.chave_informe
                            null -> R.string.chave_nao_verificavel_placeholder
                        },
                    ),
                    senha = true,
                    marca = Marcas.chave(agente),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoPrimario(
                        stringResource(R.string.acao_salvar),
                        {
                            aoDescartarPreenchimento()
                            vm.salvarChave(agente)
                        },
                        modifier = Modifier.testTag(Marcas.salvarChave(agente)),
                        icone = R.drawable.simbolo_save,
                        habilitado = vm.chavesDigitadas[agente].orEmpty().isNotBlank() && !autenticando,
                    )
                    if (configurada == true) {
                        BotaoFantasma(
                            stringResource(R.string.acao_remover),
                            { vm.removerChave(agente) },
                            modifier = Modifier.testTag(Marcas.removerChave(agente)),
                            icone = R.drawable.simbolo_delete,
                        )
                    }
                }
            }
        }
        Legenda(
            stringResource(
                when (estado.nivel) {
                    NivelDoCofre.STRONGBOX -> R.string.cofre_nivel_strongbox
                    NivelDoCofre.AMBIENTE_SEGURO -> R.string.cofre_nivel_tee
                    NivelDoCofre.SOFTWARE -> R.string.cofre_nivel_software
                    NivelDoCofre.DESCONHECIDO -> R.string.cofre_nivel_desconhecido
                    null -> R.string.cofre_nivel_ausente
                },
            ),
            modifier = Modifier.testTag(Marcas.NIVEL_DO_COFRE),
        )
        BotaoFantasma(
            stringResource(R.string.acao_testar_chaves),
            aoTestar,
            modifier = Modifier.testTag(Marcas.TESTAR_CHAVES),
            icone = R.drawable.simbolo_refresh,
            carregando = estado.testando || autenticando,
        )
        estado.resultados.forEach { resultado ->
            Row(
                modifier = Modifier.testTag(Marcas.resultadoDoTeste(resultado.provedor)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(resultado.provedor.rotulo, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                    Legenda(resultado.mensagem)
                }
                Pilula(stringResource(if (resultado.ok) R.string.testar_resultado_ok else R.string.testar_resultado_falha))
            }
        }
    }
}

@Composable
private fun Custos(vm: ConfiguracoesViewModel) {
    Cartao {
        Cabecalho(R.drawable.simbolo_paid, stringResource(R.string.custos), stringResource(R.string.valores_dos_tokens))
        CampoDeTexto(vm.teto, { vm.teto = it }, stringResource(R.string.campo_teto), teclado = KeyboardType.Decimal, marca = Marcas.CAMPO_TETO)
        CampoDeTexto(
            vm.limiteDeMinutos,
            { vm.limiteDeMinutos = it },
            stringResource(R.string.campo_limite_de_tempo),
            exemplo = stringResource(R.string.sem_limite),
            teclado = KeyboardType.Number,
            ajuda = stringResource(R.string.limite_de_tempo_ajuda),
            marca = Marcas.CAMPO_LIMITE,
        )
        Provedor.entries.forEach { agente ->
            val digitadas = vm.taxas[agente] ?: ConfiguracoesViewModel.TaxasDigitadas("", "", "")
            Text(agente.rotulo, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampoDeTexto(
                    digitadas.entrada,
                    { vm.taxas[agente] = digitadas.copy(entrada = it) },
                    stringResource(R.string.taxa_entrada),
                    modifier = Modifier.weight(1f),
                    teclado = KeyboardType.Decimal,
                    marca = Marcas.entrada(agente),
                )
                CampoDeTexto(
                    digitadas.saida,
                    { vm.taxas[agente] = digitadas.copy(saida = it) },
                    stringResource(R.string.taxa_saida),
                    modifier = Modifier.weight(1f),
                    teclado = KeyboardType.Decimal,
                    marca = Marcas.saida(agente),
                )
            }
            if (agente == Provedor.PERPLEXITY) {
                CampoDeTexto(
                    digitadas.busca,
                    { vm.taxas[agente] = digitadas.copy(busca = it) },
                    stringResource(R.string.taxa_busca),
                    modifier = Modifier.width(160.dp),
                    teclado = KeyboardType.Decimal,
                    marca = Marcas.BUSCA_PERPLEXITY,
                )
            }
        }
    }
}

/** O modelo é fixo por provedor (política da frota: sempre o mais novo); a tela só o mostra. */
@Composable
private fun Modelos() {
    Cartao {
        Cabecalho(R.drawable.simbolo_smart_toy, stringResource(R.string.modelos), stringResource(R.string.modelo_por_agente))
        Provedor.entries.forEach { agente ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(agente.rotulo, modifier = Modifier.width(96.dp), fontWeight = FontWeight.Bold, color = Tema.cores.texto)
                Text(agente.modelo, color = Tema.cores.textoFraco)
            }
        }
    }
}

@Composable
private fun Contato(vm: ConfiguracoesViewModel) {
    Cartao {
        Cabecalho(R.drawable.simbolo_mail, stringResource(R.string.ajustes), stringResource(R.string.contato))
        Legenda(stringResource(R.string.contato_explicacao))
        CampoDeTexto(vm.email, { vm.email = it }, stringResource(R.string.campo_email), teclado = KeyboardType.Email, marca = Marcas.CAMPO_EMAIL)
    }
}

@Composable
private fun Protocolo(vm: ConfiguracoesViewModel, estado: ConfiguracoesViewModel.Estado) {
    Cartao {
        Cabecalho(R.drawable.simbolo_key, stringResource(R.string.ajustes_internos), stringResource(R.string.protocolo_editorial))
        Legenda(stringResource(R.string.protocolo_explicacao))
        CampoDeTexto(vm.protocolo, { vm.protocolo = it }, stringResource(R.string.campo_protocolo), linhas = 12, marca = Marcas.CAMPO_PROTOCOLO)
        BotaoPrimario(
            stringResource(R.string.acao_salvar_configuracoes),
            vm::salvarConfiguracoes,
            modifier = Modifier.testTag(Marcas.SALVAR_CONFIGURACOES),
            icone = R.drawable.simbolo_save,
            habilitado = estado.carregado,
            carregando = estado.salvando,
        )
    }
}

@Composable
private fun Notificacoes(permitidas: Boolean, aoAbrirAjustes: () -> Unit) {
    Cartao {
        Cabecalho(R.drawable.simbolo_notifications, stringResource(R.string.ajustes), stringResource(R.string.notificacoes))
        Legenda(
            stringResource(if (permitidas) R.string.notificacoes_estado_concedida else R.string.notificacoes_estado_negada),
            modifier = Modifier.testTag(Marcas.ESTADO_DAS_NOTIFICACOES),
        )
        if (!permitidas) BotaoFantasma(stringResource(R.string.notificacoes_abrir_ajustes), aoAbrirAjustes)
    }
}

@Composable
private fun Sobre(versao: String, aoAbrirLicencas: () -> Unit) {
    Cartao {
        Cabecalho(R.drawable.simbolo_info, stringResource(R.string.titulo), stringResource(R.string.sobre))
        Legenda(stringResource(R.string.versao, versao))
        BotaoFantasma(stringResource(R.string.acao_licencas), aoAbrirLicencas, modifier = Modifier.testTag(Marcas.ABRIR_LICENCAS))
    }
}
