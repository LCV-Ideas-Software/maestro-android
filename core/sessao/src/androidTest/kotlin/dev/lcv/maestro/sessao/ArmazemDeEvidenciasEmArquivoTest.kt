package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import dev.lcv.maestro.provedores.ColetorHttp
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Corpos de evidência como gerações imutáveis em arquivo, metadados no Room. */
@RunWith(AndroidJUnit4::class)
class ArmazemDeEvidenciasEmArquivoTest {

    private val t = BancoDeTeste()
    private val pasta = File(t.contexto.noBackupFilesDir, "evidencias-teste-${System.nanoTime()}")
    private val armazem = ArmazemDeEvidenciasEmArquivo(t.banco, pasta, t.relogio)

    @After
    fun fechar() {
        pasta.deleteRecursively()
        t.fechar()
    }

    private fun corpo(tamanho: Int, semente: Int): ByteArray = ByteArray(tamanho) { ((it * 31 + semente) and 0xFF).toByte() }

    @Test
    fun umCorpoDeOitoMebibytesVaiEVoltaPorArquivoSobNoBackup() {
        val corpo = corpo(8 * 1024 * 1024, 1)
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1"), mapOf("content-type" to "text/html"), corpo))
        val lida = armazem.existente("ev-1")!!
        assertArrayEquals(corpo, lida.corpo)
        assertEquals(mapOf("content-type" to "text/html"), lida.cabecalhos)
        assertEquals("ev-1", lida.registro.id)
        val linha = t.banco.evidencias().carregar("ev-1")!!
        assertTrue(linha.caminhoDoCorpo!!.startsWith(t.contexto.noBackupFilesDir.absolutePath))
        assertEquals(8 * 1024 * 1024L, File(linha.caminhoDoCorpo!!).length())
        assertNull(armazem.existente("ev-2"))
    }

    @Test
    fun recoletaSemCorpoMantemAGeracaoAtualEComCorpoTrocaEApagaAAnterior() {
        val primeiro = corpo(3 * 1024 * 1024, 2)
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1"), emptyMap(), primeiro))
        val caminhoPrimeiro = t.banco.evidencias().carregar("ev-1")!!.caminhoDoCorpo!!
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1", EstadoDaEvidencia.BLOQUEADA), mapOf("x" to "y"), null))
        val depoisDaFalha = armazem.existente("ev-1")!!
        assertArrayEquals(primeiro, depoisDaFalha.corpo)
        assertEquals(EstadoDaEvidencia.BLOQUEADA, depoisDaFalha.registro.estado)
        assertEquals(caminhoPrimeiro, t.banco.evidencias().carregar("ev-1")!!.caminhoDoCorpo)
        val segundo = corpo(1024, 3)
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1"), emptyMap(), segundo))
        val caminhoSegundo = t.banco.evidencias().carregar("ev-1")!!.caminhoDoCorpo!!
        assertTrue(caminhoSegundo != caminhoPrimeiro)
        assertFalse(File(caminhoPrimeiro).exists())
        assertArrayEquals(segundo, armazem.existente("ev-1")!!.corpo)
    }

    @Test
    fun limparOrfaosApagaSoOQueNenhumaLinhaReferencia() {
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1"), emptyMap(), corpo(10, 4)))
        val referenciado = File(t.banco.evidencias().carregar("ev-1")!!.caminhoDoCorpo!!)
        val orfao = File(pasta, "ev-9-abc").also { it.writeBytes(corpo(5, 5)) }
        val temporario = File(pasta, "ev-9-abc.tmp").also { it.writeBytes(corpo(5, 6)) }
        armazem.limparOrfaos()
        assertTrue(referenciado.exists())
        assertFalse(orfao.exists())
        assertFalse(temporario.exists())
    }

    @Test
    fun registroGravadoInvalidoNaoVoltaComoExistente() {
        armazem.guardar(ColetorHttp.Coleta(registro("ev-1"), emptyMap(), corpo(10, 7)))
        val linha = t.banco.evidencias().carregar("ev-1")!!
        t.banco.evidencias().gravar(linha.copy(registroJson = "{\"id\":"))
        assertNull(armazem.existente("ev-1"))
    }

    private fun registro(id: String, estado: EstadoDaEvidencia = EstadoDaEvidencia.PRONTA) = RegistroDeEvidencia(
        id = id, versaoDoEsquema = "web_evidence.v1", estado = estado,
        url = "https://example.com/$id", metodo = MetodoHttp.GET, modoDeAcesso = ModoDeAcesso.COLETA_HTTP, status = 200,
        urlFinal = "https://example.com/$id", titulo = null, tipoDeConteudo = "text/html", sha256 = "1".repeat(64),
        coletadaEm = "2026-09-25T12:00:00+00:00", expiraEm = null, validadeDoCache = "P30D", estadoDoCache = EstadoDoCache.FRESCO,
        estadoDoRobots = EstadoDoRobots.PERMITIDO, estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
        estadoDeInteracao = EstadoDeInteracao.NENHUMA, resolvidaPorPessoa = false, bytes = 10, duracaoMs = 5,
        cadeiaDeRedirecionamento = emptyList(), comandoCurl = null, provedor = null, consulta = null,
        nomeDoArtefato = null, notas = emptyList(), criadaEm = "2026-09-25T12:00:00+00:00",
        atualizadaEm = "2026-09-25T12:00:00+00:00",
    )
}
