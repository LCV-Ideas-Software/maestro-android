package dev.lcv.maestro.sessao

import dev.lcv.maestro.provedores.ClienteDeProvedores
import dev.lcv.maestro.provedores.FonteDeChave
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Pedido
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.provedores.Resultado
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec

/**
 * O cliente HTTP de produção dos provedores ([Fabrica.clienteHttpDosProvedores])
 * pela mecânica real do [ClienteDeProvedores], contra um servidor local. Com o
 * raciocínio no máximo e sem *streaming*, a resposta pode levar mais que os
 * 10 s de leitura padrão do OkHttp para começar a chegar; a chamada tem de
 * esperá-la até o prazo dela, e não cair por prazo de leitura e repeti-la num
 * segundo POST pago.
 *
 * O teste espera de verdade, uns 11 s: o que ele prova é justamente a espera
 * além do padrão do OkHttp.
 */
class ClienteHttpDosProvedoresTest {

    private val servidor = MockWebServer()
    private val fonte = FonteDeChave { LeituraDaChave.Presente("chave-de-teste") }

    private val concluida =
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Ok."}]}],"usage":{"input_tokens":1,"output_tokens":1}}"""

    @BeforeTest fun subir() = servidor.start()

    @AfterTest fun descer() = servidor.close()

    @Test
    fun `resposta que chega depois de 10 s e antes de 120 s conclui com um POST so`() {
        val producao = Fabrica.clienteHttpDosProvedores()
        // Em produção só sai TLS moderno, e o OkHttp recusa `http://` sem
        // `CLEARTEXT`. O servidor local fala HTTP: só a lista de
        // especificações de conexão muda aqui, e os prazos são os de produção.
        assertEquals(listOf(ConnectionSpec.MODERN_TLS), producao.connectionSpecs)
        // A conexão, o envio e a leitura têm o prazo da chamada. A espera de
        // 11 s, abaixo, prova que a leitura passa do padrão do OkHttp; estas
        // igualdades prendem os valores.
        val prazo = ClienteDeProvedores.PRAZO_POR_CHAMADA.inWholeMilliseconds
        assertEquals(prazo, producao.connectTimeoutMillis.toLong())
        assertEquals(prazo, producao.writeTimeoutMillis.toLong())
        assertEquals(prazo, producao.readTimeoutMillis.toLong())
        val http = producao.newBuilder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).build()
        // Os cabeçalhos saem 11 s depois do pedido: um pouco além dos 10 s de
        // leitura padrão do OkHttp, e bem dentro dos 120 s da chamada. A
        // segunda resposta só seria pedida por uma nova tentativa.
        repeat(2) { servidor.enqueue(MockResponse.Builder().headersDelay(11, TimeUnit.SECONDS).body(concluida).build()) }

        val resultado = runBlocking {
            ClienteDeProvedores(http, fonte) { servidor.url("/${it.agente}").toString() }
                .chamar(Provedor.CODEX, Pedido(sistema = "Papel.", prompt = "Turno."))
        }

        assertIs<Resultado.Concluida>(resultado, "$resultado, com ${servidor.requestCount} POST(s)")
        assertEquals(1, servidor.requestCount)
    }
}
