package dev.lcv.maestro.provedores

import com.fasterxml.jackson.core.JacksonException
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync

/**
 * Chama um dos seis provedores e devolve o [Resultado].
 *
 * **A política de nova tentativa é a do canônico**, `provider_retry.rs` no
 * `maestro-app`, que o web declara portar byte a byte:
 *
 * - no máximo duas tentativas;
 * - erro de rede, DNS, conexão ou prazo tenta de novo uma vez, depois de
 *   1,5 s;
 * - só o HTTP 429 tenta de novo, esperando o `Retry-After` em segundos ou em
 *   data HTTP, 30 s quando o cabeçalho falta, nunca mais de 120 s;
 * - qualquer outro status volta como está, sem nova tentativa;
 * - espera e chamada em voo respeitam o cancelamento da corrotina.
 *
 * **Nenhuma tentativa escondida.** O `retryOnConnectionFailure` do OkHttp,
 * ligado por padrão, faz tentativas fora das duas do canônico — entre elas,
 * repete sozinho o HTTP 408 —, e cada uma é um POST pago. Ele é desligado aqui.
 *
 * O prazo de cada chamada, no canônico, é o menor entre 120 s e o tempo que
 * resta da sessão; quem sabe o tempo que resta é a sessão, que o passa em
 * [chamar].
 */
public class ClienteDeProvedores internal constructor(
    http: OkHttpClient,
    private val fonte: FonteDeChave,
    private val endereco: (Provedor) -> String,
    private val esperar: suspend (Long) -> Unit,
    private val agora: () -> Instant,
) {
    public constructor(http: OkHttpClient, fonte: FonteDeChave) :
        this(http, fonte, { it.endereco }, { delay(it) }, Instant::now)

    private val http: OkHttpClient = http.newBuilder().retryOnConnectionFailure(false).build()

    public suspend fun chamar(
        provedor: Provedor,
        pedido: Pedido,
        prazo: Duration = PRAZO_POR_CHAMADA,
    ): Resultado {
        // Chave colada costuma trazer espaço ou quebra de linha nas pontas, e
        // isso se descarta. Caractere que não pode ir num cabeçalho HTTP faz o
        // OkHttp lançar exceção **com o valor do cabeçalho na mensagem** —
        // medido: `x-api-key value: <chave>` —, e por isso é recusado aqui,
        // antes de montar a requisição, sem citar o valor.
        val chave = fonte.chaveDe(provedor)?.trim()?.takeUnless { it.isEmpty() }
            ?: return Resultado.SemChave
        if (chave.any { it.code !in 0x21..0x7e }) return Resultado.ChaveInvalida
        val formato = Formato.de(provedor)
        val corpo = Json.LEITOR.writeValueAsString(formato.corpo(pedido, provedor.modelo))
            .toRequestBody(JSON)
        val requisicao = Request.Builder()
            .url(endereco(provedor))
            .headers(Headers.Builder().apply { formato.cabecalhos(chave).forEach { (k, v) -> add(k, v) } }.build())
            .post(corpo)
            .build()
        val cliente = http.newBuilder().callTimeout(prazo.toJavaDuration()).build()

        var tentativa = 1
        while (true) {
            val resposta = try {
                cliente.newCall(requisicao).executeAsync()
            } catch (erro: IOException) {
                // Cancelamento não passa por aqui: o `executeAsync` o entrega
                // como `CancellationException`, e a espera abaixo também é
                // cancelável. Nada que o usuário mandou parar é repetido.
                if (tentativa >= MAX_TENTATIVAS) return falhaDeRede(erro)
                esperar(ESPERA_APOS_ERRO_DE_REDE_MS)
                tentativa++
                continue
            }
            // Falha lendo o corpo, depois de o provedor ter respondido, não se
            // repete: ele provavelmente já cobrou, e repetir pagaria duas
            // vezes. É o que o canônico faz, que só envolve o envio na
            // política de nova tentativa.
            val corpoDaResposta = try {
                resposta.use { r ->
                    if (r.code == 429 && tentativa < MAX_TENTATIVAS) {
                        null
                    } else {
                        withContext(Dispatchers.IO) { r.body.string() }
                    }
                }
            } catch (erro: IOException) {
                return falhaDeRede(erro)
            }
            if (corpoDaResposta == null) {
                val segundos = (segundosDeRetryAfter(resposta.headers) ?: ESPERA_PADRAO_429_S)
                    .coerceAtMost(TETO_DE_ESPERA_429_S)
                esperar(segundos * 1_000)
                tentativa++
                continue
            }
            if (!resposta.isSuccessful) {
                return Resultado.FalhaHttp(resposta.code, Erros.mensagemHttp(resposta.code, corpoDaResposta))
            }
            val json = try {
                Json.LEITOR.readTree(corpoDaResposta)
            } catch (_: JacksonException) {
                null
            }
            if (json == null || !json.isObject) {
                return Resultado.RespostaInvalida("${provedor.agente} returned a 2xx body that is not a JSON object")
            }
            return formato.ler(json)
        }
    }

    private fun falhaDeRede(erro: IOException) = Resultado.FalhaDeRede(
        Erros.sanear("PROVIDER_NETWORK_ERROR: ${erro.javaClass.simpleName}: ${erro.message}", 240),
    )

    /**
     * `parse_retry_after_header`: segundos inteiros, ou data HTTP convertida
     * em segundos a partir de agora (nunca negativo); `null` quando falta ou
     * não se entende.
     */
    internal fun segundosDeRetryAfter(cabecalhos: Headers): Long? {
        val valor = cabecalhos["Retry-After"]?.trim() ?: return null
        if (valor.isNotEmpty() && valor.all { it in '0'..'9' }) return valor.toLongOrNull()
        val data = cabecalhos.getInstant("Retry-After") ?: return null
        return maxOf(0L, data.epochSecond - agora().epochSecond)
    }

    public companion object {
        /** Teto do canônico para uma chamada; a sessão passa menos se restar menos. */
        public val PRAZO_POR_CHAMADA: Duration = 120.seconds

        internal const val MAX_TENTATIVAS = 2
        internal const val ESPERA_APOS_ERRO_DE_REDE_MS = 1_500L
        internal const val ESPERA_PADRAO_429_S = 30L
        internal const val TETO_DE_ESPERA_429_S = 120L

        private val JSON = "application/json".toMediaType()
    }
}
