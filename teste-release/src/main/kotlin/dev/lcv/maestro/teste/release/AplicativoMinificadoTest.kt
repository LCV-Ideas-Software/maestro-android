/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.teste.release

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Os fluxos do aplicativo minificado pelo R8, de fora do processo dele: o teste só vê a tela, como a pessoa, e por isso
 * não depende de nome de classe que o R8 renomeia. Cada fluxo exercita uma parte que o R8 pode quebrar em execução: a
 * casca, o Room e o Compose ao abrir cada tela; a pilha da Navigation 3, que o kotlinx.serialization grava e relê quando
 * a Activity é recriada; o Jackson, que grava e relê as tarifas das configurações; o Keystore, o OkHttp, o TLS e o
 * Jackson no teste de uma chave que o provedor recusa; a commonmark-java na tela de licenças. Uma queda do processo faz
 * o elemento seguinte não aparecer, e o teste cai.
 */
@RunWith(AndroidJUnit4::class)
class AplicativoMinificadoTest {

    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val aparelho = UiDevice.getInstance(instrumentacao)

    @Before
    fun abrir() {
        // Uma trava de tela esquecida por uma rodada interrompida prenderia o emulador no boot seguinte.
        aparelho.executeShellCommand("locksettings clear --old $PIN")
        aparelho.executeShellCommand("pm clear $PACOTE")
        abrirOAplicativo()
    }

    @After
    fun limpar() {
        aparelho.unfreezeRotation()
        aparelho.executeShellCommand("locksettings clear --old $PIN")
        aparelho.executeShellCommand("pm clear $PACOTE")
    }

    @Test
    fun asTelasAbremEAsLicencasMostramOsTextosEmbarcados() {
        abrirConfiguracoes()
        tocar(By.text(LICENCAS))
        assertNotNull("a tela de licenças não mostrou os componentes de terceiros", esperarRolando(By.text(TERCEIROS), ESPERA_ROLANDO))
        // Uma linha da tabela do THIRDPARTY.md, que só existe lida pela commonmark-java com a extensão de tabelas GFM.
        assertNotNull("a tabela de componentes de terceiros não apareceu", esperarRolando(By.textContains(LINHA_DA_TABELA), ESPERA_ROLANDO))
        assertTrue("o processo do aplicativo morreu", processoVivo())
    }

    @Test
    fun aPilhaDeTelasVoltaDepoisDeRecriarAActivity() {
        abrirConfiguracoes()
        // A rotação recria a Activity (o manifesto não declara `configChanges`): a pilha de telas é gravada e relida
        // pelo serializador que o plugin do kotlinx.serialization gera para cada chave da navegação.
        aparelho.setOrientationLeft()
        assertNotNull("a tela de configurações não voltou depois da rotação", aparelho.wait(Until.findObject(By.text(CHAVES)), ESPERA))
        aparelho.setOrientationNatural()
        assertNotNull("a tela de configurações não voltou depois da segunda rotação", aparelho.wait(Until.findObject(By.text(CHAVES)), ESPERA))
        achar(By.desc(VOLTAR)).click()
        assertNotNull("voltar não levou à tela inicial", aparelho.wait(Until.findObject(By.text(PEDIDO_EDITORIAL)), ESPERA))
        assertTrue("o processo do aplicativo morreu", processoVivo())
    }

    @Test
    fun oTetoGravadoVoltaDepoisDeFecharOAplicativo() {
        abrirConfiguracoes()
        campoAbaixoDe(TETO).text = "7.5"
        esconderOTeclado()
        tocar(By.text(SALVAR_CONFIGURACOES))
        assertNotNull("as configurações não foram salvas", aparelho.wait(Until.findObject(By.text(CONFIGURACOES_SALVAS)), ESPERA))
        aparelho.executeShellCommand("am force-stop $PACOTE")
        abrirOAplicativo()
        // A tela inicial relê as configurações do Room, com as tarifas em JSON relidas pelo Jackson.
        assertNotNull("o teto gravado não voltou na tela inicial", esperarRolando(By.text(TETO_GRAVADO), ESPERA))
        assertTrue("o processo do aplicativo morreu", processoVivo())
    }

