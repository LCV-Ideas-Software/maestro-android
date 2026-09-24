package dev.lcv.maestro.provedores

import com.fasterxml.jackson.core.JacksonException
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.BufferedSink

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
 * - espera, chamada em voo e leitura do corpo respeitam o cancelamento.
 *
 * **O prazo.** No canônico, o prazo de cada chamada é o menor entre 120 s e o
 * tempo que resta da sessão. Quem sabe o tempo que resta é a sessão, que o
 * passa em [chamar]. Aqui ele vale para a chamada inteira: uma espera que não
 * cabe no tempo restante encerra a chamada com a falha que a motivou, em vez
 * de começar outra tentativa paga depois do limite; e nenhuma tentativa
 * começa sem tempo restante, nem quando a espera que cabia terminou tarde.
 *
 * **Nenhuma tentativa escondida.** Cada tentativa é um POST pago, e o OkHttp
 * refaz pedidos sozinho em vários casos:
 * - o 408, com `retryOnConnectionFailure`, que aqui fica desligado;
 * - o 503 com `Retry-After: 0`, sem olhar aquela opção
 *   (`RetryAndFollowUpInterceptor`, linha 265 no 5.5.0);
 * - o 421 e os redirecionamentos.
 *
 * O corpo de cada tentativa é de uso único (`isOneShot`), e o OkHttp documenta
 * que não refaz pedido com corpo assim (linha 105). **Os redirecionamentos
 * ficam desligados**, porque os endereços são fixos e o OkHttp, num
 * redirecionamento para outra origem, tira o `Authorization`, mas não o
 * `x-api-key` nem o `x-goog-api-key`.
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

    internal val http: OkHttpClient = http.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * [tempoRestante] é o tempo que resta da sessão; `null` quando a sessão
     * não tem limite de tempo. O prazo de cada tentativa é o menor entre
     * [PRAZO_POR_CHAMADA] e o que resta dele.
     */
    public suspend fun chamar(
        provedor: Provedor,
        pedido: Pedido,
        tempoRestante: Duration? = null,
    ): Resultado {
        // Chave colada costuma trazer espaço ou quebra de linha nas pontas, e
        // isso se descarta. Caractere que não pode ir num cabeçalho HTTP faz o
        // OkHttp lançar exceção **com o valor do cabeçalho na mensagem** —
        // medido: `x-api-key value: <chave>` —, e por isso é recusado aqui,
        // antes de montar a requisição, sem citar o valor.
        val chave = when (val leitura = fonte.chaveDe(provedor)) {
            is LeituraDaChave.Presente -> leitura.valor.trim().takeUnless { it.isEmpty() } ?: return Resultado.SemChave
            LeituraDaChave.Ausente -> return Resultado.SemChave
            LeituraDaChave.ExigeAutenticacao -> return Resultado.ExigeAutenticacao
            LeituraDaChave.Irrecuperavel -> return Resultado.SegredoIrrecuperavel
            LeituraDaChave.Indisponivel -> return Resultado.ChaveIndisponivel
        }
        if (chave.any { it.code !in 0x21..0x7e }) return Resultado.ChaveInvalida
        val formato = Formato.de(provedor)
        val corpo = Json.LEITOR.writeValueAsBytes(formato.corpo(pedido, provedor.modelo))
        val cabecalhos = Headers.Builder().apply { formato.cabecalhos(chave).forEach { (k, v) -> add(k, v) } }.build()

        val inicio = TimeSource.Monotonic.markNow()
        fun restante(): Duration? = tempoRestante?.minus(inicio.elapsedNow())
        fun cabe(espera: Duration): Boolean = restante()?.let { espera < it } ?: true

        var tentativa = 1
        // A falha que motivou a espera. Se a espera terminar tarde — o
        // aparelho dormiu, o escalonador atrasou — e o tempo tiver acabado, é
        // ela que volta, e a tentativa seguinte não sai.
        var falhaAnterior: Resultado? = null
        while (true) {
            val resta = restante()
            if (resta != null && resta <= Duration.ZERO) {
                return falhaAnterior
                    ?: Resultado.FalhaDeRede("PROVIDER_NETWORK_ERROR: no time left in the session for the call")
            }
            val prazo = minOf(PRAZO_POR_CHAMADA, resta ?: PRAZO_POR_CHAMADA)
            // Positivo e abaixo de 1 ms, o OkHttp recusaria o valor.
            val chamada = http.newBuilder().callTimeout(prazo.coerceAtLeast(1.milliseconds).toJavaDuration()).build()
                .newCall(
                    Request.Builder().url(endereco(provedor)).headers(cabecalhos)
                        .post(CorpoDeUmaVez(corpo)).build(),
                )
            val resposta = try {
                chamada.executeAsync()
            } catch (erro: IOException) {
                // Cancelamento não passa por aqui: o `executeAsync` o entrega
                // como `CancellationException`, e a espera abaixo também é
                // cancelável. Nada que o usuário mandou parar é repetido.
                val falha = falhaDeRede(erro, chave)
                val espera = ESPERA_APOS_ERRO_DE_REDE_MS.milliseconds
                if (tentativa >= MAX_TENTATIVAS || !cabe(espera)) return falha
                falhaAnterior = falha
                esperar(espera.inWholeMilliseconds)
                tentativa++
                continue
            }
            val espera429 = if (resposta.code == 429 && tentativa < MAX_TENTATIVAS) {
                ((segundosDeRetryAfter(resposta.headers) ?: ESPERA_PADRAO_429_S)
                    .coerceAtMost(TETO_DE_ESPERA_429_S)).seconds
            } else {
                null
            }
            // Falha lendo o corpo, depois de o provedor ter respondido, não se
            // repete: ele provavelmente já cobrou, e repetir pagaria duas
            // vezes. É o que o canônico faz, que só envolve o envio na
            // política de nova tentativa.
            val corpoDaResposta = try {
                resposta.use { lerCorpo(chamada, it) }
            } catch (erro: IOException) {
                // O 429 não é cobrado, e o corpo dele só serve à mensagem.
                if (espera429 == null) return falhaDeRede(erro, chave)
                ""
            }
            if (!resposta.isSuccessful) {
                val falha = Resultado.FalhaHttp(
                    resposta.code,
                    Erros.mensagemHttp(resposta.code, corpoDaResposta ?: CORPO_ACIMA_DO_TETO, chave),
                )
                // Se a espera cabe só se decide agora, depois da leitura do
                // corpo, que também consome o tempo restante.
                if (espera429 == null || !cabe(espera429)) return falha
                falhaAnterior = falha
                esperar(espera429.inWholeMilliseconds)
                tentativa++
                continue
            }
            if (corpoDaResposta == null) {
                return Resultado.RespostaInvalida("${provedor.agente} returned a 2xx $CORPO_ACIMA_DO_TETO")
            }
            val json = try {
                Json.LEITOR.readTree(corpoDaResposta)
            } catch (_: JacksonException) {
                null
            }
            if (json == null || !json.isObject) {
                return Resultado.RespostaInvalida("${provedor.agente} returned a 2xx body that is not a JSON object")
            }
            // O motivo de uma resposta incompleta é texto do provedor e vai ao
            // jornal. É saneado aqui, com a chave da chamada, e não no
            // leitor: apagar a chave depois de cortar no teto deixaria um
            // pedaço dela quando ela cai na borda do corte.
            return when (val lida = formato.ler(json)) {
                is Resultado.Incompleta -> lida.copy(motivo = Erros.sanear(lida.motivo, 180, chave))
                else -> lida
            }
        }
    }

    /**
     * Lê o corpo sem ficar surdo ao cancelamento. Depois que os cabeçalhos
     * chegam, o `executeAsync` já terminou e não cancela mais nada; a leitura
     * do corpo é bloqueante, e sem isto a sessão só pararia quando o corpo
     * acabasse ou o prazo vencesse. O vigia cancela a chamada se a corrotina
     * for cancelada no meio.
     *
     * Devolve `null` quando o corpo passa de [TETO_DO_CORPO_BYTES], sem
     * carregá-lo inteiro: um corpo sem fim, de proxy ou de erro, esgotaria a
     * memória do aplicativo antes de qualquer validação.
     */
    private suspend fun lerCorpo(chamada: Call, resposta: Response): String? = coroutineScope {
        var lido = false
        val vigia = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!lido) chamada.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                // `request` lê até haver o número pedido de bytes no buffer, ou
                // até o corpo acabar; o `string` seguinte lê do mesmo buffer.
                if (resposta.body.source().request(TETO_DO_CORPO_BYTES + 1)) null else resposta.body.string()
            }.also { lido = true }
        } finally {
            vigia.cancel()
        }
    }

    private fun falhaDeRede(erro: IOException, chave: String) = Resultado.FalhaDeRede(
        Erros.sanear("PROVIDER_NETWORK_ERROR: ${erro.javaClass.simpleName}: ${erro.message}", 240, chave),
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

    /**
     * Corpo de uso único. O OkHttp não refaz, por conta própria, pedido cujo
     * corpo declare `isOneShot`; cada tentativa nossa monta um corpo novo.
     */
    private class CorpoDeUmaVez(private val bytes: ByteArray) : RequestBody() {
        override fun contentType(): MediaType = JSON
        override fun contentLength(): Long = bytes.size.toLong()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) {
            sink.write(bytes)
        }
    }

    public companion object {
        /** Teto do canônico para uma tentativa; a sessão passa menos se restar menos. */
        public val PRAZO_POR_CHAMADA: Duration = 120.seconds

        internal const val MAX_TENTATIVAS = 2
        internal const val ESPERA_APOS_ERRO_DE_REDE_MS = 1_500L
        internal const val ESPERA_PADRAO_429_S = 30L
        internal const val TETO_DE_ESPERA_429_S = 120L

        /**
         * Teto do corpo de uma resposta: 8 MiB. A maior resposta esperada —
         * 64 mil tokens de saída, uns 256 KB de texto, mais o JSON e as
         * fontes da Perplexity — fica muito abaixo dele.
         */
        internal const val TETO_DO_CORPO_BYTES = 8L * 1024 * 1024
        private const val CORPO_ACIMA_DO_TETO = "response body larger than $TETO_DO_CORPO_BYTES bytes"

        private val JSON = "application/json".toMediaType()
    }
}
