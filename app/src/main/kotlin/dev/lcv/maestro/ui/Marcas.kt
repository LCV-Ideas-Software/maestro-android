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
    const val ABRIR_TEXTO_FINAL = "abrir-texto-final"
    fun artefato(id: String) = "artefato-$id"
    fun aba(nome: String) = "aba-$nome"
    fun lider(provedor: Provedor) = "lider-${provedor.agente}"
    fun painel(provedor: Provedor) = "painel-${provedor.agente}"

    // Texto final
    const val TEXTO_NAO_LIBERADO = "texto-nao-liberado"
    const val TEXTO_FINAL_PAGINA = "texto-final-pagina"
    const val EXPORTAR_MARKDOWN = "exportar-markdown"
    const val EXPORTAR_TXT = "exportar-txt"
    const val EXPORTAR_PDF = "exportar-pdf"

    // Anexos
    const val ABRIR_ANEXOS = "abrir-anexos"
    const val ANEXAR_MANIFESTO = "anexar-manifesto"
    const val MANIFESTO_RESULTADO = "manifesto-resultado"
    const val ANEXOS_EM_EXECUCAO = "anexos-em-execucao"
    fun anexo(id: String) = "anexo-$id"
    fun removerAnexo(id: String) = "remover-anexo-$id"
    const val ESCOLHER_MANIFESTO = "escolher-manifesto"
    const val MANIFESTO_DO_FORMULARIO = "manifesto-do-formulario"
    const val TIRAR_MANIFESTO = "tirar-manifesto"

    // Links
    const val ABRIR_LINKS = "abrir-links"
    const val ABRIR_LINKS_DOS_AUTOS = "abrir-links-dos-autos"
    const val SEM_LINKS_AUDITADOS = "sem-links-auditados"
    const val LINKS_EM_EXECUCAO = "links-em-execucao"
    const val DETALHE_DO_LINK = "detalhe-do-link"
    const val ABRIR_NO_NAVEGADOR = "abrir-no-navegador"
    const val NOTA_DA_CAPTURA = "nota-da-captura"
    const val IMPORTAR_CAPTURA = "importar-captura"
    const val CONSULTA_DE_CORRECAO = "consulta-de-correcao"
    const val BUSCAR_PROPOSTAS = "buscar-propostas"
    const val NOTA_DA_REVISAO = "nota-da-revisao"
    const val REGISTRAR_DECISAO = "registrar-decisao"
    fun link(id: String) = "link-$id"
    fun evidencia(id: String) = "evidencia-$id"
    fun candidato(id: String) = "candidato-$id"
    fun provedorDeBusca(id: String) = "provedor-de-busca-$id"
    fun decisao(nome: String) = "decisao-$nome"

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
