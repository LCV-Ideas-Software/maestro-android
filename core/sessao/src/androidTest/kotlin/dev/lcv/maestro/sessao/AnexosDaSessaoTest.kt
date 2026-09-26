package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Anexos da sessão: arquivo sob `noBackupFilesDir`, linha no Room, teto de 16 MiB. */
@RunWith(AndroidJUnit4::class)
class AnexosDaSessaoTest {

    private val t = BancoDeTeste()
    private val pasta = File(t.contexto.noBackupFilesDir, "anexos-teste-${System.nanoTime()}")
    private val anexos = AnexosDaSessao(t.banco, pasta, t.relogio)

    @After
    fun fechar() {
        pasta.deleteRecursively()
        t.fechar()
    }

    private fun bytes(tamanho: Int): ByteArray = ByteArray(tamanho) { (it % 251).toByte() }

    @Test
    fun dezesseisMebibytesEntramEUmByteAMaisERecusado() {
        val id = t.sessoes.criar(t.entrada()).id
        val recusa = anexos.adicionar(id, "grande.json", "application/json", bytes(AnexosDaSessao.MAX_BYTES + 1)) as Resultado.Recusado
        assertEquals("Anexo excede o limite de 16 MiB.", recusa.mensagem)
        assertEquals(0, anexos.daSessao(id).size)
        assertEquals(0, pasta.listFiles()?.size ?: 0)
        val conteudo = bytes(AnexosDaSessao.MAX_BYTES)
        val anexo = (anexos.adicionar(id, "manifesto.json", "application/json", conteudo) as Resultado.Ok).valor
        assertEquals(AnexosDaSessao.MAX_BYTES.toLong(), anexo.bytes)
        assertEquals(FormatoDoRegistro.sha256(conteudo), anexo.sha256)
        assertTrue(anexo.caminho.startsWith(t.contexto.noBackupFilesDir.absolutePath))
        assertArrayEquals(conteudo, File(anexo.caminho).readBytes())
        val listado = anexos.listar(id).single()
        assertEquals("manifesto.json", listado.nomeOriginal)
        assertEquals("application/json", listado.tipoDeMidia)
    }

    @Test
    fun removerApagaALinhaEOArquivoELimparOrfaosPoupaOReferenciado() {
        val id = t.sessoes.criar(t.entrada()).id
        val a = (anexos.adicionar(id, "a.txt", "text/plain", bytes(10)) as Resultado.Ok).valor
        val b = (anexos.adicionar(id, "b.txt", "text/plain", bytes(20)) as Resultado.Ok).valor
        val orfao = File(pasta, "anexo-x-abc").also { it.writeBytes(bytes(3)) }
        anexos.limparOrfaos()
        assertFalse(orfao.exists())
        assertTrue(File(a.caminho).exists())
        assertTrue(anexos.remover(a.id))
        assertFalse(File(a.caminho).exists())
        assertFalse(anexos.remover(a.id))
        assertEquals(listOf(b.id), anexos.daSessao(id).map { it.id })
    }
}
