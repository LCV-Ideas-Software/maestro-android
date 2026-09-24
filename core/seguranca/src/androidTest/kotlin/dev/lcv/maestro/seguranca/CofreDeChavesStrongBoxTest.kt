package dev.lcv.maestro.seguranca

import android.app.KeyguardManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.security.KeyStore
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O caso 1 da seção 8: com StrongBox, o segredo cifrado volta em claro. Exige o
 * hardware, que o emulador não tem. Não há aparelho com ele disponível, e o
 * operador decidiu em 23/09/2026 que o caso não será rodado; o teste fica,
 * para rodar em qualquer aparelho que tenha o hardware. No emulador, pula.
 *
 * **Não toca na trava de tela.** Num aparelho de verdade, remover a trava
 * apagaria as chaves presas à autenticação de todos os aplicativos. O caso
 * exige, então, aparelho que já tenha trava e que tenha sido desbloqueado nos
 * [JANELA] anteriores: desbloquear é a autenticação que a chave pede.
 */
@RunWith(AndroidJUnit4::class)
class CofreDeChavesStrongBoxTest {

    private val trava get() = contexto.getSystemService(KeyguardManager::class.java)
    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alias = "teste_${System.nanoTime()}"
    private val arquivo = File(contexto.cacheDir, "$alias.preferences_pb")
    private val cofre = CofreDeChaves(
        trava,
        CofreDeChaves.armazemDoCofre(arquivo, escopo),
        JANELA,
        alias,
        temStrongBox = true,
    )

    @After
    fun limpar() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        escopo.cancel()
        arquivo.delete()
    }

    @Test
    fun caso1_com_strongbox_o_segredo_volta_em_claro() = runBlocking {
        assumeTrue(
            "aparelho sem StrongBox; este caso roda num que o tenha",
            contexto.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
        )
        assumeTrue(
            "aparelho sem trava de tela; defina uma e desbloqueie antes de rodar",
            contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure,
        )

        assertEquals(
            "desbloqueie o aparelho até ${JANELA.inWholeMinutes} minutos antes de rodar",
            Guarda.Guardada(NivelDoCofre.STRONGBOX),
            cofre.guardar(Provedor.CLAUDE, "valor-de-teste-do-cofre"),
        )
        assertEquals(
            "valor-de-teste-do-cofre",
            (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor,
        )
    }

    private companion object {
        val JANELA = 5.minutes
    }
}
