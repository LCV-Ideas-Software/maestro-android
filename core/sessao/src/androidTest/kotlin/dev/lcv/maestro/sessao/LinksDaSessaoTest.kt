package dev.lcv.maestro.sessao

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * As linhas de link de uma sessão (MAEANDR-18): as do texto que ela tem agora,
 * auditado pelo motor real, e não as de versões anteriores do texto. Sem rede:
 * o coletor falha com `timeout`, e o motor grava a linha assim mesmo.
 */
@RunWith(AndroidJUnit4::class)
class LinksDaSessaoTest {

    private val t = BancoDeTeste()
    private val links = LinksDaSessao(t.banco, t.relogio)

    @After
    fun fechar() = t.fechar()

    private fun auditar(sessaoId: String, texto: String) {
        IntegridadeDeLinks.auditar(
            texto,
            AnalisadorDeUrlOkHttp,
            { throw IntegridadeDeLinks.Falha("timeout") },
            links.registro(sessaoId),
            t.relogio,
        )
    }

    @Test
    fun asLinhasSaoAsDoTextoAtualDaSessao() {
        val id = t.sessoes.criar(t.entrada()).id
        val antigo = "Primeira versão cita [a](https://exemplo.org/antiga) e [b](https://exemplo.org/b)."
        val atual = "Versão atual cita só [c](https://exemplo.org/c)."
        auditar(id, antigo)
        // O texto da sessão mudou (um revisor reescreveu): a gravação direta faz o papel do checkpoint.
        t.adulterar("UPDATE sessoes SET textoAtual = '$atual' WHERE id = '$id'")
        auditar(id, atual)

        val linhas = links.linhas(id)
        assertEquals(listOf("https://exemplo.org/c"), linhas.map { it.urlNormalizada })
        assertTrue(linhas.all { it.classificacao.name == "TEMPO_ESGOTADO" })
        // As da versão anterior continuam no registro global, fora da sessão.
        assertEquals(3, t.banco.links().todos().size)
    }

    @Test
    fun numaSessaoConvergidaAsLinhasSaoAsDoTextoFinal() {
        val id = t.sessoes.criar(t.entrada()).id
        val final = "Texto final com [fonte](https://exemplo.org/final)."
        auditar(id, final)
        val execucao = (t.retomada.preparar(id) as Preparacao.Nova).execucao
        assertTrue(t.sessoes.concluir(id, execucao, final, Estados.CONVERGIDA, null))

        assertEquals(listOf("https://exemplo.org/final"), links.linhas(id).map { it.urlNormalizada })
    }

    @Test
    fun aOrdemEADaListagemDoMotor() {
        val id = t.sessoes.criar(t.entrada()).id
        val antigo = "Versão antiga cita [a](https://exemplo.org/a), [b](https://exemplo.org/b) e [c](https://exemplo.org/c)."
        val atual = "Versão atual cita [c](https://exemplo.org/c), [d](https://exemplo.org/d) e [e](https://exemplo.org/e)."
        auditar(id, antigo)
        t.adulterar("UPDATE sessoes SET textoAtual = '$atual' WHERE id = '$id'")
        auditar(id, atual)
        // O motor data cada linha com uma leitura própria do relógio. A de maior id passa a ter a
        // verificação mais recente, e as outras duas empatam na data: aí o id decide, crescente.
        val ultima = links.linhas(id).maxBy { it.linkId }
        val outras = links.linhas(id).filter { it.linkId != ultima.linkId }.joinToString(", ") { "'${it.linkId}'" }
        t.adulterar("UPDATE links SET linhaJson = json_set(linhaJson, '$.checked_at', '2098-01-01T00:00:00+00:00') WHERE linkId IN ($outras)")
        t.adulterar("UPDATE links SET linhaJson = json_set(linhaJson, '$.checked_at', '2099-01-01T00:00:00+00:00') WHERE linkId = '${ultima.linkId}'")

        val linhas = links.linhas(id)
        assertEquals(3, linhas.size)
        assertEquals(ultima.linkId, linhas.first().linkId)
        assertEquals(linhas.drop(1).map { it.linkId }.sorted(), linhas.drop(1).map { it.linkId })
        // A mesma ordem da listagem do motor, filtrada pela impressão do texto.
        val doMotor = IntegridadeDeLinks.listar(IntegridadeDeLinks.PedidoDeListagem(limite = 100), links.registro(id)).itens
            .filter { it.impressaoDaOrigem == linhas.first().impressaoDaOrigem }
        assertEquals(doMotor.map { it.linkId }, linhas.map { it.linkId })
    }

    @Test
    fun sessaoInexistenteNaoTemLinhas() {
        assertTrue(links.linhas("android-inexistente").isEmpty())
    }
}
