package dev.lcv.maestro.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.StatusDaRevisao
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Estados
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Os links auditados e a revisão de cada um (especificação, seção 2.2;
 * MAEANDR-18; plano do `:app`, emendas A7 e A8): as linhas são as do texto
 * atual, pelo motor real; o navegador só recebe a URL que a regra de rede
 * aceitou, e nenhum teste abre navegador (o dublê anota a URL); a captura
 * importada fica sob o link sem mudá-lo; a revisão mostra a recusa do motor;
 * as propostas de correção sobrevivem à auditoria seguinte numa linha decidida.
 */
@RunWith(AndroidJUnit4::class)
class LinksScreenTest {

    val regra = createAndroidComposeRule<ComponentActivity>()

    private val c = Cenario()

    @get:Rule
    val ordem: RuleChain = RuleChain.outerRule(c.fechamento).around(regra)

    private fun sessaoComLinks(): String {
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoAtual = TEXTO)
        c.auditarLinks(id, TEXTO)
        return id
    }

    private fun linha(id: String, url: String): LinhaDeLink = c.links.linhas(id).single { it.urlNormalizada == url }

    private fun esperarTag(marca: String) {
        regra.waitUntil(5_000) { regra.onAllNodesWithTag(marca).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Abre a sessão, vai aos links e abre o link de [url]. */
    private fun abrirOLink(id: String, url: String, seletor: SeletorDeTeste? = null): LinhaDeLink {
        val alvo = linha(id, url)
        regra.abrir(c, sessaoPedida = id, seletor = seletor)
        esperarTag(Marcas.ABRIR_LINKS)
        regra.onNodeWithTag(Marcas.ABRIR_LINKS).performScrollTo().performClick()
        esperarTag(Marcas.link(alvo.linkId))
        regra.onNodeWithTag(Marcas.link(alvo.linkId)).performScrollTo().performClick()
        // O detalhe aberto é o deste link quando mostra a URL original dele.
        regra.waitUntil(5_000) { regra.onAllNodes(noDetalhe(alvo.urlOriginal)).fetchSemanticsNodes().isNotEmpty() }
        return alvo
    }

    /**
     * Espera o aviso aparecer e sair: o `Snackbar` cobre o pé da tela, e um
     * toque no botão que está embaixo dele cairia no aviso.
     */
    private fun esperarOAvisoPassar(texto: String, substring: Boolean = false) {
        regra.esperarTexto(texto, substring)
        regra.waitUntil(15_000) { regra.onAllNodes(hasText(texto, substring = substring)).fetchSemanticsNodes().isEmpty() }
    }

    private fun noDetalhe(texto: String) = hasText(texto, substring = true) and hasAnyAncestor(hasTestTag(Marcas.DETALHE_DO_LINK))

    private fun detalhe(texto: String) = regra.onNode(noDetalhe(texto))

    @Test
    fun asLinhasSaoAsDoTextoAtualComOsRotulosDoDesktop() {
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoAtual = TEXTO)
        // Uma versão anterior do texto também foi auditada: as linhas dela não são desta sessão.
        c.auditarLinks(id, "Versão antiga cita [outra](https://exemplo.org/antiga).")
        c.auditarLinks(id, TEXTO)
        abrirOLink(id, RELATORIO)

        assertEquals(setOf(RELATORIO, INTERNO), c.links.linhas(id).map { it.urlNormalizada }.toSet())
        regra.onAllNodes(hasText("outra")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        regra.onNode(hasText("Base interna")).assertExists()
        detalhe("Tempo esgotado").assertExists()
        detalhe("Ainda não julgado editorialmente").assertExists()
        detalhe("Pendente · decisão não registrada").assertExists()
        detalhe("Nenhuma evidência guardada para este endereço.").assertExists()
    }

    @Test
    fun semAuditoriaATelaDizQueNenhumLinkFoiAuditado() {
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoAtual = TEXTO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.ABRIR_LINKS)
        regra.onNodeWithTag(Marcas.ABRIR_LINKS).performScrollTo().performClick()
        esperarTag(Marcas.SEM_LINKS_AUDITADOS)
    }

    @Test
    fun abrirNoNavegadorRegistraAPassagemEDisparaSoAUrlValidada() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto("Handoff registrado. Exporte o artefato no navegador e importe-o abaixo.")
        assertEquals(listOf(RELATORIO), c.navegador.abertas)
        // O registro de passagem fica sob o próprio link: o endereço validado é o normalizado da linha.
        val passagem = ImportacaoDoOperador.idDaPassagem(RELATORIO)
        regra.onNodeWithTag(Marcas.evidencia(passagem)).performScrollTo().assertExists()
        detalhe("Captura do operador · Ação humana").assertExists()
        val registro = c.evidencias.existente(passagem)!!.registro
        assertEquals(EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR, registro.estado)
        assertEquals("Default-browser handoff launched", registro.notas.last())
        // A linha de link não muda: só a revisão explícita a libera.
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
    }

    @Test
    fun aUrlQueARegraDeRedeRecusaNaoChegaAoNavegador() {
        val id = sessaoComLinks()
        abrirOLink(id, INTERNO)

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto(
            "Não foi possível registrar o handoff para o navegador padrão. Motivo: " +
                "dominio resolve para IP privado/reservado bloqueado por seguranca",
        )
        assertTrue(c.navegador.abertas.isEmpty())
        assertTrue(c.evidencias.registrosDe(setOf(INTERNO)).isEmpty())
    }

    @Test
    fun semNavegadorOAvisoDizOQueORegistroAnotou() {
        val id = sessaoComLinks()
        c.navegador.falha = "failed to open system default browser: No Activity found to handle Intent"
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto("Handoff registrado, mas o navegador não abriu: failed to open system default browser: No Activity found to handle Intent")
        val registro = c.evidencias.existente(ImportacaoDoOperador.idDaPassagem(RELATORIO))!!.registro
        assertEquals("failed to open system default browser: No Activity found to handle Intent", registro.notas.last())
    }

    @Test
    fun oArquivoImportadoViraEvidenciaDoLinkComANotaDeProveniencia() {
        val id = sessaoComLinks()
        val html = "<html><title>Relatório</title></html>".toByteArray()
        val seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", html)))
        val antes = abrirOLink(id, RELATORIO, seletor)

        regra.onNodeWithTag(Marcas.NOTA_DA_CAPTURA).performScrollTo().performTextInput("Página salva do site oficial, acesso público.")
        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto("Artefato importado, hasheado e registrado com proveniência do operador.")

        val registro = c.evidencias.registrosDe(setOf(RELATORIO)).single()
        assertEquals("pagina-salva.html", registro.nomeDoArtefato)
        // Endereço `file:` sem tipo: a extensão decide, como no desktop.
        assertEquals("text/html", registro.tipoDeConteudo)
        assertEquals(EstadoDaEvidencia.PRONTA, registro.estado)
        assertEquals(RELATORIO, registro.url)
        assertEquals(
            listOf("Página salva do site oficial, acesso público.", "Artifact explicitly supplied by the operator; no browser profile was read"),
            registro.notas,
        )
        regra.onNodeWithTag(Marcas.evidencia(registro.id)).performScrollTo().assertExists()
        detalhe("Captura do operador · Pronta").assertExists()
        assertEquals(
            listOf("text/html", "text/markdown", "text/plain", "application/pdf", "image/png", "image/jpeg", "image/webp", "application/octet-stream"),
            (seletor.pedidos.single() as Array<*>).toList(),
        )
        assertEquals(antes, linha(id, RELATORIO))
    }

    @Test
    fun umArquivoDeTipoForaDaListaNaoViraEvidencia() {
        val id = sessaoComLinks()
        val seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("dados.json", "{\"a\": 1}".toByteArray())))
        abrirOLink(id, RELATORIO, seletor)

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto("Tipo recusado. Use HTML, Markdown, PDF, PNG, JPEG, WebP ou texto puro.")
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO)).isEmpty())
    }

    @Test
    fun aRevisaoExigeDecisaoENotaEMostraARecusaDoMotor() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsNotEnabled()

        regra.onNodeWithTag(Marcas.decisao("rejeitar")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("curta")
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsEnabled().performClick()
        esperarOAvisoPassar("A decisão não foi registrada. Motivo: review note must contain at least 10 characters")

        // Aceitar um link cuja verificação mecânica não passou é recusado pelo motor.
        val nota = "A fonte oficial não respondeu; o trecho fica sem suporte."
        regra.onNodeWithTag(Marcas.decisao("aceitar")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextReplacement(nota)
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        esperarOAvisoPassar("A decisão não foi registrada. Motivo: cannot accept a link that did not pass mechanical validation")
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)

        regra.onNodeWithTag(Marcas.decisao("rejeitar")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("Decisão registrada sobre a versão verificada. Nenhuma substituição foi aplicada ao texto.")
        val revisada = linha(id, RELATORIO)
        assertEquals(StatusDaRevisao.REJEITADA, revisada.statusDaRevisao)
        assertEquals(ClassificacaoDoLink.SUSPEITA_DE_ALUCINACAO, revisada.classificacao)
        assertEquals("operator", revisada.revisadoPor)
        assertEquals(nota, revisada.notaDaRevisao)
        detalhe("Rejeitada · decisão Rejeitar").assertExists()
        detalhe(nota).assertExists()
        // Gravada a decisão, a tela limpa a escolha, como o desktop.
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun asPropostasUsamOProvedorEscolhidoESobrevivemAAuditoriaNumaLinhaDecidida() {
        val id = sessaoComLinks()
        c.resultadosDaBusca = listOf(c.resultadoDeBusca("https://exemplo.org/substituto", "Relatório substituto"))
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.provedorDeBusca("openalex")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.CONSULTA_DE_CORRECAO).performScrollTo().performTextInput("relatório oficial 2026")
        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        esperarOAvisoPassar("3 propostas registradas. Nenhuma foi aplicada automaticamente.", substring = true)
        assertEquals(listOf("relatório oficial 2026" to "openalex"), c.buscas)
        detalhe("Substituir · Relatório substituto").assertExists()
        detalhe("Remover · Sem URL proposta").assertExists()
        detalhe("Reformular texto · Sem URL proposta").assertExists()

        // O caminho da emenda A8: decidir o link, e a próxima auditoria do portão mantém as propostas.
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte substituta ser conferida.")
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("Decisão registrada sobre a versão verificada. Nenhuma substituição foi aplicada ao texto.")
        c.auditarLinks(id, TEXTO)
        assertEquals(3, linha(id, RELATORIO).candidatosDeCorrecao.size)
    }

    @Test
    fun aListaSegueOTextoDaSessaoEODigitadoParaUmLinkQueSaiuNaoVaiParaOutro() {
        // Achados do Codex na #78: com a tela aberta durante a execução, o texto muda; a lista tem de
        // ser a do texto novo, e a decisão digitada para um link que saiu não pode valer para outro.
        val id = sessaoComLinks()
        val relatorio = abrirOLink(id, RELATORIO)
        val nota = "Nota escrita para o relatório oficial, que saiu do texto."
        regra.onNodeWithTag(Marcas.decisao("rejeitar")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput(nota)
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsEnabled()

        // Um revisor reescreveu o texto: a auditoria do texto novo e o checkpoint que grava o texto.
        c.auditarLinks(id, TEXTO_NOVO)
        c.mudarTextoAtual(id, TEXTO_NOVO)

        esperarTag(Marcas.link(linha(id, NOVA).linkId))
        assertTrue(regra.onAllNodesWithTag(Marcas.link(relatorio.linkId)).fetchSemanticsNodes().isEmpty())
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsNotEnabled()
        assertTrue(regra.onAllNodes(hasText(nota)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun sairDaTelaCancelaABuscaDePropostasEmCurso() {
        // Achado do Codex na #78: a busca bloqueia no HTTP e só para pelo cancelamento dela.
        val id = sessaoComLinks()
        c.buscaPresa = CountDownLatch(1)
        val relatorio = abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.waitUntil(5_000) { c.buscas.isNotEmpty() }

        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        // O ViewModel só é descartado quando a transição de saída (700 ms na `NavDisplay`) termina, e ela
        // corre no relógio do teste, um quadro por volta do `waitUntil`: sem adiantá-lo, o cancelamento
        // chegava entre 4,3 e 4,9 s depois do toque, colado no limite da espera.
        regra.mainClock.advanceTimeBy(1_000)
        regra.waitUntil(5_000) { c.buscasCanceladas.get() > 0 }
        // Cancelada, a busca não chega a gravar proposta nenhuma.
        Thread.sleep(500)
        assertTrue(c.links.linhas(id).single { it.linkId == relatorio.linkId }.candidatosDeCorrecao.isEmpty())
    }

    @Test
    fun oArquivoEscolhidoParaUmLinkQueSaiuDaListaNaoEImportado() {
        // Achado do Codex na #78: o seletor do sistema demora, e o link aberto pode mudar antes da volta.
        val id = sessaoComLinks()
        val seletor = SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", "<html></html>".toByteArray()))).apply { adiado = true }
        abrirOLink(id, RELATORIO, seletor)
        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()

        c.auditarLinks(id, TEXTO_NOVO)
        c.mudarTextoAtual(id, TEXTO_NOVO)
        esperarTag(Marcas.link(linha(id, NOVA).linkId))
        regra.runOnUiThread { seletor.entregar() }

        regra.esperarTexto("O link aberto mudou enquanto o arquivo era escolhido; nada foi importado. Escolha o arquivo de novo.")
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO, NOVA)).isEmpty())
    }

    @Test
    fun comASessaoEmExecucaoARevisaoEAsPropostasEsperamEACapturaSegue() {
        // Decisão 24 do operador (29/09/2026): a auditoria da sessão regrava as mesmas linhas durante a execução.
        val id = c.sessao(Estados.RODANDO, textoAtual = TEXTO)
        c.auditarLinks(id, TEXTO)
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.LINKS_EM_EXECUCAO).performScrollTo().assertExists()
        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().assertIsNotEnabled()
        regra.onNodeWithTag(Marcas.decisao("rejeitar")).performScrollTo().assertIsNotEnabled()
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsNotEnabled()
        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().assertIsEnabled()
        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aAbaLinksDosAutosLevaAosLinksAuditadosDaSessao() {
        val id = sessaoComLinks()
        val artefato = c.artefato(id, 1, Provedor.CLAUDE, TEXTO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.artefato(artefato))
        regra.onNodeWithTag(Marcas.aba("links")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.ABRIR_LINKS_DOS_AUTOS).performScrollTo().performClick()
        esperarTag(Marcas.link(linha(id, RELATORIO).linkId))
    }

    private companion object {
        const val RELATORIO = "https://exemplo.org/relatorio"
        const val INTERNO = "https://interno.exemplo/dados"
        const val TEXTO = "Primeira fonte em [Relatório oficial]($RELATORIO) e segunda em [Base interna]($INTERNO)."
        const val NOVA = "https://exemplo.org/nova"
        const val TEXTO_NOVO = "O texto reescrito cita só a [Fonte nova]($NOVA)."
    }
}
