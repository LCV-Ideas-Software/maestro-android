package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.Custo
import dev.lcv.maestro.protocolo.EspacoUnicode
import dev.lcv.maestro.provedores.Pedido
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado as RespostaDoProvedor

/** Uma linha do resultado do "Testar chaves" (`ApiTestResult` do web). */
public data class ResultadoDoTeste(val provedor: Provedor, val ok: Boolean, val mensagem: String)

/**
 * `handleMaestroAiSettingsTestPost` (`sessions.ts:4606-4648`): uma chamada
 * curta e paga a cada provedor com chave e tarifas, em série, na ordem de
 * [Provedor]; quem não tem chave ou tarifa não é chamado. A tela pede
 * confirmação e autenticação antes (decisão 20 do operador, 28/09/2026).
 */
public class TesteDeChaves(private val chamador: Chamador) {

    /** [taxas] são as das configurações; [chaves], o que o cofre respondeu por provedor. */
    public suspend fun testar(taxas: Map<Provedor, Custo.Taxas>, chaves: Map<Provedor, Boolean?>): List<ResultadoDoTeste> =
        Provedor.entries.map { agente ->
            when {
                chaves[agente] == null -> ResultadoDoTeste(agente, false, MENSAGEM_NAO_VERIFICAVEL)
                chaves[agente] == false -> ResultadoDoTeste(agente, false, MENSAGEM_SEM_CHAVE)
                !Taxas.positivas(taxas[agente]) -> ResultadoDoTeste(agente, false, MENSAGEM_SEM_TARIFAS)
                else -> resultado(agente, chamador.chamar(agente, Pedido(SISTEMA, PROMPT, MAX_TOKENS), null))
            }
        }

    private fun resultado(agente: Provedor, resposta: RespostaDoProvedor): ResultadoDoTeste = when (resposta) {
        // `publicApiHealthResult`: os 120 primeiros caracteres da resposta, contados
        // em pontos de código (o web corta em unidades UTF-16; aqui um emoji nunca é
        // partido ao meio, como em `Texto.sanear`). O cliente nunca devolve texto
        // vazio como concluído: a resposta sem texto chega como `Incompleta`.
        is RespostaDoProvedor.Concluida -> ResultadoDoTeste(agente, true, EspacoUnicode.primeirosPontosDeCodigo(resposta.texto, 120))
        // A resposta vazia do web ("Chamada autenticada aceita; resposta textual vazia.").
        is RespostaDoProvedor.Incompleta if resposta.motivo == RespostaDoProvedor.Incompleta.SEM_TEXTO ->
            ResultadoDoTeste(agente, true, MENSAGEM_RESPOSTA_VAZIA)
        // Só no Android, onde o cliente separa a resposta cortada pelo provedor: a
        // chamada foi autenticada e aceita, que é o que o teste quer saber.
        is RespostaDoProvedor.Incompleta -> ResultadoDoTeste(agente, true, "$MENSAGEM_INCOMPLETA ${resposta.motivo}")
        RespostaDoProvedor.ExigeAutenticacao -> ResultadoDoTeste(agente, false, MENSAGEM_EXIGE_AUTENTICACAO)
        else -> ResultadoDoTeste(agente, false, mensagemOperacional(agente, resposta))
    }

    public companion object {
        /** `API_TEST_SYSTEM` e `API_TEST_PROMPT` (`sessions.ts:300-301`). */
        public const val SISTEMA: String = "You are an API health-check endpoint. Return a short plain-text acknowledgement only."
        public const val PROMPT: String = "Reply with exactly: OK"

        /** O `maxTokens` que o web passa ao `callProvider` no teste. */
        public const val MAX_TOKENS: Int = 256

        public const val MENSAGEM_SEM_CHAVE: String = "Chave nao configurada."
        public const val MENSAGEM_SEM_TARIFAS: String = "Tarifas financeiras ausentes."
        public const val MENSAGEM_RESPOSTA_VAZIA: String = "Chamada autenticada aceita; resposta textual vazia."

        /** Só no Android: o terceiro estado do cofre (seção 6.2). */
        public const val MENSAGEM_NAO_VERIFICAVEL: String = "Nao foi possivel verificar a chave agora; tente de novo."
        public const val MENSAGEM_INCOMPLETA: String = "Chamada autenticada aceita; resposta interrompida pelo provedor:"
        public const val MENSAGEM_EXIGE_AUTENTICACAO: String = "A janela de autenticacao do aparelho venceu; confirme a identidade e teste de novo."
    }
}
