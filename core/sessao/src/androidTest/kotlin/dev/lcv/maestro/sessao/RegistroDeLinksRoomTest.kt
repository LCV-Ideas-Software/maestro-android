package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** O diretório de registros de link como tabelas do Room, atravessado pelo motor real com uma coleta de mentira. */
@RunWith(AndroidJUnit4::class)
class RegistroDeLinksRoomTest {

    private val t = BancoDeTeste()
    private val registro = RegistroDeLinksRoom(t.banco, "android-1", t.relogio)

    @After
    fun fechar() = t.fechar()

    private val coletor = IntegridadeDeLinks.ColetorDeEvidencia { url -> evidencia(url) }

    private fun auditar(texto: String) = IntegridadeDeLinks.auditar(texto, AnalisadorDeUrlOkHttp, coletor, registro, t.relogio)

    @Test
    fun aAuditoriaGravaUmaLinhaPorLinkEAnotaOEvento() {
        val resultado = auditar("Veja [a](https://example.com/a) e [b](https://example.org/b).")
        assertEquals(2, resultado.linhas.size)
        assertEquals(2, registro.todos().size)
        for (linha in resultado.linhas) {
            assertEquals(linha, registro.carregar(linha.linkId))
            assertEquals(listOf("audit"), registro.eventosDe(linha.linkId).map { it.tipo })
            assertEquals("android-1", t.banco.links().carregar(linha.linkId)!!.sessaoId)
        }
        // Uma segunda auditoria do mesmo texto reaproveita o registro e anota de novo.
        auditar("Veja [a](https://example.com/a) e [b](https://example.org/b).")
        assertEquals(2, registro.todos().size)
        assertEquals(2, registro.eventosDe(resultado.linhas[0].linkId).size)
    }

    @Test
    fun linhaGravadaInvalidaOuDeOutroEsquemaNaoEValida() {
        val linha = auditar("[a](https://example.com/a)").linhas.single()
        val gravada = t.banco.links().carregar(linha.linkId)!!
        t.banco.links().gravar(gravada.copy(linhaJson = "{\"link_id\":\"x\""))
        assertNull(registro.carregar(linha.linkId))
        assertEquals(0, registro.todos().size)
        t.banco.links().gravar(gravada.copy(linhaJson = gravada.linhaJson.replace("link_evidence.v1", "link_evidence.v2")))
        assertNull(registro.carregar(linha.linkId))
        t.banco.links().gravar(gravada)
        assertEquals(linha, registro.carregar(linha.linkId))
    }

    @Test
    fun osRegistrosSaoGlobaisEntreSessoes() {
        val linha = auditar("[a](https://example.com/a)").linhas.single()
        val outra = RegistroDeLinksRoom(t.banco, "android-2", t.relogio)
        assertEquals(linha, outra.carregar(linha.linkId))
        assertEquals(1, outra.todos().size)
    }

    private fun evidencia(url: String) = RegistroDeEvidencia(
        id = "ev-${url.hashCode()}", versaoDoEsquema = "web_evidence.v1", estado = EstadoDaEvidencia.PRONTA,
        url = url, metodo = MetodoHttp.GET, modoDeAcesso = ModoDeAcesso.COLETA_HTTP, status = 200,
        urlFinal = url, titulo = null, tipoDeConteudo = "text/html", sha256 = "1".repeat(64), coletadaEm = "2026-09-25T12:00:00+00:00",
        expiraEm = null, validadeDoCache = "P30D", estadoDoCache = EstadoDoCache.FRESCO,
        estadoDoRobots = EstadoDoRobots.PERMITIDO, estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
        estadoDeInteracao = EstadoDeInteracao.NENHUMA, resolvidaPorPessoa = false, bytes = 10, duracaoMs = 5,
        cadeiaDeRedirecionamento = emptyList(), comandoCurl = null, provedor = null, consulta = null,
        nomeDoArtefato = null, notas = emptyList(), criadaEm = "2026-09-25T12:00:00+00:00",
        atualizadaEm = "2026-09-25T12:00:00+00:00",
    )
}
