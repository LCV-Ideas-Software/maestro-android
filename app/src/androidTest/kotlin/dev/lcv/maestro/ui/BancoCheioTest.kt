package dev.lcv.maestro.ui

import android.database.sqlite.SQLiteFullException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.PedidoDeConfiguracoes
import dev.lcv.maestro.sessao.Resultado
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * O dublê do banco cheio antes das telas que dependem dele (decisão 25 do operador): a escrita chega a
 * quem chamou o repositório como a própria `SQLiteFullException` do framework, sem embrulho do Room, e
 * a leitura, o rastreador de invalidação e o banco depois de esvaziado seguem normais.
 */
@RunWith(AndroidJUnit4::class)
class BancoCheioTest {

    private val c = Cenario()

    @get:Rule
    val fechamento: TestRule = c.fechamento

    @Test
    fun aEscritaFalhaComoOSQLiteSemEspacoEORestoSegue() {
        c.configurar(teto = "5")
        val id = c.sessao(Estados.ERRO, erro = "Falha qualquer.")
        c.bancoCheio.cheio = true

        val direta = assertThrows(SQLiteFullException::class.java) { c.configuracoes.salvar(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal("7"))) }
        assertEquals(SQLiteFullException::class.java, direta.javaClass)
        assertEquals(BancoCheio.MENSAGEM, direta.message)
        // Numa transação do repositório, e com o arquivo do anexo já publicado: a exceção sobe igual, e o arquivo sai.
        val naTransacao = assertThrows(SQLiteFullException::class.java) { c.anexos.adicionar(id, "a.json", "application/json", ByteArray(10)) }
        assertEquals(SQLiteFullException::class.java, naTransacao.javaClass)
        assertEquals(0, c.pastaDosAnexos.listFiles()?.size ?: 0)

        // A leitura e o rastreador de invalidação do Room seguem.
        assertEquals(BigDecimal("5"), c.configuracoes.carregar().tetoDeCustoUsd.stripTrailingZeros())
        assertEquals(listOf(id), c.sessoes.listar().map { it.id })
        runBlocking { withTimeout(5_000) { c.links.mudancas().first() } }

        c.bancoCheio.cheio = false
        assertTrue(c.configuracoes.salvar(PedidoDeConfiguracoes(tetoDeCustoUsd = BigDecimal("7"))) is Resultado.Ok)
        assertTrue(c.anexos.adicionar(id, "a.json", "application/json", ByteArray(10)) is Resultado.Ok)
    }
}
