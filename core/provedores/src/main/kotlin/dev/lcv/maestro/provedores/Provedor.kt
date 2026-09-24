package dev.lcv.maestro.provedores

/**
 * Os seis provedores, cada um com o modelo e o endereço fixados na
 * especificação (seção 5.1), reconferidos na documentação oficial em
 * 23/09/2026.
 *
 * O modelo é fixo, e não resolvido pela lista `/models` de cada provedor como
 * o web e o desktop fazem: a decisão do operador de 22/09/2026 (ADMIAPP-33) é
 * um ID atual por provedor, e ausência no catálogo não autoriza cair para uma
 * geração anterior.
 */
public enum class Provedor(
    /** A chave do agente no protocolo editorial (`claude`, `codex`…). */
    public val agente: String,
    public val modelo: String,
    internal val endereco: String,
) {
    CLAUDE("claude", "claude-fable-5-1", "https://api.anthropic.com/v1/messages"),
    CODEX("codex", "gpt-6-astra", "https://api.openai.com/v1/responses"),

    /**
     * `v1beta`, e não `v1beta2` como a especificação dizia até 23/09/2026: é
     * o endereço do exemplo REST oficial da Interactions API.
     */
    GEMINI(
        "gemini",
        "gemini-3.1-pro-preview",
        "https://generativelanguage.googleapis.com/v1beta/interactions",
    ),
    DEEPSEEK("deepseek", "deepseek-v4-pro", "https://api.deepseek.com/chat/completions"),
    GROK("grok", "grok-4.7", "https://api.x.ai/v1/responses"),

    /**
     * O modelo próprio da Perplexity que a Agent API lista, com o preset
     * `xhigh` (decisão do operador de 22/09/2026, ADMIAPP-33).
     */
    PERPLEXITY("perplexity", "perplexity/sonar", "https://api.perplexity.ai/v1/agent"),
}

/**
 * De onde vem a chave de API de cada provedor. Este módulo não conhece o
 * Android Keystore: quem implementa esta interface sobre ele é o
 * `:core:seguranca`, e em teste ela é um valor em memória (seção 4).
 */
public fun interface FonteDeChave {
    public suspend fun chaveDe(provedor: Provedor): LeituraDaChave
}

/**
 * O que a fonte encontrou. "Não consegui a chave" não é um caso só: a seção
 * 4.2 da especificação separa três causas, com três respostas ao usuário, e
 * juntá-las mandaria redigitar uma chave que está intacta. Em nenhuma das três
 * alguma requisição é feita.
 */
public sealed interface LeituraDaChave {

    /**
     * A chave, em claro. Só existe em memória, durante a montagem da chamada.
     * Espaço nas pontas é descartado, e texto só com espaço conta como
     * [Ausente]; caractere que não pode ir num cabeçalho HTTP devolve
     * [Resultado.ChaveInvalida].
     */
    public class Presente(public val valor: String) : LeituraDaChave {
        /** Nunca o valor: o objeto pode acabar num registro ou numa mensagem. */
        override fun toString(): String = "Presente(<redacted>)"
    }

    /**
     * Não há chave configurada, ou o cifrado ficou órfão — o caso do backup
     * restaurado em outro aparelho. Vira [Resultado.SemChave].
     */
    public data object Ausente : LeituraDaChave

    /**
     * A janela de autenticação expirou (`UserNotAuthenticatedException`). A
     * chave está intacta. Vira [Resultado.ExigeAutenticacao].
     */
    public data object ExigeAutenticacao : LeituraDaChave

    /**
     * A chave do Keystore foi invalidada em definitivo
     * (`KeyPermanentlyInvalidatedException`), e o segredo cifrado com ela não
     * volta mais. Vira [Resultado.SegredoIrrecuperavel].
     */
    public data object Irrecuperavel : LeituraDaChave

    /**
     * A fonte não conseguiu ler a chave agora — Keystore ocupado, falha do
     * disco ou do próprio Keystore —, sem sinal de que ela se perdeu. Vira
     * [Resultado.ChaveIndisponivel]: tentar de novo, e nunca pedir a chave.
     */
    public data object Indisponivel : LeituraDaChave
}
