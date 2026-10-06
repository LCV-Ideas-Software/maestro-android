package dev.lcv.maestro.protocolo

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * A regra do parâmetro de credencial na URL: porte de `url_has_sensitive_parameters` e das funções que ela chama
 * (`maestro-app/src-tauri/src/web_evidence.rs`, linhas 740–864, em `16a8cff`; #77), com uma divergência, por decisão
 * do operador de 05/10/2026 (Discussion #94, decisão 4; o defeito está registrado no canônico, maestro-app#432):
 *
 * - no caminho, só conta como credencial o segmento que traz a chave e o valor juntos (`api_key=…` ou `token:…`). O
 *   canônico também contava o segmento com nome de credencial seguido de qualquer outro segmento, como se o par fosse
 *   `chave/valor`, e recusava URLs legítimas: `…/node-jsonwebtoken/blob/…` no GitHub, `…/CryptoKey/type` no MDN,
 *   `…/password/reset` (medido por execução das funções do canônico, em 05/10/2026);
 * - a query e o fragmento seguem o canônico: cada pedaço entre `;`, `?` e `#` é lido como `chave=valor&…`, e basta
 *   uma chave sensível.
 *
 * A URL chega analisada ([IntegridadeDeLinks.UrlAnalisada]), com o caminho, a query e o fragmento ainda codificados,
 * que são o que o canônico lê da `Url`. Na dúvida, a regra falha fechada: o nome que não se decodifica é sensível.
 *
 * É uma regra só para os dois portões, como no canônico: a normalização da auditoria ([IntegridadeDeLinks.normalizar],
 * `normalize_url`) e a validação de toda URL que toca a rede, salto de redirecionamento incluído (`UrlPublica.validar`
 * no `:core:provedores`, `validate_public_url`). Pública por isso, como a [RedePublica].
 */
public object ParametroSensivel {

    /** `url_has_sensitive_parameters`, sem o ramo do segmento seguinte. */
    public fun tem(url: IntegridadeDeLinks.UrlAnalisada): Boolean {
        url.segmentosDoCaminho?.let { segmentos ->
            val decodificados = mutableListOf<String>()
            for (segmento in segmentos) {
                val decodificado = decodificar(segmento) ?: return true
                decodificados += decodificado.split('/', '\\')
            }
            val comChaveEValor = decodificados.any { segmento ->
                val corte = segmento.indexOfAny(charArrayOf('=', ':'))
                corte >= 0 && corte + 1 < segmento.length && chaveSensivel(segmento.substring(0, corte))
            }
            if (comChaveEValor) return true
        }
        return listOfNotNull(url.query, url.fragmento)
            .flatMap { it.split(';', '?', '#') }
            .any { parte -> nomesDaQuery(parte).any { nome -> nome == null || chaveSensivel(nome) } }
    }

    /** `sensitive_query_key`: o nome que não se decodifica é sensível. */
    private fun chaveSensivel(valor: String): Boolean {
        val decodificado = decodificar(valor) ?: return true
        return decodificado.split('&', ';', '?', '#').any(::nomeSensivel)
    }

    /** `sensitive_parameter_name`: um delimitador codificado dentro do nome não esconde um sufixo de credencial. */
    private fun nomeSensivel(valor: String): Boolean = valor.split('=', ':', '/', '\\').any(::trechoSensivel)

    /** `sensitive_parameter_segment`. */
    private fun trechoSensivel(valor: String): Boolean {
        val normalizado = EspacoUnicode.caixaBaixaAscii(valor)
        if ((normalizado.endsWith("key") && normalizado !in PALAVRAS_COMUNS_EM_KEY) || normalizado.endsWith("sig")) {
            return true
        }
        return CANDIDATOS.any { candidato ->
            normalizado == candidato ||
                (candidato in SUFIXOS && normalizado.endsWith(candidato)) ||
                SEPARADORES.any { separador -> normalizado.endsWith("$separador$candidato") }
        }
    }

    /**
     * `decode_parameter_component`: decodifica até quatro camadas de `%XX`, em UTF-8 estrito. Mais camadas são
     * ambíguas, e o nome que ainda tem um `%XX` depois da quarta é recusado (`null`).
     */
    private fun decodificar(valor: String): String? {
        var decodificado = valor
        repeat(4) {
            val proximo = utf8Estrito(bytesDecodificados(decodificado) ?: return null) ?: return null
            if (proximo == decodificado) return decodificado
            decodificado = proximo
        }
        return if (ESCAPE.containsMatchIn(decodificado)) null else decodificado
    }

    /**
     * Os nomes de `form_urlencoded::parse` (o `query_pairs` da crate `url`): pedaços entre `&`, os vazios pulados, o
     * nome antes do primeiro `=`, com `+` lido como espaço e `%XX` decodificado, trocando o UTF-8 inválido por U+FFFD.
     * O nome que não se decodifica é `null`, e conta como sensível.
     */
    private fun nomesDaQuery(parte: String): List<String?> = parte.split('&')
        .filter { it.isNotEmpty() }
        .map { par -> bytesDecodificados(par.substringBefore('='))?.let { String(it, Charsets.UTF_8) } }

    /**
     * Os bytes de `percent_decode_str`, pelo decodificador do JDK: o `URLDecoder` lê o texto como Latin-1, em que cada
     * byte é um caractere, e devolve cada `%XX` como o byte exato; o resultado volta a bytes pela mesma tabela. Duas
     * diferenças do canônico, as duas do lado fechado e fixadas em teste: um `%` sem dois dígitos hexadecimais lança
     * (o canônico o mantém), e vira `null`, que é "não se decodifica" = sensível; e `+` vira espaço em todas as
     * camadas (o canônico só na query), o que não muda veredito algum, porque nenhum candidato, sufixo, separador nem
     * palavra comum tem `+` ou espaço. É a regra do workspace: o decodificador da especificação é o da plataforma,
     * não um escrito à mão.
     */
    private fun bytesDecodificados(valor: String): ByteArray? = try {
        URLDecoder.decode(String(valor.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1), Charsets.ISO_8859_1)
            .toByteArray(Charsets.ISO_8859_1)
    } catch (erro: IllegalArgumentException) {
        null
    }

    /** `decode_utf8`: o texto, ou `null` quando os bytes não são UTF-8 válido. */
    private fun utf8Estrito(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (erro: CharacterCodingException) {
        null
    }

    private val ESCAPE = Regex("%[0-9A-Fa-f]{2}")

    /** Palavras comuns que terminam em `key` e não são credencial. */
    private val PALAVRAS_COMUNS_EM_KEY = setOf("monkey", "donkey", "turkey", "hockey", "jockey", "whiskey", "hotkey")

    private val CANDIDATOS = listOf(
        "access_token", "accesskey", "api_key", "apikey", "appkey", "authorization", "authkey", "clientkey",
        "credential", "key", "passkey", "password", "privatekey", "secret", "secretkey", "sessionkey", "signature",
        "sig", "token", "x-amz-credential", "x-amz-signature",
    )

    /** Os candidatos que também valem como sufixo colado (`jsonwebtoken`, `userpassword`). */
    private val SUFIXOS = setOf("token", "apikey", "password", "secret", "credential", "signature", "authorization")

    private val SEPARADORES = listOf('_', '-', '.')
}
