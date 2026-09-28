package dev.lcv.maestro.sessao

import java.time.Duration
import java.time.Instant

/**
 * O orçamento agregado do `dataSync` (especificação, seção 4.1): seis horas
 * por 24 horas, contadas sobre todas as execuções do aplicativo, e não por
 * sessão. A tela lê o saldo para avisar antes de uma segunda sessão longa
 * morrer sem explicação. Função pura sobre as linhas de `execucoes`; quem
 * as lê é [RepositorioDeSessoes.execucoesNaJanela].
 */
public object Orcamento {
    public val JANELA: Duration = Duration.ofHours(24)
    public val TETO: Duration = Duration.ofHours(6)

    /**
     * O tempo que ainda cabe: [TETO] menos o que as execuções gastaram dentro
     * da janela que termina em [agora]. Uma execução ainda aberta conta até
     * [agora]; o que ficou fora da janela não conta; uma data ilegível conta
     * como zero. Nunca negativo.
     */
    public fun restanteNaJanela(execucoes: List<ExecucaoEntidade>, agora: Instant): Duration {
        val inicioDaJanela = agora.minus(JANELA)
        var gasto = Duration.ZERO
        for (execucao in execucoes) {
            val inicio = FormatoDeInstante.ler(execucao.inicio) ?: continue
            val fim = execucao.fim?.let { FormatoDeInstante.ler(it) ?: continue } ?: agora
            val de = maxOf(inicio, inicioDaJanela)
            val ate = minOf(fim, agora)
            if (ate > de) gasto = gasto.plus(Duration.between(de, ate))
        }
        val restante = TETO.minus(gasto)
        return if (restante.isNegative) Duration.ZERO else restante
    }
}
