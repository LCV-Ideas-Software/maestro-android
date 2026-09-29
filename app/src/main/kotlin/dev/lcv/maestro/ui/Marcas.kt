/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import dev.lcv.maestro.provedores.Provedor

/** Os `testTag` que os testes de tela procuram: um nome estável por controle e por valor lido. */
object Marcas {
    // Casca
    const val IR_PARA_CONFIGURACOES = "ir-para-configuracoes"
    const val VOLTAR = "voltar"

    // Sessões
    const val METRICA_SESSAO = "metrica-sessao"
    const val METRICA_COM_O_TRABALHO = "metrica-com-o-trabalho"
    const val METRICA_AGENTES_PRONTOS = "metrica-agentes-prontos"
    const val METRICA_TETO = "metrica-teto"
    const val CAMPO_TITULO = "campo-titulo"
    const val CAMPO_PEDIDO = "campo-pedido"
    const val CAMPO_TEXTO_INICIAL = "campo-texto-inicial"
    const val INICIAR = "iniciar"
    const val AVISO_DE_ORCAMENTO = "aviso-de-orcamento"
    const val RAZAO_DAS_NOTIFICACOES = "razao-das-notificacoes"
    fun redator(provedor: Provedor) = "redator-${provedor.agente}"
    fun colegiado(provedor: Provedor) = "colegiado-${provedor.agente}"
    fun sessao(id: String) = "sessao-$id"

    // Sessão
    const val CANCELAR = "cancelar"
    const val RETOMAR = "retomar"
    const val METRICA_CUSTO = "metrica-custo"
    const val EVENTOS = "eventos"
    const val MOSTRAR_EVENTOS = "mostrar-eventos"
    const val CONTEUDO_DA_ABA = "conteudo-da-aba"
    const val TEXTO_DA_SESSAO = "texto-da-sessao"
    const val ERRO_OPERACIONAL = "erro-operacional"
    const val CHAMADA_INDETERMINADA = "chamada-indeterminada"
    const val PARADA_PELO_SISTEMA = "parada-pelo-sistema"
    const val NOVO_TETO = "novo-teto"
    const val CONFIRMAR_RETOMADA = "confirmar-retomada"
    fun artefato(id: String) = "artefato-$id"
    fun aba(nome: String) = "aba-$nome"
    fun lider(provedor: Provedor) = "lider-${provedor.agente}"
    fun painel(provedor: Provedor) = "painel-${provedor.agente}"

    // Configurações
    const val TESTAR_CHAVES = "testar-chaves"
    const val CONFIRMAR_TESTE = "confirmar-teste"
    const val NIVEL_DO_COFRE = "nivel-do-cofre"
    const val SEM_TRAVA = "sem-trava"
    const val SEM_TRAVA_AVISO = "sem-trava-aviso"
    const val CAMPO_TETO = "campo-teto"
    const val CAMPO_LIMITE = "campo-limite"
    const val CAMPO_PROTOCOLO = "campo-protocolo"
    const val CAMPO_EMAIL = "campo-email"
    const val SALVAR_CONFIGURACOES = "salvar-configuracoes"
    const val ESTADO_DAS_NOTIFICACOES = "estado-das-notificacoes"
    const val ABRIR_LICENCAS = "abrir-licencas"
    fun chave(provedor: Provedor) = "chave-${provedor.agente}"
    fun salvarChave(provedor: Provedor) = "salvar-chave-${provedor.agente}"
    fun removerChave(provedor: Provedor) = "remover-chave-${provedor.agente}"
    fun pilulaDaChave(provedor: Provedor) = "pilula-da-chave-${provedor.agente}"
    fun entrada(provedor: Provedor) = "entrada-${provedor.agente}"
    fun saida(provedor: Provedor) = "saida-${provedor.agente}"
    fun resultadoDoTeste(provedor: Provedor) = "resultado-do-teste-${provedor.agente}"
    const val BUSCA_PERPLEXITY = "busca-perplexity"
}
