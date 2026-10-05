package dev.lcv.maestro.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.ClassificacaoDoLink
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.LinhaDeLink
import dev.lcv.maestro.protocolo.StatusDaRevisao
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Estados
import dev.lcv.maestro.sessao.LinksDaSessao
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    /** Decisão 26 do operador (#82): a captura que o provedor nega é avisada com o motivo, e nada vira evidência. */
    @Test
    fun aCapturaQueOProvedorNegaEAvisadaComOMotivo() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO, SeletorDeTeste(DOCUMENTO_NEGADO))

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível ler o arquivo escolhido. Motivo: $ACESSO_NEGADO", substring = true)
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
    fun umaAuditoriaDoMesmoTextoApareceComATelaAberta() {
        // Achado do Codex na #78: a auditoria da sessão grava as linhas sem mudar o texto, e a tela
        // aberta não as via, porque só relia a lista quando o texto mudava.
        val id = c.sessao(Estados.AUDITORIA_FINAL, textoAtual = TEXTO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.ABRIR_LINKS)
        regra.onNodeWithTag(Marcas.ABRIR_LINKS).performScrollTo().performClick()
        esperarTag(Marcas.SEM_LINKS_AUDITADOS)

        c.auditarLinks(id, TEXTO)
        esperarTag(Marcas.link(linha(id, RELATORIO).linkId))
        esperarTag(Marcas.link(linha(id, INTERNO).linkId))
    }

    @Test
    fun oDiscoQueFalhaNaImportacaoEAvisadoSemDerrubarOAplicativo() {
        // Achado do Codex na #78: a `IOException` do armazém de evidências derrubava o aplicativo.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO, SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", "<html></html>".toByteArray()))))
        // Um arquivo onde a pasta das evidências deveria estar: o corpo importado não pode ser gravado.
        c.pastaDasEvidencias.deleteRecursively()
        c.pastaDasEvidencias.writeBytes(byteArrayOf(0))

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto(
            "A importação falhou. O arquivo local não foi alterado. Motivo: cannot create directory ${c.pastaDasEvidencias.absolutePath}",
        )
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO)).isEmpty())
    }

    @Test
    fun oDiscoQueFalhaNaBuscaEAvisadoSemDerrubarOAplicativo() {
        // A busca real guarda cada resultado como evidência, e a falha do armazém sai dela crua.
        val id = sessaoComLinks()
        c.discoDaBusca = IOException("No space left on device")
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.esperarTexto("A busca de candidatos falhou. O link e o texto permaneceram inalterados. Motivo: No space left on device")
        assertTrue(linha(id, RELATORIO).candidatosDeCorrecao.isEmpty())
    }

    @Test
    fun aBuscaQueTerminaDepoisDeATelaSairNaoGravaAsPropostas() {
        // Achado do Codex na #78: a resposta HTTP já tinha chegado, não há chamada para cancelar, e o
        // motor ainda gravaria as propostas na linha depois de a tela sair.
        val id = sessaoComLinks()
        c.resultadosDaBusca = listOf(c.resultadoDeBusca("https://exemplo.org/substituto", "Relatório substituto"))
        c.buscaPresa = CountDownLatch(1)
        c.buscaTerminouAntes = true
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.waitUntil(5_000) { c.buscas.isNotEmpty() }

        regra.onNodeWithTag(Marcas.VOLTAR).performClick()
        // A transição de saída corre no relógio do teste (ver `sairDaTelaCancelaABuscaDePropostasEmCurso`).
        regra.mainClock.advanceTimeBy(1_000)
        regra.waitUntil(5_000) { c.buscasCanceladas.get() > 0 }
        Thread.sleep(500)
        assertTrue(linha(id, RELATORIO).candidatosDeCorrecao.isEmpty())
    }

    // Achado do Codex na #78: o registro é global, e outra sessão com o mesmo texto, em execução, divide as linhas.

    @Test
    fun aDecisaoNumaLinhaDeOutraSessaoEmExecucaoEsperaEla() {
        val id = sessaoComLinks()
        c.sessao(Estados.RODANDO, textoAtual = TEXTO)
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")

        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("A decisão não foi registrada. Motivo: ${LinksDaSessao.MENSAGEM_EM_AUDITORIA}")
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
    }

    @Test
    fun asPropostasNumaLinhaDeOutraSessaoEmExecucaoEsperamEla() {
        val id = sessaoComLinks()
        c.sessao(Estados.NA_FILA, textoAtual = TEXTO)
        c.resultadosDaBusca = listOf(c.resultadoDeBusca("https://exemplo.org/substituto", "Relatório substituto"))
        abrirOLink(id, RELATORIO)

        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.esperarTexto(
            "A busca de candidatos falhou. O link e o texto permaneceram inalterados. Motivo: ${LinksDaSessao.MENSAGEM_EM_AUDITORIA}",
        )
        assertTrue(linha(id, RELATORIO).candidatosDeCorrecao.isEmpty())
    }

    // Decisão 25 do operador (29/09/2026): o banco cheio numa ação da tela é a falha da ação, sem derrubar o aplicativo.

    @Test
    fun oBancoCheioNaPassagemAoNavegadorEAvisado() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível registrar o handoff para o navegador padrão. Motivo: ${BancoCheio.MENSAGEM}")
        // Sem registro, sem disparo: o navegador não recebe a URL de uma passagem que não ficou auditada.
        assertTrue(c.navegador.abertas.isEmpty())
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO)).isEmpty())
    }

    @Test
    fun oDiscoQueFalhaAoMontarABuscaEAvisado() {
        // Achado do Codex na #78: montar a busca já lê o Room, fora do tratamento da busca.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        c.bancoCheio.leituraQuebrada = "configuracoes"

        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.esperarTexto("A busca de candidatos falhou. O link e o texto permaneceram inalterados. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        assertTrue(c.buscas.isEmpty())
    }

    @Test
    fun oRegistroDePassagemJaEstaGravadoQuandoONavegadorAbre() {
        // Achado do Codex na #78: depois do `startActivity`, o Android pode matar o aplicativo.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        val passagem = ImportacaoDoOperador.idDaPassagem(RELATORIO)
        var noDisparo: List<String>? = null
        // O disparo é na linha principal, e o Room não lê nela: a leitura vai para outra thread, e o disparo a espera.
        c.navegador.aoAbrir = { thread { noDisparo = c.evidencias.existente(passagem)?.registro?.notas }.join() }

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto("Handoff registrado. Exporte o artefato no navegador e importe-o abaixo.")
        assertEquals(2, noDisparo?.size)
        assertEquals("Default-browser handoff launched", c.evidencias.existente(passagem)!!.registro.notas.last())
    }

    @Test
    fun oDiscoQueFalhaSoAoAnotarOResultadoDaPassagemDizQueONavegadorAbriu() {
        // O registro já foi gravado e o navegador já recebeu a URL: só a anotação do resultado falha.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        c.navegador.aoAbrir = { c.bancoCheio.cheio = true }

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto("Handoff registrado e navegador aberto, mas o resultado não foi anotado no registro. Motivo: ${BancoCheio.MENSAGEM}")
        c.bancoCheio.cheio = false
        assertEquals(1, c.navegador.abertas.size)
        // O registro de antes do disparo ficou; a anotação do resultado, não.
        val notas = c.evidencias.existente(ImportacaoDoOperador.idDaPassagem(RELATORIO))!!.registro.notas
        assertEquals(2, notas.size)
        assertTrue(notas.none { it == "Default-browser handoff launched" })
    }

    @Test
    fun semNavegadorEComAAnotacaoQueFalhaOAvisoDizAsDuasCoisas() {
        // O navegador não abre e a anotação do resultado também não grava: o aviso não pode dizer que ele abriu.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        c.navegador.falha = "no activity found"
        c.navegador.aoAbrir = { c.bancoCheio.cheio = true }

        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto(
            "Handoff registrado, mas o navegador não abriu (no activity found) e o resultado não foi anotado no registro. " +
                "Motivo: ${BancoCheio.MENSAGEM}",
        )
        c.bancoCheio.cheio = false
    }

    @Test
    fun oBancoCheioNaImportacaoEAvisado() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO, SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", "<html></html>".toByteArray()))))
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto("A importação falhou. O arquivo local não foi alterado. Motivo: ${BancoCheio.MENSAGEM}")
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO)).isEmpty())
    }

    @Test
    fun oDiscoQueFalhaAoConsultarAEvidenciaDaImportacaoEAvisado() {
        // Antes de gravar, a importação consulta a evidência já guardada do endereço, e essa leitura falha.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO, SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", "<html></html>".toByteArray()))))
        c.bancoCheio.leituraQuebrada = "evidencias"

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.esperarTexto("A importação falhou. O arquivo local não foi alterado. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        c.bancoCheio.leituraQuebrada = null
        assertTrue(c.evidencias.registrosDe(setOf(RELATORIO)).isEmpty())
    }

    @Test
    fun oBancoCheioNaDecisaoEAvisado() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("A decisão não foi registrada. Motivo: ${BancoCheio.MENSAGEM}")
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
    }

    // Rodada 8 do Codex na #78.

    @Test
    fun aReleituraQueFalhaDepoisDaDecisaoAvisaEMantemALista() {
        val id = sessaoComLinks()
        val relatorio = abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")
        // A decisão grava; a lista relida depois dela lê as evidências, e essa leitura falha.
        c.bancoCheio.leituraQuebrada = "evidencias"

        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("Não foi possível reler a lista de links; ela ficou como estava. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}")
        assertNotEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
        regra.onNodeWithTag(Marcas.link(relatorio.linkId)).assertExists()
    }

    @Test
    fun oQueSeDigitaDuranteARevisaoFicaNoFormulario() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")
        // A revisão fica presa na gravação do diário, e nesse meio-tempo o operador escreve mais.
        val trava = CountDownLatch(1)
        c.bancoCheio.tabelaTravada = "eventos_de_links"
        c.bancoCheio.trava = trava

        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput(" Mais uma linha.")
        trava.countDown()
        regra.esperarTexto("Decisão registrada sobre a versão verificada. Nenhuma substituição foi aplicada ao texto.")
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).assertTextEquals("Aguardando a fonte ser conferida. Mais uma linha.")
    }

    @Test
    fun aNotaDigitadaDuranteAImportacaoFicaNoFormulario() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO, SeletorDeTeste(Uri.fromFile(c.arquivo("pagina-salva.html", "<html></html>".toByteArray()))))
        regra.onNodeWithTag(Marcas.NOTA_DA_CAPTURA).performScrollTo().performTextInput("Página salva do site oficial.")
        val trava = CountDownLatch(1)
        c.bancoCheio.tabelaTravada = "evidencias"
        c.bancoCheio.trava = trava

        regra.onNodeWithTag(Marcas.IMPORTAR_CAPTURA).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_CAPTURA).performScrollTo().performTextInput(" Segunda cópia.")
        trava.countDown()
        regra.esperarTexto("Artefato importado, hasheado e registrado com proveniência do operador.")
        regra.onNodeWithTag(Marcas.NOTA_DA_CAPTURA).assertTextEquals("Página salva do site oficial. Segunda cópia.")
    }

    @Test
    fun aLinhaAbertaQueVoltaComOutroHashRecomecaOFormulario() {
        // P1: outra sessão com o mesmo texto audita e a linha, com o mesmo id, passa a ter outro hash.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")

        c.auditarLinks(id, TEXTO) { url -> c.resultadoDeBusca(url, "Relatório") }
        regra.waitUntil(5_000) { regra.onAllNodes(hasText("Aguardando a fonte ser conferida.", substring = true)).fetchSemanticsNodes().isEmpty() }
        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().assertIsNotEnabled()
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
    }

    @Test
    fun aDecisaoCujoDiarioNaoGravaNaoFicaNaLinha() {
        // Achado do Codex na #78: a linha era gravada numa transação e o diário noutra.
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        regra.onNodeWithTag(Marcas.decisao("quarentena")).performScrollTo().performClick()
        regra.onNodeWithTag(Marcas.NOTA_DA_REVISAO).performScrollTo().performTextInput("Aguardando a fonte ser conferida.")
        c.bancoCheio.soNaTabela = "eventos_de_links"
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.REGISTRAR_DECISAO).performScrollTo().performClick()
        regra.esperarTexto("A decisão não foi registrada. Motivo: ${BancoCheio.MENSAGEM}")
        assertEquals(StatusDaRevisao.PENDENTE, linha(id, RELATORIO).statusDaRevisao)
    }

    @Test
    fun asPropostasCujoDiarioNaoGravaNaoFicamNaLinha() {
        val id = sessaoComLinks()
        c.resultadosDaBusca = listOf(c.resultadoDeBusca("https://exemplo.org/substituto", "Relatório substituto"))
        abrirOLink(id, RELATORIO)
        c.bancoCheio.soNaTabela = "eventos_de_links"
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.esperarTexto("A busca de candidatos falhou. O link e o texto permaneceram inalterados. Motivo: ${BancoCheio.MENSAGEM}")
        assertTrue(linha(id, RELATORIO).candidatosDeCorrecao.isEmpty())
    }

    @Test
    fun oBancoCheioNasPropostasEAvisado() {
        val id = sessaoComLinks()
        c.resultadosDaBusca = listOf(c.resultadoDeBusca("https://exemplo.org/substituto", "Relatório substituto"))
        abrirOLink(id, RELATORIO)
        c.bancoCheio.cheio = true

        regra.onNodeWithTag(Marcas.BUSCAR_PROPOSTAS).performScrollTo().performClick()
        regra.esperarTexto("A busca de candidatos falhou. O link e o texto permaneceram inalterados. Motivo: ${BancoCheio.MENSAGEM}")
        assertTrue(linha(id, RELATORIO).candidatosDeCorrecao.isEmpty())
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

    // Decisão 25 do operador, estendida em 30/09/2026 (#80): as leituras de abrir e voltar à tela e a observação.

    private val avisoGeral = "Não foi possível ler os dados do aparelho. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
    private val noLugar = "Não foi possível ler os dados desta tela; ela tenta de novo quando você voltar a ela. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"

    /** A tela ao segundo plano e de volta: só o ON_RESUME relê. */
    private fun voltarATela() {
        regra.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        regra.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun esperarMotivoNoLugar() {
        regra.waitUntil(5_000) { regra.onAllNodes(hasTestTag(Marcas.LEITURA_FALHOU) and hasText(noLugar)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aListaQueNaoSeLeAoAbrirMostraOMotivoNoLugarEVoltaNaVoltaDaTela() {
        val id = sessaoComLinks()
        val alvo = linha(id, RELATORIO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.ABRIR_LINKS)
        c.bancoCheio.leituraQuebrada = "FROM links"
        regra.onNodeWithTag(Marcas.ABRIR_LINKS).performScrollTo().performClick()
        esperarMotivoNoLugar()
        regra.esperarTexto(avisoGeral)
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        esperarTag(Marcas.link(alvo.linkId))
        regra.onNodeWithTag(Marcas.LEITURA_FALHOU).assertDoesNotExist()
    }

    @Test
    fun aSessaoQueNaoSeLeNosLinksMostraOMotivoNoLugar() {
        val id = sessaoComLinks()
        val alvo = linha(id, RELATORIO)
        regra.abrir(c, sessaoPedida = id)
        esperarTag(Marcas.ABRIR_LINKS)
        c.bancoCheio.leituraQuebrada = "FROM sessoes WHERE id"
        regra.onNodeWithTag(Marcas.ABRIR_LINKS).performScrollTo().performClick()
        esperarMotivoNoLugar()
        regra.esperarTexto(avisoGeral)
        regra.onNodeWithText("Sessão não encontrada.").assertDoesNotExist()
        c.bancoCheio.leituraQuebrada = null
        voltarATela()
        esperarTag(Marcas.link(alvo.linkId))
    }

    @Test
    fun aAcaoReabreOAvisoDaReleituraQueFalhaNaMesmaVolta() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        val releitura = "Não foi possível reler a lista de links; ela ficou como estava. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
        c.bancoCheio.leituraQuebrada = "FROM links"
        voltarATela()
        regra.esperarTexto(releitura)
        regra.waitUntil(15_000) { regra.onAllNodesWithText(releitura).fetchSemanticsNodes().isEmpty() }
        regra.onNodeWithTag(Marcas.ABRIR_NO_NAVEGADOR).performScrollTo().performClick()
        regra.esperarTexto(releitura)
        c.bancoCheio.leituraQuebrada = null
    }

    @Test
    fun aReleituraQueFalhouNaoERefeitaACadaGravacaoDaMesmaVolta() {
        val id = sessaoComLinks()
        abrirOLink(id, RELATORIO)
        val releitura = "Não foi possível reler a lista de links; ela ficou como estava. Motivo: ${BancoCheio.MENSAGEM_DE_DISCO}"
        c.bancoCheio.leituraQuebrada = "FROM links"
        voltarATela()
        regra.esperarTexto(releitura)
        Thread.sleep(1_000)
        regra.waitForIdle()
        val antes = c.bancoCheio.quebradas.get()
        // A auditoria de outra sessão grava nas mesmas tabelas: a releitura que falhou espera a volta.
        c.tocarLinks()
        c.tocarLinks()
        Thread.sleep(1_000)
        regra.waitForIdle()
        assertEquals(antes, c.bancoCheio.quebradas.get())
        voltarATela()
        regra.waitUntil(5_000) { c.bancoCheio.quebradas.get() > antes }
        c.bancoCheio.leituraQuebrada = null
    }
}
