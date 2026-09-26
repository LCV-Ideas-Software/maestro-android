package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.CofreDeChaves
import dev.lcv.maestro.sessao.Agentes.rotulo
import java.math.BigDecimal
import java.time.Instant

/** Um campo que pode vir ausente: `Object.hasOwn(body, campo)` do web. */
public sealed interface Campo<out T> {
    public data class Presente<T>(val valor: T) : Campo<T>
    public data object Ausente : Campo<Nothing>
}

/** O que uma operação devolve quando não lança: o valor, ou a recusa com a mensagem do web. */
public sealed interface Resultado<out T> {
    public data class Ok<T>(val valor: T) : Resultado<T>
    public data class Recusado(val mensagem: String) : Resultado<Nothing>
}

/** As configurações lidas (`loadSettings`, `sessions.ts:701-723`), com as taxas já saneadas. */
public data class Configuracoes(
    val protocolo: String,
    val tetoDeCustoUsd: BigDecimal,
    val tetoDeMinutos: Int?,
    val maxCiclos: Int,
    val taxas: Map<Provedor, Custo.Taxas>,
    val atualizadaEm: String,
)

/** O corpo do `PUT /settings` (`MaestroSettingsRequest`), sem chaves de API: elas são do cofre. */
public data class PedidoDeConfiguracoes(
    val protocolo: String? = null,
    val tetoDeCustoUsd: BigDecimal? = null,
    /** Presente e `null` limpa o limite; ausente mantém o atual (`sessions.ts:4512-4518`). */
    val tetoDeMinutos: Campo<Int?> = Campo.Ausente,
    val maxCiclos: Int? = null,
    val taxas: Map<Provedor, Custo.Taxas?>? = null,
)

/**
 * `configuredAgents` (`sessions.ts:744-746`) com o terceiro estado do cofre:
 * a chave existe, não existe, ou o Keystore não respondeu e a resposta é
 * "não sei" (especificação, seção 6.2).
 */
public enum class Elegibilidade { ELEGIVEL, SEM_CHAVE, SEM_TAXAS, NAO_VERIFICAVEL }

/** O corpo do `POST /sessions` (`MaestroSessionRequest`). */
public data class PedidoDeInicio(
    val pedido: String?,
    val titulo: String? = null,
    val agenteInicial: String? = null,
    val agentesAtivos: List<String?>? = null,
    val tetoDeCustoUsd: BigDecimal? = null,
    val conteudoInicial: String? = null,
)

/** `MaestroResolvedSessionInput`: o que `resolveStartRequest` entrega ao insert. */
public data class EntradaResolvida(
    val titulo: String,
    val pedido: String,
    val protocolo: String,
    val agenteInicial: Provedor,
    val agentesAtivos: List<Provedor>,
    val conteudoInicial: String,
    val tetoDeCustoUsd: BigDecimal,
    val tetoDeMinutos: Int?,
    val taxas: Map<Provedor, Custo.Taxas>,
    val maxCiclos: Int,
)

/**
 * `loadSettings`, `handleMaestroAiSettingsPut` e `resolveStartRequest`
 * (`sessions.ts:701-723, 4504-4604, 4302-4361`), regra a regra. Não há
 * `models_json`: o modelo de cada provedor é [Provedor.modelo], e a política
 * da frota é "sempre o mais novo" (sem seleção). Não há `configured_secrets`:
 * a chave é do cofre e só existe em tempo de execução.
 */
