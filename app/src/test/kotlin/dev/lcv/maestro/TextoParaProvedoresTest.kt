package dev.lcv.maestro

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * A legenda da tela de chaves (`texto_para_provedores`; especificação, seções
 * 6.3 e 6.4) contra a seção 6.1 da política de privacidade 1.5: os anexos não
 * vão aos provedores como tal, e o pedido de não guardar a conversa não desliga
 * a guarda da Perplexity. Lê o `strings.xml` do repositório com o leitor de XML
 * da plataforma Java, como os testes do manifesto e da regra de backup; a
 * redação inteira, na tela, é conferida pelo teste instrumentado da tela de
 * configurações, que só roda no portão local.
 */
class TextoParaProvedoresTest {

    private val raiz = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val legenda: String = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(raiz, "app/src/main/res/values/strings.xml"))
        .getElementsByTagName("string")
        .let { lista -> (0 until lista.length).map { lista.item(it) as Element } }
        .single { it.getAttribute("name") == "texto_para_provedores" }
        .textContent

    /** As orações da legenda, separadas pelo ponto e pelo ponto e vírgula. */
    private val oracoes = legenda.split('.', ';')

    @Test
    fun `a legenda nao diz que os anexos vao aos provedores`() {
        // O leitor dos anexos ignora o que não é manifesto de citações (`ManifestosDosAnexos`), e, do manifesto, os
        // prompts comuns levam só a origem e as contagens (`PromptsDaSessao.resumoDoManifesto`).
        assertFalse("anexos da sessão são enviados" in legenda, legenda)
        assertTrue("um resumo do manifesto de citações" in legenda, legenda)
        assertTrue("O arquivo anexado não é enviado como tal" in legenda, legenda)
        assertTrue("os anexos que não são manifesto de citações não saem do aparelho" in legenda, legenda)
    }

    @Test
    fun `a legenda nao conta a Perplexity entre os provedores que atendem o pedido de nao guardar`() {
        // Na Agent API, o `store: false` só esconde a resposta da consulta posterior e não desliga a guarda (página
        // Conversation State da Perplexity: https://docs.perplexity.ai/docs/agent-api/conversation-state).
        val pedido = oracoes.single { "não guardem a conversa" in it }
        assertFalse("Perplexity" in pedido, pedido)
        val daPerplexity = oracoes.filter { "Perplexity" in it }
        assertTrue(daPerplexity.isNotEmpty(), legenda)
        daPerplexity.forEach { assertTrue("não desliga a guarda" in it, it) }
    }
}
