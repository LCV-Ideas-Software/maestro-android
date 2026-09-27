package dev.lcv.maestro.sessao

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Um banco por teste, **em arquivo** sob `cacheDir`, nunca em memória: o caso
 * que importa é reabrir o mesmo arquivo depois de descartar tudo
 * (especificação, seção 8). O relógio é injetado e avança um segundo por
 * leitura, para que `atualizadaEm` mude a cada escrita.
 */
internal class BancoDeTeste {
    val contexto: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val arquivo: File = File(contexto.cacheDir, "sessao-${UUID.randomUUID()}.db")
    private var instante: Instant = Instant.parse("2026-09-25T12:00:00Z")
    val relogio: () -> Instant = { instante.also { instante = instante.plusSeconds(1) } }

    var banco: BancoDaSessao = BancoDaSessao.abrir(contexto, arquivo)
        private set
    var sessoes: RepositorioDeSessoes = RepositorioDeSessoes(banco, relogio)
        private set
    var artefatos: RepositorioDeArtefatos = RepositorioDeArtefatos(banco, relogio)
        private set
    var retomada: Retomada = Retomada(banco, sessoes, artefatos, relogio)
        private set
    var ponto: PontoDeRetomada = PontoDeRetomada(banco, artefatos, relogio)
        private set

    /** Fecha o banco e reconstrói cada objeto sobre o mesmo arquivo: o processo morreu e voltou. */
    fun reabrir() {
        banco.close()
        banco = BancoDaSessao.abrir(contexto, arquivo)
        sessoes = RepositorioDeSessoes(banco, relogio)
        artefatos = RepositorioDeArtefatos(banco, relogio)
        retomada = Retomada(banco, sessoes, artefatos, relogio)
        ponto = PontoDeRetomada(banco, artefatos, relogio)
    }

    fun fechar() {
        banco.close()
        arquivo.delete()
        File(arquivo.path + "-wal").delete()
        File(arquivo.path + "-shm").delete()
    }

    /** SQL cru para adulterar uma coluna que nenhum DAO escreve fora do checkpoint: o que um ataque ao arquivo faria. */
    fun adulterar(sql: String) {
        banco.openHelper.writableDatabase.execSQL(sql)
    }

    fun entrada(
        agentes: List<Provedor> = listOf(Provedor.CLAUDE, Provedor.CODEX, Provedor.GEMINI),
        conteudoInicial: String = "",
        tetoDeMinutos: Int? = null,
    ): EntradaResolvida = EntradaResolvida(
        titulo = "Sessao de teste",
        pedido = "Escreva sobre o tema.",
        protocolo = RepositorioDeConfiguracoes.PROTOCOLO_PADRAO,
        agenteInicial = agentes.first(),
        agentesAtivos = agentes,
        conteudoInicial = conteudoInicial,
        tetoDeCustoUsd = BigDecimal("5"),
        tetoDeMinutos = tetoDeMinutos,
        taxas = Taxas.PADRAO,
        maxCiclos = 2,
    )

    fun artefato(
        sessaoId: String,
        turno: Int,
        agente: Provedor,
        papel: String = "revision",
        status: String = "ready",
        texto: String = "Texto.",
        ciclo: Int = 1,
        anteriorId: String? = null,
        auditoria: List<LinhaDeLink> = emptyList(),
    ): EntradaDeArtefato = EntradaDeArtefato(
        sessaoId = sessaoId,
        ciclo = ciclo,
        turno = turno,
        agente = agente,
        papel = papel,
        status = status,
        titulo = "Sessao de teste",
        texto = texto,
        relatorioDeRevisao = "{\"status\":\"$status\"}",
        auditoriaDeLinks = auditoria,
        custoUsd = BigDecimal("0.01"),
        artefatoAnteriorId = anteriorId,
    )

    fun evento(status: String, mensagem: String, agente: Provedor? = null): EventoDaSessao =
        EventoDaSessao(em = FormatoDeInstante.iso(relogio()), status = status, mensagem = mensagem, agente = agente)

    /** As mensagens do jornal, na ordem. */
    fun mensagens(id: String): List<String> = sessoes.eventos(id).map { it.mensagem }

    companion object {
        val TODAS_AS_CHAVES: Map<Provedor, Boolean?> = Provedor.entries.associateWith { true }
    }
}
