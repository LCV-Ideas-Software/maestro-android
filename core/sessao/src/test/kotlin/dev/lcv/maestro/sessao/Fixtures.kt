package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.StatusDaRevisao
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal
import java.time.Instant

/** Linhas e entradas de teste, com os campos que os casos não variam preenchidos uma vez. */
internal object Fixtures {
    val AGORA: Instant = Instant.parse("2026-09-25T12:00:00.500Z")
    const val ISO_AGORA = "2026-09-25T12:00:00.500Z"

    fun sessao(
        id: String = "android-1",
        status: String = Estados.RODANDO,
        autorAtual: String? = null,
        textoAtual: String = "",
        agentes: List<Provedor> = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI),
        liderDoCiclo: String = "claude",
        textoFinal: String? = null,
        tetoDeMinutos: Int? = null,
        criadaEm: String = ISO_AGORA,
        custodiaArtefatoId: String? = null,
        artefatoAnteriorId: String? = null,
        rodada: Int = 1,
        indiceDoTurno: Int = 0,
        turnoDoArtefato: Int = 0,
        escala: List<Provedor> = emptyList(),
        agentesValidos: List<Provedor> = emptyList(),
        aprovacoesEstaveis: List<Provedor> = emptyList(),
        escalaJson: String? = null,
        agentesValidosJson: String? = null,
        aprovacoesEstaveisJson: String? = null,
    ): SessaoEntidade = SessaoEntidade(
        id = id,
        titulo = "Sessao de teste",
        pedido = "Escreva.",
        protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
        agenteInicial = "claude",
        liderDoCiclo = liderDoCiclo,
        agentesAtivosJson = RepositorioDeSessoes.agentesJson(agentes),
        status = status,
        autorAtual = autorAtual,
        textoAtual = textoAtual,
        textoFinal = textoFinal,
        tetoDeCustoE8 = Dinheiro.paraE8(BigDecimal("5")),
        tetoDeMinutos = tetoDeMinutos,
        maxCiclos = 2,
        taxasJson = Taxas.paraJson(Taxas.PADRAO),
        modelosJson = RepositorioDeSessoes.modelosJson(),
        custodiaArtefatoId = custodiaArtefatoId,
        artefatoAnteriorId = artefatoAnteriorId,
        rodada = rodada,
        indiceDoTurno = indiceDoTurno,
        turnoDoArtefato = turnoDoArtefato,
        escalaJson = escalaJson ?: EstadoCircular.agentesJson(escala),
        agentesValidosJson = agentesValidosJson ?: EstadoCircular.agentesJson(agentesValidos),
        aprovacoesEstaveisJson = aprovacoesEstaveisJson ?: EstadoCircular.agentesJson(aprovacoesEstaveis),
        criadaEm = criadaEm,
        atualizadaEm = criadaEm,
    )

    fun entrada(
        sessaoId: String = "android-1",
        ciclo: Int = 1,
        turno: Int = 1,
        agente: Provedor = Provedor.CLAUDE,
        papel: String = "draft",
        status: String = "ready",
        titulo: String = "Sessao de teste",
        texto: String = "Texto.",
        relatorio: String? = null,
        auditoria: List<LinhaDeLink> = emptyList(),
        custoUsd: BigDecimal? = null,
        anteriorId: String? = null,
        modelo: String? = null,
    ): EntradaDeArtefato = EntradaDeArtefato(
        sessaoId = sessaoId,
        ciclo = ciclo,
        turno = turno,
        agente = agente,
        papel = papel,
        status = status,
        titulo = titulo,
        texto = texto,
        relatorioDeRevisao = relatorio,
        auditoriaDeLinks = auditoria,
        custoUsd = custoUsd,
        artefatoAnteriorId = anteriorId,
        modelo = modelo,
    )

    /** A linha que `RepositorioDeArtefatos.inserir` gravaria, sem banco. */
    fun artefato(id: String, entrada: EntradaDeArtefato, criadoEm: String = ISO_AGORA): ArtefatoEntidade {
        val textoAceito = EstadoCircular.textoCanonico(entrada.texto)
        val conteudo = Texto.sanear(MarkdownDoArtefato.montar(entrada.copy(texto = textoAceito)), 500_000)
        return ArtefatoEntidade(
            id = id,
            sessaoId = entrada.sessaoId,
            ciclo = entrada.ciclo,
            turno = entrada.turno,
            agente = entrada.agente.agente,
            papel = entrada.papel,
            status = entrada.status,
            titulo = entrada.titulo,
            textoAceito = textoAceito,
            conteudoMd = conteudo,
            relatorioDeRevisaoJson = entrada.relatorioDeRevisao ?: "{}",
            auditoriaDeLinksJson = "[]",
            custoE8 = Dinheiro.paraE8(entrada.custoUsd ?: BigDecimal.ZERO),
            modelo = entrada.modelo,
            artefatoAnteriorId = entrada.artefatoAnteriorId,
            bytesDoConteudo = conteudo.toByteArray(Charsets.UTF_8).size.toLong(),
            criadoEm = criadoEm,
        )
    }

    fun linha(linkId: String = "lnk-1", tom: String = "ok", status: String = "verified_supports_claim"): LinhaDeLink = LinhaDeLink(
        versaoDoEsquema = "link_evidence.v1",
        linkId = linkId,
        artefatoDeOrigem = "artifact-1",
        impressaoDaOrigem = "abc",
        textoDaAncora = "fonte",
        textoAoRedor = "a fonte diz",
        urlOriginal = "https://example.com/a",
        urlNormalizada = "https://example.com/a",
        mudancasDaNormalizacao = emptyList(),
        urlFinal = "https://example.com/a",
        cadeiaDeRedirecionamento = emptyList(),
        statusHttp = 200,
        tipoDeConteudo = "text/html",
        sha256 = "0".repeat(64),
        verificadoEm = "2026-09-25T12:00:00+00:00",
        sustentaAfirmacao = true,
        classificacao = ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO,
        classificacaoMecanica = ClassificacaoDoLink.VERIFICADO_SUSTENTA_A_AFIRMACAO,
        candidatosDeCorrecao = emptyList(),
        statusDaRevisao = StatusDaRevisao.DISPENSADA,
        decisaoDeRevisao = null,
        revisadoPor = null,
        notaDaRevisao = null,
        revisadoEm = null,
        evidenciaWebId = null,
        url = "https://example.com/a",
        status = status,
        invalidade = "",
        tom = tom,
    )
}
