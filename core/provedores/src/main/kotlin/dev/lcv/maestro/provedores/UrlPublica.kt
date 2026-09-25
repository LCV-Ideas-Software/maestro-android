package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * `validate_public_url` e vizinhos (`web_evidence.rs:661-704`, `:723-727`,
 * `:857-863`): o que uma URL precisa ser para a coleta a tocar. A regra de
 * rede pública em si é da [dev.lcv.maestro.protocolo.RedePublica]; aqui
 * ela chega por [PoliticaDeRede], para os testes correrem num servidor local
 * que a regra recusaria.
 */
internal object UrlPublica {

    /** `public_http_url_rejection_reason`: o motivo da recusa, ou `null`. */
    fun interface PoliticaDeRede {
        fun motivoDeRecusa(url: String): String?
    }

    /** O `value.len() > 4_096` do canônico, em bytes UTF-8. */
    const val LIMITE_EM_BYTES = 4_096

    /**
     * A URL validada, sem fragmento, ou [IntegridadeDeLinks.Falha] com a
     * mensagem do canônico. Uma regra a mais que o canônico, por decisão do
     * operador de 25/09/2026: só `https://` é coletado. O Android não envia
     * texto claro por padrão, e liberar `http://` para a auditoria liberaria
     * o aplicativo inteiro; o link em texto claro fica bloqueado com a nota
     * que diz isso.
     */
    fun validar(url: String, politica: PoliticaDeRede): HttpUrl {
        if (url.toByteArray(Charsets.UTF_8).size > LIMITE_EM_BYTES) {
            throw IntegridadeDeLinks.Falha("URL exceeds the 4096-character evidence limit")
        }
        politica.motivoDeRecusa(url)?.let { throw IntegridadeDeLinks.Falha(it) }
        val analisada = url.toHttpUrlOrNull() ?: throw IntegridadeDeLinks.Falha("URL invalida ou incompleta")
        if (analisada.scheme != "https") {
            throw IntegridadeDeLinks.Falha("cleartext http:// links are not collected; only https:// is")
        }
        if (analisada.username.isNotEmpty() || AnalisadorDeUrlOkHttp.temSenha(url)) {
            throw IntegridadeDeLinks.Falha("URLs with embedded credentials are blocked")
        }
        if (analisada.queryParameterNames.any(::chaveSensivel)) {
            throw IntegridadeDeLinks.Falha(
                "URLs with credential-like query parameters are blocked; use an environment-backed connector or " +
                    "operator capture",
            )
        }
        return analisada.newBuilder().fragment(null).build()
    }

    /** `sensitive_query_key`. */
    fun chaveSensivel(nome: String): Boolean {
        val normalizado = nome.lowercase(Locale.ROOT)
        return CHAVES_SENSIVEIS.any { normalizado == it || normalizado.endsWith(it) }
    }

    /** `same_origin`: esquema, host e porta (a porta padrão já preenchida). */
    fun mesmaOrigem(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /** `robots_url_for`: o `/robots.txt` da mesma origem, validado como qualquer URL. */
    fun robotsDe(url: HttpUrl, politica: PoliticaDeRede): HttpUrl =
        validar(url.newBuilder().encodedPath("/robots.txt").query(null).fragment(null).build().toString(), politica)

    private val CHAVES_SENSIVEIS = listOf(
        "access_token", "api_key", "apikey", "authorization", "credential", "key", "password", "secret",
        "signature", "sig", "token", "x-amz-credential", "x-amz-signature",
    )
}
