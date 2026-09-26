package dev.lcv.maestro.sessao

import com.fasterxml.jackson.databind.JsonNode
import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Provedor
import java.math.BigDecimal

/**
 * As tarifas por provedor (`ProviderRates`, `DEFAULT_RATES`, `sanitizeRates`,
 * `hasPositiveRates`; `sessions.ts:40-44, 265-275, 387-410, 725-734`), em
 * `BigDecimal` (especificação, seção 7).
 */
public object Taxas {

    /** `DEFAULT_RATES` (25/09/2026, seis peers nativos). */
    public val PADRAO: Map<Provedor, Custo.Taxas> = mapOf(
        Provedor.CLAUDE to Custo.Taxas(BigDecimal("10"), BigDecimal("50"), BigDecimal.ZERO),
        Provedor.CODEX to Custo.Taxas(BigDecimal("10"), BigDecimal("50"), BigDecimal.ZERO),
        Provedor.GEMINI to Custo.Taxas(BigDecimal("2"), BigDecimal("12"), BigDecimal.ZERO),
        Provedor.DEEPSEEK to Custo.Taxas(BigDecimal("1.32"), BigDecimal("3.96"), BigDecimal.ZERO),
        Provedor.GROK to Custo.Taxas(BigDecimal("2"), BigDecimal("6"), BigDecimal.ZERO),
        Provedor.PERPLEXITY to Custo.Taxas(BigDecimal("0.25"), BigDecimal("2.5"), BigDecimal("14")),
    )

    /** `hasPositiveRates`: entrada e saída finitas e maiores que zero. */
    public fun positivas(taxas: Custo.Taxas?): Boolean =
        taxas != null && (taxas.entradaPorMilhao?.signum() ?: 0) > 0 && (taxas.saidaPorMilhao?.signum() ?: 0) > 0

    /**
     * `sanitizeRates`, regra a regra: valor ausente, não numérico ou não
     * positivo cai no padrão; **valor positivo abaixo do padrão sobe para o
     * padrão** (`Math.max`, `sessions.ts:398-403`); a taxa por mil requisições
     * fica se positiva, senão o padrão (zero fora da Perplexity).
     */
    public fun sanear(bruto: Map<Provedor, Custo.Taxas?>): Map<Provedor, Custo.Taxas> = Provedor.entries.associateWith { agente ->
        val padrao = PADRAO.getValue(agente)
        val taxas = bruto[agente]
        Custo.Taxas(
            entradaPorMilhao = positivoOuPadrao(taxas?.entradaPorMilhao, padrao.entradaPorMilhao!!),
            saidaPorMilhao = positivoOuPadrao(taxas?.saidaPorMilhao, padrao.saidaPorMilhao!!),
            requisicoesPorMil = taxas?.requisicoesPorMil?.takeIf { it.signum() > 0 } ?: padrao.requisicoesPorMil ?: BigDecimal.ZERO,
        )
    }

    /** `sanitizeRates(parseJson(json, defaultRates()))`: o JSON gravado, tolerante. */
    public fun lerJson(texto: String?): Map<Provedor, Custo.Taxas> = sanear(lerBruto(texto))

    /** O JSON como o web grava: `{ "claude": { "input_usd_per_million": …, … }, … }`. */
    public fun paraJson(taxas: Map<Provedor, Custo.Taxas>): String {
        val raiz = Json.ESTRITO.createObjectNode()
        for (agente in Provedor.entries) {
            val t = taxas[agente] ?: continue
            val no = raiz.putObject(agente.agente)
            t.entradaPorMilhao?.let { no.put("input_usd_per_million", it) }
            t.saidaPorMilhao?.let { no.put("output_usd_per_million", it) }
            no.put("request_usd_per_1k", t.requisicoesPorMil ?: BigDecimal.ZERO)
        }
        return Json.ESTRITO.writeValueAsString(raiz)
    }

    /** As taxas cruas de um JSON (ou de um objeto já lido), sem sanear: para `hasPositiveRates` na retomada. */
    public fun lerBruto(texto: String?): Map<Provedor, Custo.Taxas?> = lerBruto(Json.tolerante(texto))

    public fun lerBruto(raiz: JsonNode?): Map<Provedor, Custo.Taxas?> {
        if (raiz == null || !raiz.isObject) return emptyMap()
        return Provedor.entries.associateWith { agente ->
            val no = raiz.get(agente.agente)?.takeIf { it.isObject } ?: return@associateWith null
            Custo.Taxas(
                entradaPorMilhao = numero(no.get("input_usd_per_million")),
                saidaPorMilhao = numero(no.get("output_usd_per_million")),
                requisicoesPorMil = numero(no.get("request_usd_per_1k")),
            )
        }
    }

    private fun positivoOuPadrao(valor: BigDecimal?, padrao: BigDecimal): BigDecimal =
        if (valor != null && valor.signum() > 0) valor.max(padrao) else padrao

    /** `Number(x)` do web: número, ou texto numérico; o resto é "não finito". */
    private fun numero(no: JsonNode?): BigDecimal? = when {
        no == null || no.isNull -> null
        no.isNumber -> no.decimalValue()
        no.isTextual -> no.textValue().trim().toBigDecimalOrNull()
        else -> null
    }
}
