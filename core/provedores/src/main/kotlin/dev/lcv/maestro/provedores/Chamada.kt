package dev.lcv.maestro.provedores

import java.math.BigDecimal

/**
 * Teto de saída de cada chamada, raciocínio incluído. O canônico usa 20 mil
 * tokens, pensados para o esforço padrão. Com o raciocínio no máximo, o
 * operador decidiu em 23/09/2026 pelos 64 mil que a Anthropic documenta como
 * ponto de partida para `max`. Cabe no teto de saída de cada modelo: Claude
 * Fable 5.1 e GPT-6 Astra 128 mil, Gemini 3.1 Pro 65.536, DeepSeek 384 mil,
 * Grok 4.7 128 mil por padrão. A Perplexity não publica o teto do
 * `perplexity/sonar`.
 */
public const val MAX_TOKENS_DE_SAIDA: Int = 64_000

/** O que se pede a um provedor num turno. */
public data class Pedido(
    val sistema: String,
    val prompt: String,
    val maxTokensDeSaida: Int = MAX_TOKENS_DE_SAIDA,
)

/**
 * O uso que o provedor devolveu. Cada campo é `null` quando o provedor não o
 * informou; quem calcula o custo decide o que fazer (`Custo.observar`, no
 * `:core:protocolo`, estima pelo tamanho do texto).
 *
 * [tokensDeSaida] já inclui o raciocínio, que os seis cobram como saída. No
 * Gemini ele vem separado (`total_thought_tokens`) e é somado aqui.
 */
public data class Uso(
    val tokensDeEntrada: Long?,
    val tokensDeSaida: Long?,
    val custoInformadoUsd: BigDecimal? = null,
)

/** Resultado de uma chamada. Cancelamento não é resultado: é exceção. */
public sealed interface Resultado {

    /** O provedor terminou a resposta. [texto] é só a resposta final. */
    public data class Concluida(val texto: String, val uso: Uso) : Resultado

    /**
     * O provedor respondeu, mas não terminou: estourou o teto de saída,
     * recusou ou parou por outro motivo. É cobrada como qualquer resposta, e
     * por isso carrega o uso.
     */
    public data class Incompleta(val motivo: String, val uso: Uso) : Resultado

    /** Resposta HTTP de erro, depois da política de nova tentativa. */
    public data class FalhaHttp(val status: Int, val mensagem: String) : Resultado

    /** Rede, DNS, conexão ou prazo, depois da política de nova tentativa. */
    public data class FalhaDeRede(val mensagem: String) : Resultado

    /** Resposta 2xx que não é o JSON do contrato do provedor. */
    public data class RespostaInvalida(val mensagem: String) : Resultado

    /** Não há chave configurada para o provedor; nada foi enviado. */
    public data object SemChave : Resultado

    /**
     * A janela de autenticação do usuário expirou, e nada foi enviado. A chave
     * está intacta: a sessão pausa aguardando autenticação (seção 6.2), e
     * nunca pede a chave de API.
     */
    public data object ExigeAutenticacao : Resultado

    /**
     * A chave do Keystore que cifrava o segredo foi invalidada em definitivo,
     * tipicamente por mudança da trava de tela, e nada foi enviado. O segredo
     * não volta: o usuário precisa informar a chave de API de novo, e a tela
     * diz por quê.
     */
    public data object SegredoIrrecuperavel : Resultado

    /**
     * A chave não pôde ser lida agora, por falha passageira do aparelho, e
     * nada foi enviado. Ela não se perdeu: vale tentar de novo, sem pedir a
     * chave de API ao usuário.
     */
    public data object ChaveIndisponivel : Resultado

    /**
     * A chave configurada tem caractere que não pode ir num cabeçalho HTTP
     * (controle, espaço no meio, fora do ASCII). Nada foi enviado, e o valor
     * não aparece em lugar nenhum: o usuário precisa colar a chave de novo.
     */
    public data object ChaveInvalida : Resultado
}