public class RepositorioDeConfiguracoes(
    private val banco: BancoDaSessao,
    private val cofre: CofreDeChaves,
    private val relogio: () -> Instant,
) {
    public fun carregar(): Configuracoes {
        val linha = banco.configuracoes().carregar()
            ?: return Configuracoes(
                protocolo = PROTOCOLO_PADRAO,
                tetoDeCustoUsd = BigDecimal.ZERO,
                tetoDeMinutos = null,
                maxCiclos = 2,
                taxas = Taxas.PADRAO,
                atualizadaEm = FormatoDeInstante.iso(relogio()),
            )
        return Configuracoes(
            protocolo = linha.protocolo,
            tetoDeCustoUsd = Dinheiro.deE8(linha.tetoDeCustoE8),
            tetoDeMinutos = linha.tetoDeMinutos?.takeIf { it > 0 },
            maxCiclos = linha.maxCiclos.takeIf { it != 0 } ?: 2,
            taxas = Taxas.lerJson(linha.taxasJson),
            atualizadaEm = linha.atualizadaEm,
        )
    }

    /** `handleMaestroAiSettingsPut`, com as mensagens do web e o teto de minutos do produto. */
    public fun salvar(pedido: PedidoDeConfiguracoes): Resultado<Configuracoes> {
        val atual = banco.configuracoes().carregar()
        val protocolo = Texto.sanear(pedido.protocolo ?: atual?.protocolo ?: PROTOCOLO_PADRAO, 160_000)
        val tetoDeCustoUsd = pedido.tetoDeCustoUsd ?: atual?.let { Dinheiro.deE8(it.tetoDeCustoE8) } ?: BigDecimal.ZERO
        val limiteBruto = when (val campo = pedido.tetoDeMinutos) {
            is Campo.Presente -> campo.valor
            Campo.Ausente -> atual?.tetoDeMinutos
        }
        // `null` e `0` limpam o limite (`sessions.ts:4516-4518`); um valor negativo
        // não é "limpar", é entrada inválida, e cai na mesma recusa da faixa —
        // o web o trataria como `null` (desvio declarado; achado do Codex na #67).
        if (limiteBruto != null && limiteBruto < 0) {
            return Resultado.Recusado("Limite de tempo opcional deve ficar entre 1 e $TETO_DE_MINUTOS minutos.")
        }
        val tetoDeMinutos = limiteBruto?.takeIf { it > 0 }
        val maxCiclos = pedido.maxCiclos ?: atual?.maxCiclos ?: 2
        if (protocolo.length < 100) {
            return Resultado.Recusado("Protocolo editorial integral deve ter pelo menos 100 caracteres.")
        }
        if (tetoDeCustoUsd.signum() <= 0) return Resultado.Recusado("Teto financeiro em USD deve ser positivo.")
        if (maxCiclos < 1 || maxCiclos > 5) return Resultado.Recusado("Ciclos maximos devem ser um inteiro entre 1 e 5.")
        if (tetoDeMinutos != null && (tetoDeMinutos < 1 || tetoDeMinutos > TETO_DE_MINUTOS)) {
            return Resultado.Recusado("Limite de tempo opcional deve ficar entre 1 e $TETO_DE_MINUTOS minutos.")
        }
        val taxas = pedido.taxas?.let(Taxas::sanear) ?: Taxas.lerJson(atual?.taxasJson)
        val linha = ConfiguracoesEntidade(
            protocolo = protocolo,
            tetoDeCustoE8 = Dinheiro.paraE8(tetoDeCustoUsd),
            tetoDeMinutos = tetoDeMinutos,
            maxCiclos = maxCiclos,
            taxasJson = Taxas.paraJson(taxas),
            atualizadaEm = FormatoDeInstante.iso(relogio()),
        )
        banco.configuracoes().gravar(linha)
        return Resultado.Ok(carregar())
    }

    /** `Boolean(secretForAgent(env, agent))` com o terceiro estado do cofre, provedor a provedor. */
    public suspend fun chaves(): Map<Provedor, Boolean?> = Provedor.entries.associateWith { cofre.configurada(it) }

    public companion object {
        /**
         * Teto de produto para `tetoDeMinutos`: cinco horas, uma de folga sob o
         * orçamento agregado de seis horas por 24 horas do `dataSync`, para uma
         * segunda sessão no mesmo dia não morrer sem aviso (decisão do operador
         * de 25/09/2026; o web aceita até 720). Revisável quando sessões reais
         * forem medidas.
         */
        public const val TETO_DE_MINUTOS: Int = 300

        /** `DEFAULT_PROTOCOL` (`sessions.ts:314-324`). */
        public const val PROTOCOLO_PADRAO: String = """# Maestro Editorial Protocol

Internal agent coordination must be in en_US.
Only the operator-facing final text must be delivered in pt_BR.

No agent may review or revise its own immediately produced text.
The work proceeds as a serial circular review-rewrite chain.
Each reviewer must focus only on cited defects, blockers, or protocol-grounded corrections.
Approved content is locked and must not be restyled, shortened, broadened, reordered, simplified, or rewritten without a concrete editorial defect.
Weaker agents must not impoverish stronger prose. Preserve breadth, depth, nuance, articulation, and reflexive structure unless a narrow correction is mandatory.
Do not reproduce this protocol in artifacts. Read it, obey it, and cite only the specific rule basis in the revision report."""

        /** `configuredAgents`: chave presente **e** taxas positivas; os outros estados dizem por que não. */
        public fun elegibilidade(taxas: Map<Provedor, Custo.Taxas?>, chaves: Map<Provedor, Boolean?>): Map<Provedor, Elegibilidade> =
            Provedor.entries.associateWith { agente ->
                when {
                    chaves[agente] == null -> Elegibilidade.NAO_VERIFICAVEL
                    chaves[agente] == false -> Elegibilidade.SEM_CHAVE
                    !Taxas.positivas(taxas[agente]) -> Elegibilidade.SEM_TAXAS
                    else -> Elegibilidade.ELEGIVEL
                }
            }

        /** `resolveStartRequest`, com [configuracoes] e [chaves] já lidas. */
        public fun resolverInicio(
            pedido: PedidoDeInicio,
            configuracoes: Configuracoes,
            chaves: Map<Provedor, Boolean?>,
        ): Resultado<EntradaResolvida> {
            val taxas = configuracoes.taxas
            val titulo = Texto.sanear(pedido.titulo?.takeIf { it.isNotEmpty() } ?: "Sessao Maestro AI", 200)
            val texto = Texto.sanear(pedido.pedido, 40_000)
            val protocolo = Texto.sanear(configuracoes.protocolo, 160_000)
            val elegibilidade = elegibilidade(taxas, chaves)
            val elegiveis = Provedor.entries.filter { elegibilidade[it] == Elegibilidade.ELEGIVEL }
            val agenteInicial = Agentes.sanear(pedido.agenteInicial, elegiveis.firstOrNull() ?: Provedor.CLAUDE)
            val pedidos = pedido.agentesAtivos?.takeIf { it.isNotEmpty() } ?: elegiveis.map { it.agente }
            val agentesAtivos = Agentes.sanearLista(pedidos, agenteInicial).filter { it in elegiveis }
            val tetoDeCustoUsd = pedido.tetoDeCustoUsd ?: configuracoes.tetoDeCustoUsd
            val tetoDeMinutos = configuracoes.tetoDeMinutos?.takeIf { it > 0 }
            val maxCiclos = configuracoes.maxCiclos
            if (texto.isEmpty()) return Resultado.Recusado("Prompt editorial obrigatorio.")
            if (protocolo.length < 100) {
                return Resultado.Recusado("Configure e salve o protocolo editorial integral antes de iniciar.")
            }
            if (agentesAtivos.size < 2) {
                return Resultado.Recusado("Configure pelo menos dois agentes com chave e tarifas antes de iniciar.")
            }
            if (tetoDeCustoUsd.signum() <= 0) {
                return Resultado.Recusado("Teto financeiro em USD e obrigatorio nas configuracoes ou na sessao.")
            }
            if (maxCiclos < 1 || maxCiclos > 5) {
                return Resultado.Recusado("Ciclos maximos devem estar entre 1 e 5 nas configuracoes.")
            }
            for (agente in agentesAtivos) {
                if (chaves[agente] != true) return Resultado.Recusado("${agente.rotulo} sem chave configurada neste aparelho.")
                if (!Taxas.positivas(taxas[agente])) {
                    return Resultado.Recusado("Configure tarifas de entrada e saida para ${agente.rotulo}.")
                }
            }
            return Resultado.Ok(
                EntradaResolvida(
                    titulo = titulo,
                    pedido = texto,
                    protocolo = protocolo,
                    agenteInicial = if (agenteInicial in agentesAtivos) agenteInicial else agentesAtivos.first(),
                    agentesAtivos = agentesAtivos,
                    conteudoInicial = Texto.sanear(pedido.conteudoInicial, 120_000),
                    tetoDeCustoUsd = tetoDeCustoUsd,
                    tetoDeMinutos = tetoDeMinutos,
                    taxas = taxas,
                    maxCiclos = maxCiclos,
                ),
            )
        }
    }
}
