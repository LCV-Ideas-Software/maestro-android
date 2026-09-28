package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.Provedor
import java.time.Instant

/**
 * A reconciliação na abertura do aplicativo (especificação, seção 4.3): toda
 * sessão ainda em `queued`/`running` sem trabalho vivo no WorkManager é
 * marcada como interrompida — a execução que ela tinha é fechada na mesma
 * transação, cercada pela execução inspecionada — e, se nenhuma chamada
 * paga estava em voo, é pedida de novo e reenfileirada sem que o usuário
 * redigite nada (emenda A8 do plano da 3a). Uma execução morta com o
 * marcador da chamada paga (decisão 16 do operador, 27/09/2026) fica em
 * `error` com a mensagem que diz por quê, e só o operador a retoma.
 *
 * `pedir` e `enfileirar` só acontecem depois que `marcarInterrompida` gravou:
 * um cancelamento ou uma pausa que chegue entre a leitura e a escrita não é
 * desfeito. No fim, os arquivos órfãos de evidências e anexos são limpos.
 */
public class Reconciliacao(
    private val banco: BancoDaSessao,
    private val sessoes: RepositorioDeSessoes,
    private val retomada: Retomada,
    private val agendador: Agendador,
    private val chaves: suspend () -> Map<Provedor, Boolean?>,
    private val evidencias: ArmazemDeEvidenciasEmArquivo,
    private val anexos: AnexosDaSessao,
    private val relogio: () -> Instant,
) {
    /** Bloqueante no WorkManager e no Room: chamar em `Dispatchers.IO`. Devolve os ids reenfileirados. */
    public suspend fun naAbertura(): List<String> {
        val reenfileiradas = ArrayList<String>()
        var chavesLidas: Map<Provedor, Boolean?>? = null
        for (linha in sessoes.emExecucao()) {
            if (agendador.viva(linha.id)) continue
            val execucaoMorta = linha.execucaoAtual?.let { banco.execucoes().uma(it) }
            val emVoo = execucaoMorta?.takeIf { it.fim == null }?.chamadaEmVoo?.let(Agentes::porChave)
            val parada = agendador.ultimaParada(linha.id)?.let { " (${Agendador.rotuloDaParada(it)})" } ?: ""
            val mensagem = if (emVoo != null) RepositorioDeSessoes.mensagemDeChamadaIndeterminada(emVoo) else RepositorioDeSessoes.MENSAGEM_INTERROMPIDA
            val evento = EventoDaSessao(em = FormatoDeInstante.iso(relogio()), agente = emVoo, status = EventoDaSessao.ERRO, mensagem = mensagem + parada)
            if (!sessoes.marcarInterrompida(linha.id, linha.execucaoAtual, evento, mensagem)) continue
            if (emVoo != null) continue
            val chavesDoCofre = chavesLidas ?: chaves().also { chavesLidas = it }
            when (val pedido = retomada.pedir(linha.id, null, null, chavesDoCofre)) {
                is Resultado.Ok -> {
                    agendador.enfileirar(linha.id)
                    reenfileiradas += linha.id
                }
                is Resultado.Recusado -> sessoes.anotar(
                    linha.id,
                    EventoDaSessao(em = FormatoDeInstante.iso(relogio()), status = EventoDaSessao.ERRO, mensagem = "$MENSAGEM_RETOMADA_RECUSADA${pedido.mensagem}"),
                    seSituacaoEm = Estados.RETOMAVEIS,
                )
            }
        }
        evidencias.limparOrfaos()
        anexos.limparOrfaos()
        return reenfileiradas
    }

    public companion object {
        public const val MENSAGEM_RETOMADA_RECUSADA: String = "Retomada automatica recusada: "
    }
}
