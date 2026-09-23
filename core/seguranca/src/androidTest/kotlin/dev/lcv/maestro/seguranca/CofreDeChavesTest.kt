package dev.lcv.maestro.seguranca

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Os casos 2 a 5 da seção 8 da especificação, mais o que a medição de
 * 23/09/2026 acrescentou. O caso 1 exige StrongBox e está em
 * [CofreDeChavesStrongBoxTest].
 *
 * O teste define um PIN de tela antes de cada caso — o Keystore não gera chave
 * presa à autenticação sem trava de tela — e o remove depois. Definir o PIN
 * conta como autenticação, e `locksettings verify` autentica de novo, sem
 * tela: é assim que o teste abre e fecha a janela.
 *
 * **Só roda em aparelho sem trava de tela nenhuma**, como o emulador da CI. Num
 * aparelho que já tem trava, a classe inteira pula: remover a trava de um
 * aparelho de verdade apagaria as chaves presas à autenticação de todos os
 * aplicativos dele, e o teste não toca na trava de ninguém.
 *
 * Nenhum teste usa valor com forma de chave real.
 */
@RunWith(AndroidJUnit4::class)
class CofreDeChavesTest {

    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val contexto = instrumentacao.targetContext
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alias = "teste_${System.nanoTime()}"
    private val arquivo = File(contexto.cacheDir, "$alias.preferences_pb")
    private val armazem = PreferenceDataStoreFactory.create(scope = escopo) { arquivo }
    private val cofre = CofreDeChaves(contexto, armazem, JANELA, alias)

    private val chave = "valor-de-teste-do-cofre"

    private fun shell(comando: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand(comando))
            .bufferedReader().readText().trim()

    private fun autenticar() {
        assertTrue(shell("locksettings verify --old $PIN").contains("verified"))
    }

    private fun esperarAJanelaVencer() = Thread.sleep(JANELA.inWholeMilliseconds + 2_000)

    /**
     * Autentica logo antes e exige que a chave tenha sido guardada de fato.
     * Gerar a chave num emulador lento pode passar da janela de teste; sem
     * isto, a guarda voltaria `ExigeAutenticacao` em silêncio e o caso
     * falharia por outro motivo.
     */
    private suspend fun guardar(provedor: Provedor, valor: String = chave): Guarda.Guardada {
        autenticar()
        val guarda = cofre.guardar(provedor, valor)
        assertTrue("a chave não foi guardada: $guarda", guarda is Guarda.Guardada)
        return guarda as Guarda.Guardada
    }

    private fun temStrongBox() =
        contexto.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /** Só verdadeiro se foi este teste que definiu o PIN. */
    private var definiuOPin = false

    @Before
    fun definirPin() {
        assumeFalse(
            "o aparelho já tem trava de tela; este teste só mexe na trava de um aparelho sem nenhuma",
            contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure,
        )
        assertTrue(shell("locksettings set-pin $PIN").contains(PIN))
        definiuOPin = true
    }

    @After
    fun limpar() {
        if (definiuOPin) shell("locksettings clear --old $PIN")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        escopo.cancel()
        arquivo.delete()
    }

    @Test
    fun caso2_sem_strongbox_cai_no_caminho_sem_ele_e_registra_qual() = runBlocking {
        // No emulador a ausência do hardware é real, não encenada.
        assumeFalse("aparelho com StrongBox; este caso roda num que não o tenha", temStrongBox())

        val nivel = guardar(Provedor.CLAUDE).nivel
        assertNotEquals(NivelDoCofre.STRONGBOX, nivel)
        assertEquals(nivel, cofre.nivel())
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun caso3_sem_autenticacao_recente_a_decifra_e_recusada() = runBlocking {
        // Sem este caso, uma implementação que esquecesse
        // `setUserAuthenticationRequired(true)` passaria em todos os outros.
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()

        assertEquals(LeituraDaChave.ExigeAutenticacao, cofre.chaveDe(Provedor.CLAUDE))
    }

    @Test
    fun caso4_dentro_da_janela_a_decifra_repetida_nao_pede_nova_autenticacao() = runBlocking {
        // É o que distingue o modo por tempo do modo por operação.
        guardar(Provedor.CLAUDE)
        autenticar()

        repeat(3) {
            assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
        }
    }

    @Test
    fun caso5_janela_expirada_pede_autenticacao_e_a_chave_continua_intacta() = runBlocking {
        // O estado `pausada_aguardando_autenticacao` é do `:core:sessao`; aqui
        // se prova o resultado tipado que o alimenta, e que a chave não se
        // perdeu: autenticar de novo basta.
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()
        assertEquals(LeituraDaChave.ExigeAutenticacao, cofre.chaveDe(Provedor.CLAUDE))

        autenticar()
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun remover_a_trava_de_tela_deixa_o_cifrado_orfao_e_vira_ausente() = runBlocking {
        // Medido em 23/09/2026 no Android 17: remover a trava apaga a chave do
        // Keystore, em vez de invalidá-la. É a linha "o alias não existe" da
        // seção 4.2.
        guardar(Provedor.CLAUDE)
        assertTrue(cofre.configurada(Provedor.CLAUDE))

        shell("locksettings clear --old $PIN")

        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertFalse(cofre.configurada(Provedor.CLAUDE))
    }

    @Test
    fun sem_trava_de_tela_nada_e_guardado() = runBlocking {
        shell("locksettings clear --old $PIN")

        assertEquals(Guarda.SemTravaDeTela, cofre.guardar(Provedor.CLAUDE, chave))
        assertFalse(cofre.configurada(Provedor.CLAUDE))
    }

    @Test
    fun o_cifrado_de_um_provedor_nao_abre_como_o_de_outro() = runBlocking {
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        armazem.edit { it[byteArrayPreferencesKey("chave_codex")] = doClaude }

        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CODEX))
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun cada_provedor_tem_a_propria_chave_e_apagar_uma_nao_toca_as_outras() = runBlocking {
        guardar(Provedor.CLAUDE, "valor-um")
        guardar(Provedor.GEMINI, "valor-dois")
        autenticar()

        cofre.apagar(Provedor.CLAUDE)

        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertEquals("valor-dois", (cofre.chaveDe(Provedor.GEMINI) as LeituraDaChave.Presente).valor)
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.DEEPSEEK))
    }

    @Test
    fun as_tres_causas_da_secao_4_2_tem_tres_respostas() {
        // A invalidação definitiva não se provoca no emulador: remover a
        // trava apaga a chave em vez de invalidá-la. A tradução se prova com
        // as exceções da própria plataforma.
        assertEquals(LeituraDaChave.ExigeAutenticacao, traduzir(UserNotAuthenticatedException()))
        assertEquals(LeituraDaChave.Irrecuperavel, traduzir(KeyPermanentlyInvalidatedException()))
        assertEquals(LeituraDaChave.Ausente, traduzir(AEADBadTagException()))
    }

    private companion object {
        const val PIN = "1234"
        val JANELA = 3.seconds
    }
}