    /**
     * Uma chave falsa no Claude: a Anthropic recusa (HTTP 401) sem cobrar, e o pedido não leva nada do usuário, só o
     * texto fixo do teste de chaves. É a única chamada a um provedor real nos testes do repositório, e existe porque só
     * ela prova o OkHttp e o TLS no aplicativo minificado. A mensagem do provedor só aparece extraída do JSON da
     * resposta pelo Jackson; se ele falhasse, a tela mostraria o corpo cru, com as chaves do JSON. Sem rede, o teste se
     * declara pulado, não verde.
     */
    @Test
    fun oTesteDeUmaChaveRecusadaMostraAMensagemDoProvedor() {
        assumeTrue("sem rede até a Anthropic: a chamada não foi provada", alcanca(ANTHROPIC))
        // O Keystore só guarda a chave com trava de tela; o aplicativo lê a trava ao abrir.
        aparelho.executeShellCommand("locksettings set-pin $PIN")
        aparelho.executeShellCommand("am force-stop $PACOTE")
        abrirOAplicativo()
        abrirConfiguracoes()
        campoAbaixoDe(CHAVE_CLAUDE).text = CHAVE_FALSA
        esconderOTeclado()
        // Cada agente tem o seu Salvar: o do Claude é o primeiro abaixo do campo dele. Fechar o teclado deixa a tela
        // rolada, e o primeiro Salvar à vista pode ser o do Codex, de campo vazio (medido em 06/10/2026).
        abaixoDe(campoAbaixoDe(CHAVE_CLAUDE), SALVAR).click()
        // A chave é cifrada sem pedir a credencial, que só é pedida para usá-la; o aviso passa, a pílula fica.
        assertNotNull("a chave não foi guardada", esperarRolando(By.text(CONFIGURADA), ESPERA))
        tocar(By.text(TESTAR_CHAVES))
        val corpo = achar(By.textStartsWith(CORPO_DO_DIALOGO))
        // O Testar chaves do diálogo é o que fica logo abaixo do texto dele; o da tela pode estar atrás do diálogo.
        abaixoDe(corpo, TESTAR_CHAVES).click()
        assertTrue("o diálogo do teste não fechou", aparelho.wait(Until.gone(By.text(TITULO_DO_TESTE)), ESPERA))
        responderOPinSePedido()
        val resultado = esperarRolando(By.textStartsWith(RECUSA), ESPERA_REDE)
        assertNotNull("o teste da chave não mostrou a recusa do provedor", resultado)
        val mensagem = resultado!!.text
        Log.i(ROTULO, "resultado do teste: $mensagem")
        assertFalse("a mensagem não veio do JSON da resposta: $mensagem", mensagem.contains("{"))
        assertTrue("o processo do aplicativo morreu", processoVivo())
    }

    private fun abrirOAplicativo() {
        aparelho.pressHome()
        val intencao = instrumentacao.context.packageManager.getLaunchIntentForPackage(PACOTE)
        assertNotNull("o aplicativo $PACOTE não está instalado", intencao)
        intencao!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Logo depois do `pm clear`, o sistema ainda pode matar o aplicativo recém-aberto (medido na calculadora-android,
        // CI da #97): uma segunda abertura, e o fluxo só cai se nem ela abrir.
        val abriu = (1..2).any {
            instrumentacao.context.startActivity(intencao)
            aparelho.wait(Until.hasObject(By.text(PEDIDO_EDITORIAL)), ESPERA)
        }
        assertTrue("o aplicativo não abriu", abriu)
    }

    private fun abrirConfiguracoes() {
        achar(By.desc(CONFIGURACOES)).click()
        assertNotNull("a tela de configurações não abriu", aparelho.wait(Until.findObject(By.text(CHAVES)), ESPERA))
    }

    /** O campo de texto logo abaixo do rótulo: o rótulo é um texto separado, acima da caixa. */
    private fun campoAbaixoDe(rotulo: String): UiObject2 {
        val alvo = trazerAoMeio(By.text(rotulo))
        val base = alvo.visibleBounds.bottom
        return requireNotNull(
            aparelho.findObjects(By.clazz("android.widget.EditText"))
                .filter { it.visibleBounds.top >= base - 8 }
                .minByOrNull { it.visibleBounds.top },
        ) { "sem campo abaixo de \"$rotulo\"" }
    }

    /** O elemento com o texto que fica logo abaixo de [acima], na tela. */
    private fun abaixoDe(acima: UiObject2, texto: String): UiObject2 {
        val base = acima.visibleBounds.bottom
        return requireNotNull(
            aparelho.findObjects(By.text(texto))
                .filter { it.visibleBounds.top >= base - 8 }
                .minByOrNull { it.visibleBounds.top },
        ) { "sem \"$texto\" abaixo do elemento esperado" }
    }

    /** Toca o controle depois de trazê-lo ao meio da tela: o UI Automator toca no centro da parte visível. */
    private fun tocar(seletor: BySelector) {
        trazerAoMeio(seletor).click()
    }

    private fun trazerAoMeio(seletor: BySelector): UiObject2 {
        var alvo = requireNotNull(esperarRolando(seletor, ESPERA_ROLANDO)) { "não achei $seletor na tela" }
        val altura = aparelho.displayHeight
        var voltas = 0
        while (alvo.visibleBounds.bottom > altura * 0.7 && voltas < 6) {
            aparelho.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.3f)
            alvo = aparelho.wait(Until.findObject(seletor), ESPERA) ?: break
            voltas++
        }
        return alvo
    }

    /**
     * Espera o elemento rolando a tela, porque o UI Automator só vê o que está nela: desce até o fim e só então sobe. A
     * tela de configurações tem nove telas de altura, e a busca que trocava de sentido a cada tentativa oscilava no
     * meio dela sem chegar às licenças (medido em 06/10/2026). O fim é a tela que não muda depois de rolar, porque o
     * retorno de `scroll` não é confiável no Compose (medido na calculadora-android).
     */
    private fun esperarRolando(seletor: BySelector, espera: Long): UiObject2? {
        val limite = SystemClock.uptimeMillis() + espera
        var sentido = Direction.DOWN
        while (SystemClock.uptimeMillis() < limite) {
            aparelho.findObject(seletor)?.let { return it }
            val antes = retrato()
            aparelho.findObject(By.scrollable(true))?.scroll(sentido, 0.6f)
            aparelho.waitForIdle()
            if (retrato() == antes) sentido = if (sentido == Direction.DOWN) Direction.UP else Direction.DOWN
        }
        return aparelho.findObject(seletor)
    }

