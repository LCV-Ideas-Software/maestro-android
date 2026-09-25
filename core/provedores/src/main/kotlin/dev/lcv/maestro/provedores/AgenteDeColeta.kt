package dev.lcv.maestro.provedores

/**
 * Quem o coletor diz ser. A identidade é a do canônico — `MaestroEditorialAI`
 * com a versão e a URL pública do repositório (`web_evidence.rs:715-718`) —
 * para que uma regra de `robots.txt` escrita para o crawler do desktop valha
 * também para o aplicativo (revisão cruzada de 25/09/2026). O que muda é a
 * plataforma entre parênteses e o repositório.
 *
 * O [emailDeContato] é do usuário do aplicativo, opcional, e só vai ao
 * Crossref, na variante polida do User-Agent, por decisão do operador de
 * 25/09/2026: nada da LCV Ideas & Software identifica o usuário em tráfego
 * nenhum. Versão e e-mail precisam caber num cabeçalho HTTP; um valor com
 * caractere fora de `0x21..0x7E` é recusado aqui, sem ser citado, porque o
 * OkHttp o citaria na mensagem da exceção.
 */
public class AgenteDeColeta(versao: String, emailDeContato: String?) {

    public val versao: String = versao.trim().also { require(cabeNumCabecalho(it)) { "versao do aplicativo invalida" } }

    public val emailDeContato: String? = emailDeContato?.trim()?.takeUnless { it.isEmpty() }
        ?.also { require(cabeNumCabecalho(it) && '@' in it) { "e-mail de contato invalido" } }

    /** O User-Agent de toda requisição da auditoria. */
    public val userAgent: String = "$PRODUTO/${this.versao} (Android; +$REPOSITORIO)"

    /** O User-Agent do "polite pool" do Crossref: o mesmo, com o contato do usuário. */
    public val userAgentPolido: String =
        this.emailDeContato?.let { "$PRODUTO/${this.versao} (Android; +$REPOSITORIO; mailto:$it)" } ?: userAgent

    /** O nome pelo qual o `robots.txt` se dirige a este crawler. */
    public val nomeDoRobo: String = NOME_DO_ROBO

    private fun cabeNumCabecalho(valor: String): Boolean = valor.isNotEmpty() && valor.all { it.code in 0x21..0x7E }

    public companion object {
        public const val PRODUTO: String = "MaestroEditorialAI"
        public const val NOME_DO_ROBO: String = "maestroeditorialai"
        public const val REPOSITORIO: String = "https://github.com/LCV-Ideas-Software/maestro-android"
    }
}
