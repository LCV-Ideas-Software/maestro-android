package dev.lcv.maestro.sessao

import com.fasterxml.jackson.core.StreamWriteFeature
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.json.JsonMapper
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
    /**
     * A aba **Metadados** do web (`MaestroAiModule.tsx:1075-1098`): os onze
     * campos, na ordem e com os nomes do web, como `JSON.stringify(…, null, 2)`
     * os escreve — dois espaços de recuo, `"campo": valor`, `null` para o que
     * falta. O custo sai como decimal sem zeros à direita e sem expoente (o
     * JavaScript usaria expoente abaixo de 1e-6; desvio declarado).
     */
    public fun metadadosJson(): String {
        val no = METADADOS.createObjectNode()
            .put("id", id)
            .put("cycle", ciclo)
            .put("turn", turno)
            .put("agent", agente)
            .put("role", papel)
            .put("status", status)
            .put("model", modelo)
            .put("cost_usd", custoUsd.stripTrailingZeros())
            .put("previous_artifact_id", artefatoAnteriorId)
            .put("content_bytes", bytesDoConteudo)
            .put("created_at", criadoEm)
        return METADADOS.writer(IMPRESSORA).writeValueAsString(no)
    }

    public companion object {
        private val METADADOS: JsonMapper = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).build()
        private val IMPRESSORA: DefaultPrettyPrinter =
            DefaultPrettyPrinter(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(DefaultIndenter("  ", "\n"))

        public fun de(linha: ArtefatoEntidade): ResumoDoArtefato = de(
            LinhaDoResumoDoArtefato(
                id = linha.id,
                sessaoId = linha.sessaoId,
                ciclo = linha.ciclo,
                turno = linha.turno,
                agente = linha.agente,
                papel = linha.papel,
                status = linha.status,
                titulo = linha.titulo,
                custoE8 = linha.custoE8,
                modelo = linha.modelo,
                artefatoAnteriorId = linha.artefatoAnteriorId,
                bytesDoConteudo = linha.bytesDoConteudo,
                auditoriaDeLinksJson = linha.auditoriaDeLinksJson,
                criadoEm = linha.criadoEm,
            ),
        )

        public fun de(linha: LinhaDoResumoDoArtefato): ResumoDoArtefato = ResumoDoArtefato(
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

/** `publicArtifactDetail` (`sessions.ts:825-833`): o resumo mais o texto aceito, o markdown renderizado, o relatório e o anterior. */
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
            conteudoMd = MarkdownDoArtefato.doArtefato(linha),
            relatorioDeRevisao = linha.relatorioDeRevisaoJson,
            auditoriaDeLinks = RepositorioDeArtefatos.lerAuditoria(linha.auditoriaDeLinksJson),
            conteudoAnteriorMd = anterior?.let(MarkdownDoArtefato::doArtefato) ?: "",
        )
    }
}
