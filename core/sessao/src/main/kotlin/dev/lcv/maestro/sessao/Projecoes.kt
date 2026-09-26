package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal

/** `publicSession` (`sessions.ts:782-803`): a linha como a tela a vê, com dinheiro em `BigDecimal` e as listas lidas. */
public data class ProjecaoDaSessao(
    val id: String,
    val titulo: String,
    val status: String,
    val agenteInicial: String,
    /** `cycle_lead || initial_agent`. */
    val liderDoCiclo: String,
    val agentesAtivos: List<Provedor>,
    val custodiaArtefatoId: String?,
    val rodada: Int,
    val indiceDoTurno: Int,
    val autorAtual: String?,
    val textoAtual: String,
    val textoFinal: String?,
    val custoObservadoUsd: BigDecimal,
    val tetoDeCustoUsd: BigDecimal,
    val tetoDeMinutos: Int?,
    val maxCiclos: Int,
    val eventos: List<EventoDaSessao>,
    val criadaEm: String,
    val atualizadaEm: String,
    val erro: String?,
) {
    public companion object {
        public fun de(linha: SessaoEntidade, eventos: List<EventoEntidade>): ProjecaoDaSessao = ProjecaoDaSessao(
            id = linha.id,
            titulo = linha.titulo,
            status = linha.status,
            agenteInicial = linha.agenteInicial,
            liderDoCiclo = linha.liderDoCiclo.ifEmpty { linha.agenteInicial },
            agentesAtivos = RepositorioDeSessoes.lerAgentes(linha.agentesAtivosJson),
            custodiaArtefatoId = linha.custodiaArtefatoId,
            rodada = linha.rodada,
            indiceDoTurno = linha.indiceDoTurno,
            autorAtual = linha.autorAtual,
            textoAtual = linha.textoAtual,
            textoFinal = linha.textoFinal,
            custoObservadoUsd = Dinheiro.deE8(linha.custoObservadoE8),
            tetoDeCustoUsd = Dinheiro.deE8(linha.tetoDeCustoE8),
            tetoDeMinutos = linha.tetoDeMinutos,
            maxCiclos = linha.maxCiclos,
            eventos = eventos.map(EventoEntidade::paraEvento),
            criadaEm = linha.criadaEm,
            atualizadaEm = linha.atualizadaEm,
            erro = linha.erro,
        )
    }
}

/** `publicArtifactSummary` (`sessions.ts:805-823`). */
public data class ResumoDoArtefato(
    val id: String,
    val sessaoId: String,
    val ciclo: Int,
    val turno: Int,
    val agente: String,
    val papel: String,
    val status: String,
    val titulo: String,
    val custoUsd: BigDecimal,
    val modelo: String?,
    val artefatoAnteriorId: String?,
    val bytesDoConteudo: Long,
    val linksInvalidos: Int,
    val criadoEm: String,
) {
    public companion object {
        public fun de(linha: ArtefatoEntidade): ResumoDoArtefato = ResumoDoArtefato(
            id = linha.id,
            sessaoId = linha.sessaoId,
            ciclo = linha.ciclo,
            turno = linha.turno,
            agente = linha.agente,
            papel = linha.papel,
            status = linha.status,
            titulo = linha.titulo,
            custoUsd = Dinheiro.deE8(linha.custoE8),
            modelo = linha.modelo,
            artefatoAnteriorId = linha.artefatoAnteriorId,
            bytesDoConteudo = linha.bytesDoConteudo,
            linksInvalidos = MarkdownDoArtefato.contarInvalidos(RepositorioDeArtefatos.lerAuditoria(linha.auditoriaDeLinksJson)),
            criadoEm = linha.criadoEm,
        )
    }
}

/** `publicArtifactDetail` (`sessions.ts:825-833`): o resumo mais o texto aceito, o markdown, o relatório e o texto anterior. */
public data class DetalheDoArtefato(
    val resumo: ResumoDoArtefato,
    val textoAceito: String,
    val conteudoMd: String,
    val relatorioDeRevisao: String,
    val auditoriaDeLinks: List<LinhaDeLink>,
    /** `previous?.content_md ?? ''`. */
    val conteudoAnteriorMd: String,
) {
    public companion object {
        public fun de(linha: ArtefatoEntidade, anterior: ArtefatoEntidade?): DetalheDoArtefato = DetalheDoArtefato(
            resumo = ResumoDoArtefato.de(linha),
            textoAceito = linha.textoAceito,
            conteudoMd = linha.conteudoMd,
            relatorioDeRevisao = linha.relatorioDeRevisaoJson,
            auditoriaDeLinks = RepositorioDeArtefatos.lerAuditoria(linha.auditoriaDeLinksJson),
            conteudoAnteriorMd = anterior?.conteudoMd ?: "",
        )
    }
}