    /**
     * Os textos à vista e as suas posições: igual antes e depois de rolar, a rolagem chegou ao fim. Um elemento que o
     * Compose recriou no meio da leitura conta como tela que mudou.
     */
    private fun retrato(): String = try {
        aparelho.findObjects(By.clazz("android.widget.TextView")).joinToString("|") { "${it.text}@${it.visibleBounds.top}" }
    } catch (_: StaleObjectException) {
        "mudou@${SystemClock.uptimeMillis()}"
    }

    private fun achar(seletor: BySelector): UiObject2 =
        requireNotNull(aparelho.wait(Until.findObject(seletor), ESPERA)) { "não achei $seletor na tela" }

    /** O teclado na tela cobre os botões de baixo; voltar o fecha, e só é pedido com ele aberto, para não sair da tela. */
    private fun esconderOTeclado() {
        if (aparelho.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) {
            aparelho.pressBack()
            aparelho.waitForIdle()
        }
    }

    /**
     * Responde o pedido de credencial do aparelho com o PIN, se ele abrir. O `BiometricPrompt` mora no SystemUI e, sem
     * biometria cadastrada, mostra só o teclado do PIN; a janela em foco é a dele.
     */
    private fun responderOPinSePedido() {
        val limite = SystemClock.uptimeMillis() + ESPERA_DO_PIN
        while (!pedidoDeCredencialAberto() && SystemClock.uptimeMillis() < limite) SystemClock.sleep(250)
        if (!pedidoDeCredencialAberto()) return
        SystemClock.sleep(1_000)
        aparelho.executeShellCommand("input text $PIN")
        aparelho.executeShellCommand("input keyevent 66")
        val fim = SystemClock.uptimeMillis() + ESPERA
        while (pedidoDeCredencialAberto() && SystemClock.uptimeMillis() < fim) SystemClock.sleep(250)
    }

    private fun pedidoDeCredencialAberto(): Boolean {
        val foco = aparelho.executeShellCommand("dumpsys window").lineSequence().firstOrNull { "mCurrentFocus" in it }.orEmpty()
        return "BiometricPrompt" in foco || "com.android.systemui" in foco || "ConfirmDeviceCredential" in foco
    }

    /** O provedor responde ao próprio teste: qualquer resposta HTTP prova a rede, inclusive a recusa sem chave. */
    private fun alcanca(endereco: String): Boolean = try {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        conexao.connectTimeout = 15_000
        conexao.readTimeout = 15_000
        try {
            conexao.responseCode > 0
        } finally {
            conexao.disconnect()
        }
    } catch (erro: IOException) {
        Log.i(ROTULO, "o provedor $endereco não respondeu ao teste: $erro")
        false
    }

    private fun processoVivo(): Boolean = aparelho.executeShellCommand("pidof $PACOTE").isNotBlank()

    private companion object {
        const val PACOTE = "dev.lcv.maestro"
        const val ROTULO = "AplicativoMinificado"
        const val ESPERA = 15_000L
        const val ESPERA_REDE = 60_000L
        // A tela de configurações tem nove telas de altura; descer e voltar cabe com folga.
        const val ESPERA_ROLANDO = 40_000L
        const val ESPERA_DO_PIN = 8_000L
        const val PIN = "1234"

        const val PEDIDO_EDITORIAL = "Pedido editorial"
        const val CONFIGURACOES = "Configurações"
        const val VOLTAR = "Voltar"
        const val CHAVES = "Chaves dos agentes"
        const val LICENCAS = "Licenças"
        const val TERCEIROS = "Componentes de terceiros"
        const val LINHA_DA_TABELA = "actions/checkout"
        const val TETO = "Teto financeiro por sessão (USD)"
        const val SALVAR_CONFIGURACOES = "Salvar configurações"
        const val CONFIGURACOES_SALVAS = "Configurações salvas."
        const val TETO_GRAVADO = "US$ 7.50"
        const val CHAVE_CLAUDE = "Chave de API (Claude)"
        // Sem a forma de uma chave real, nem inválida (especificação, seção 8): o secret scanning não distingue.
        const val CHAVE_FALSA = "chave-falsa-do-teste-do-minificado"
        const val SALVAR = "Salvar"
        const val CONFIGURADA = "configurada"
        const val TESTAR_CHAVES = "Testar chaves"
        const val TITULO_DO_TESTE = "Testar as chaves configuradas?"
        const val CORPO_DO_DIALOGO = "Uma chamada pequena e paga"
        const val RECUSA = "PROVIDER_ERROR_HTTP_401_AUTH: "
        const val ANTHROPIC = "https://api.anthropic.com/v1/messages"
    }
}
