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
    fun adicionarERemoverDentroDeUmaTransacaoSaoRecusados() {
        val id = t.sessoes.criar(t.entrada()).id
        val anexo = (anexos.adicionar(id, "a.json", "application/json", bytes(10)) as Resultado.Ok).valor
        val recusas = listOf<() -> Unit>(
            { anexos.adicionar(id, "b.json", "application/json", bytes(11)) },
            { anexos.remover(anexo.id) },
        ).count { chamada ->
            try {
                t.banco.runInTransaction { chamada() }
                false
            } catch (esperado: IllegalStateException) {
                true
            }
        }
        assertEquals(2, recusas)
        assertEquals(1, anexos.daSessao(id).size)
        assertTrue(File(anexo.caminho).exists())
        assertEquals(1, pasta.listFiles()!!.size)
    }

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

    @Test
    fun aSessaoDoFormularioNasceComOManifestoOuNaoNasce() {
        val sessao = (anexos.criarSessao(t.sessoes, t.entrada(), "citation-manifest.json", "application/json", bytes(10)) as Resultado.Ok).valor
        assertEquals(listOf(sessao.id), t.sessoes.listar().map { it.id })
        assertEquals(listOf("citation-manifest.json"), anexos.daSessao(sessao.id).map { it.nomeOriginal })
        // A linha do anexo falha depois da linha da sessão, dentro da mesma operação: nada fica.
        t.adulterar("CREATE TRIGGER anexo_falha BEFORE INSERT ON anexos BEGIN SELECT RAISE(ABORT, 'disco cheio'); END")
        val falhou = try {
            anexos.criarSessao(t.sessoes, t.entrada(), "outro.json", "application/json", bytes(20))
            false
        } catch (esperado: RuntimeException) {
            true
        }
        assertTrue(falhou)
        assertEquals(listOf(sessao.id), t.sessoes.listar().map { it.id })
        assertEquals(1, pasta.listFiles()!!.size)
    }

    @Test
    fun oDiscoQueFalhaERecusaSemSessaoNemAnexo() {
        val id = t.sessoes.criar(t.entrada()).id
        // Um arquivo onde a pasta dos anexos deveria estar: a pasta não pode ser criada.
        pasta.writeBytes(bytes(1))
        val recusa = anexos.adicionar(id, "a.json", "application/json", bytes(10)) as Resultado.Recusado
        assertEquals("failed to write attachment: cannot create directory ${pasta.absolutePath}", recusa.mensagem)
        val daSessao = anexos.criarSessao(t.sessoes, t.entrada(), "citation-manifest.json", "application/json", bytes(10)) as Resultado.Recusado
        assertEquals(recusa.mensagem, daSessao.mensagem)
        assertEquals(listOf(id), t.sessoes.listar().map { it.id })
        assertEquals(0, anexos.daSessao(id).size)
    }
}
