package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A regra do parâmetro de credencial ([ParametroSensivel]) contra o canônico executado: cada URL daqui passou pelas
 * funções de `web_evidence.rs` (`16a8cff`, linhas 740–864), copiadas sem alteração numa sonda com a crate `url` 2.5.8
 * e a `percent-encoding` 2.3.2, em 05/10/2026, e a resposta esperada é a dela. A exceção é o último teste, a
 * divergência decidida pelo operador (Discussion #94, decisão 4). As URLs passam pelo dublê do parser
 * ([urlDeTeste]); a regra com o parser real está no `IntegridadeComRedeTest`, do `:core:provedores`.
 */
class ParametroSensivelTest {

    /** As URLs de [urls] em que a regra não deu [esperado]: a lista vazia é o acerto. */
    private fun confere(esperado: Boolean, urls: List<String>) {
        val erradas = urls.filter { url -> ParametroSensivel.tem(assertNotNull(urlDeTeste(url), url)) != esperado }
        assertEquals(emptyList(), erradas, "a regra deveria dar $esperado")
    }

    @Test
    fun `nome de credencial na query ou no fragmento e recusado`() {
        confere(
            true,
            listOf(
                // `normalization_rejects_script_and_credentials` (`link_integrity.rs:1442-1444`).
                "https://example.com/a?access_token=secret",
                "https://example.com/a#access_token=secret",
                "https://example.com/a?utm_source=x;access_token=secret",
                "https://example.com/search?search_key=termo",
                "https://www.google.com/maps/embed/v1/place?key=AIzaFAKE&q=Brasilia",
                "https://example.com/download?signature=abc&expires=1",
                "https://example.com/a?x=1#y=2&secret=3",
                "https://example.com/a#x?token=1",
                "https://example.com/a?key",
                "https://example.com/a?sig=1",
                "https://example.com/a?x-amz-signature=1",
                "https://example.com/a?jsonwebtoken=1",
                "https://example.com/a?userpassword=x",
                "https://example.com/a?my.secret=1",
                "mailto:editor@example.com?token=abc",
            ),
        )
    }

    @Test
    fun `o nome da query e decodificado duas vezes antes da regra`() {
        // A primeira decodificação é a do `query_pairs`: `+` vira espaço, e o UTF-8 inválido vira U+FFFD. A segunda
        // decodifica até quatro camadas, em UTF-8 estrito, e o que não se decodifica é sensível.
        confere(
            true,
            listOf(
                "https://example.com/a?a+key=1",
                "https://example.com/a?api%5Fkey=1",
                "https://example.com/a?access%255Ftoken=1",
                "https://example.com/a?%25FF=1",
                "https://example.com/a?%252525252541=1",
            ),
        )
        confere(
            false,
            listOf(
                "https://example.com/a?%FF=1",
                "https://example.com/a?%2525252541=1",
                "https://example.com/a?%25252541=1",
            ),
        )
    }

    @Test
    fun `no caminho conta a chave com valor no mesmo segmento, e o que nao se decodifica`() {
        confere(
            true,
            listOf(
                "https://example.com/api_key=abc/x",
                "https://example.com/token:abc",
                "https://example.com/a+key=1",
                "https://example.com/a%5Capi_key=1",
                "https://example.com/a%2Fapi_key=1",
                "https://example.com/%FF",
                "https://example.com/%2525252541",
            ),
        )
        confere(
            false,
            listOf(
                "https://example.com/token:",
                "https://example.com/%25252541",
                "https://example.com/%E2%82%AC",
            ),
        )
    }

    @Test
    fun `nomes comuns e valores nao sao credencial`() {
        confere(
            false,
            listOf(
                "https://example.com/a",
                "https://example.com/source#monkey",
                "https://example.com/a?MONKEY=1",
                "https://example.com/a?config=1",
                "https://example.com/a?tokens=1",
                "https://example.com/a?q=api_key",
                "https://example.com/a?;;",
                "mailto:editor@example.com",
                "mailto:editor@example.com?subject=api",
                "https://example.com/token",
                "https://example.com/token/",
                "https://example.com/monkey/business",
                "https://developer.mozilla.org/en-US/docs/Web/API/SubtleCrypto/importKey",
                "https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Authorization",
                "https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Authorization#syntax",
                "https://en.wikipedia.org/wiki/Public-key_cryptography",
                "https://en.wikipedia.org/wiki/Key_(cryptography)",
                "https://en.wikipedia.org/wiki/Turkey",
                "https://datatracker.ietf.org/doc/html/rfc6750",
                "https://docs.python.org/3/library/secrets.html#module-secrets",
                "https://learn.microsoft.com/en-us/azure/key-vault/general/overview",
                "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/" +
                    "managing-your-personal-access-tokens",
            ),
        )
    }

    @Test
    fun `delimitador codificado no nome, sufixo sig e as palavras comuns`() {
        // Vetores que cada ramo da regra precisa (a matriz de desarme os usa): o delimitador codificado dentro do nome
        // não esconde a credencial (`sensitive_query_key` e `sensitive_parameter_name` cortam depois de decodificar),
        // o sufixo `sig` é sensível, e as palavras comuns em `key` não são.
        confere(
            true,
            listOf(
                "https://example.com/a?a%26access_token=1",
                "https://example.com/a?x%3Dtoken=1",
                "https://example.com/a?x%3Atoken=1",
                "https://example.com/a?x%2Ftoken=1",
                "https://example.com/a?a%3B%23secret=1",
                // O nome sensível à ESQUERDA do delimitador codificado: só o corte o encontra.
                "https://example.com/a?access_token%26x=1",
                "https://example.com/a?secret%3Bx=1",
                "https://example.com/a?token%3Dx=1",
                "https://example.com/a?password%3Ax=1",
                "https://example.com/a?secret%2Fx=1",
                "https://example.com/token%3Dx=1/y",
                "https://example.com/a?xsig=1",
                "https://example.com/a?%2Bkey=1",
                "https://example.com/a?my+token=1",
            ),
        )
        confere(
            false,
            listOf(
                "https://example.com/a?hockey=1",
                "https://example.com/a?whiskey=1",
                "https://example.com/a?jockey=1",
                "https://example.com/a?turkey=1",
            ),
        )
    }

    // O `%` solto (`?50%=1`, `/sale/50%`), única divergência da decodificação nativa, está no
    // `AnalisadorDeUrlOkHttpTest`: o `java.net.URI` deste dublê nem aceita essa URL; o parser real e o canônico aceitam.

    @Test
    fun `divergencia decidida, o segmento seguinte nao e valor de credencial`() {
        // O canônico recusa todas estas (`true` na sonda), pelo ramo que lê o segmento seguinte ao nome de credencial
        // como o valor dela. O porte não tem esse ramo (Discussion #94, decisão 4; maestro-app#432).
        confere(
            false,
            listOf(
                "https://developer.mozilla.org/en-US/docs/Web/API/CryptoKey/type",
                "https://developer.mozilla.org/en-US/docs/Web/API/PublicKeyCredential/id",
                "https://github.com/auth0/node-jsonwebtoken/blob/master/README.md",
                "https://www.npmjs.com/package/jsonwebtoken/v/9.0.2",
                "https://example.com/password/reset",
                "https://example.com/reset-password/step-2",
                "https://example.com/api-token/overview",
                "https://example.com/books/the-secret/chapter-1",
                "https://example.com/token=/x",
            ),
        )
    }
}
