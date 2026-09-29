package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.provedores.Pedido
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado
import dev.lcv.maestro.provedores.Uso
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `handleMaestroAiSettingsTestPost`: quem é chamado, com que pedido, e a mensagem de cada desfecho. */
class TesteDeChavesTest {

    private val uso = Uso(tokensDeEntrada = 10, tokensDeSaida = 2)
    private val todas = Provedor.entries.associateWith<Provedor, Boolean?> { true }

    private fun testar(
        chaves: Map<Provedor, Boolean?> = todas,
        taxas: Map<Provedor, Custo.Taxas> = Taxas.PADRAO,
        resposta: (Provedor) -> Resultado,
    ): Pair<List<ResultadoDoTeste>, List<Pair<Provedor, Pedido>>> {
        val chamadas = mutableListOf<Pair<Provedor, Pedido>>()
        val resultados = runBlocking {
            TesteDeChaves { provedor, pedido, tempoRestante ->
                assertEquals(null, tempoRestante)
                chamadas += provedor to pedido
                resposta(provedor)
            }.testar(taxas, chaves)
        }
        return resultados to chamadas
    }

    @Test
    fun `chama cada provedor configurado uma vez com o pedido do web`() {
        val (resultados, chamadas) = testar { Resultado.Concluida("OK", uso) }
        assertEquals(Provedor.entries, chamadas.map { it.first })
        chamadas.forEach { (_, pedido) ->
            assertEquals(Pedido(TesteDeChaves.SISTEMA, TesteDeChaves.PROMPT, 256), pedido)
        }
        assertEquals(Provedor.entries.map { ResultadoDoTeste(it, true, "OK") }, resultados)
    }

    @Test
    fun `sem chave sem tarifa ou sem resposta do cofre nao ha chamada paga`() {
        val chaves = todas + mapOf(Provedor.CLAUDE to false, Provedor.CODEX to null)
        val taxas = Taxas.PADRAO + (Provedor.GEMINI to Custo.Taxas(BigDecimal.ZERO, BigDecimal("12")))
        val (resultados, chamadas) = testar(chaves, taxas) { Resultado.Concluida("OK", uso) }
        assertEquals(listOf(Provedor.DEEPSEEK, Provedor.GROK, Provedor.PERPLEXITY), chamadas.map { it.first })
        assertEquals(ResultadoDoTeste(Provedor.CLAUDE, false, "Chave nao configurada."), resultados[0])
        assertEquals(ResultadoDoTeste(Provedor.CODEX, false, TesteDeChaves.MENSAGEM_NAO_VERIFICAVEL), resultados[1])
        assertEquals(ResultadoDoTeste(Provedor.GEMINI, false, "Tarifas financeiras ausentes."), resultados[2])
    }

    @Test
    fun `resposta longa fica nos 120 primeiros e a concluida sem texto tem a frase do web`() {
        val longa = "a".repeat(119) + "😀" + "b"
        val (resultados, _) = testar { if (it == Provedor.CLAUDE) Resultado.Concluida(longa, uso) else Resultado.Incompleta(Resultado.Incompleta.SEM_TEXTO, uso) }
        assertEquals("a".repeat(119) + "😀", resultados[0].mensagem)
        assertEquals(ResultadoDoTeste(Provedor.CODEX, true, TesteDeChaves.MENSAGEM_RESPOSTA_VAZIA), resultados[1])
    }

    @Test
    fun `falhas levam a mensagem operacional da sessao e a janela vencida pede autenticacao`() {
        val (resultados, _) = testar {
            when (it) {
                Provedor.CLAUDE -> Resultado.FalhaHttp(401, "PROVIDER_HTTP_401: invalid x-api-key")
                Provedor.CODEX -> Resultado.ExigeAutenticacao
                Provedor.GEMINI -> Resultado.SegredoIrrecuperavel
                Provedor.DEEPSEEK -> Resultado.Incompleta("max_output_tokens", uso)
                else -> Resultado.FalhaDeRede("PROVIDER_NETWORK_ERROR: timeout")
            }
        }
        assertEquals(ResultadoDoTeste(Provedor.CLAUDE, false, "PROVIDER_HTTP_401: invalid x-api-key"), resultados[0])
        assertEquals(ResultadoDoTeste(Provedor.CODEX, false, TesteDeChaves.MENSAGEM_EXIGE_AUTENTICACAO), resultados[1])
        assertEquals(
            ResultadoDoTeste(Provedor.GEMINI, false, "Gemini: a chave guardada foi invalidada pelo aparelho; informe a chave de novo."),
            resultados[2],
        )
        assertEquals(ResultadoDoTeste(Provedor.DEEPSEEK, true, "${TesteDeChaves.MENSAGEM_INCOMPLETA} max_output_tokens"), resultados[3])
        assertEquals(ResultadoDoTeste(Provedor.GROK, false, "PROVIDER_NETWORK_ERROR: timeout"), resultados[4])
    }
}
