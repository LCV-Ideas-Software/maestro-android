package dev.lcv.maestro.seguranca

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.util.Date
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
 * **No emulador da CI, pular é falha.** A CI passa o argumento
 * `emuladorDaCi=true` ao executor de testes; com ele, um emulador que
 * aparecesse com trava ou com StrongBox reprovaria a verificação, em vez de
 * pular todos os casos e passar sem ter provado nada.
 *
 * Nenhum teste usa valor com forma de chave real.
 */
@RunWith(AndroidJUnit4::class)
class CofreDeChavesTest {

    private val instrumentacao = InstrumentationRegistry.getInstrumentation()
    private val trava get() = contexto.getSystemService(KeyguardManager::class.java)
    private val contexto = instrumentacao.targetContext
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val alias = "teste_${System.nanoTime()}"
    private val arquivo = File(contexto.cacheDir, "$alias.preferences_pb")
    private val armazem = CofreDeChaves.armazemDoCofre(arquivo, escopo)
    private val cofre = CofreDeChaves(trava, armazem, JANELA, alias, temStrongBox())

    private val chave = "valor-de-teste-do-cofre"

    private fun shell(comando: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentacao.uiAutomation.executeShellCommand(comando))
            .bufferedReader().use { it.readText().trim() }

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

    private val noEmuladorDaCi = InstrumentationRegistry.getArguments().getString("emuladorDaCi") == "true"

    /** Pula num aparelho qualquer; reprova no emulador da CI. */
    private fun exigirQueNao(motivo: String, condicao: Boolean) {
        if (noEmuladorDaCi) assertFalse(motivo, condicao) else assumeFalse(motivo, condicao)
    }

    @Before
    fun definirPin() {
        exigirQueNao(
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
        // No emulador a ausência do hardware é real, não encenada. O cofre
        // aqui tenta o StrongBox mesmo assim, para exercitar a volta ao
        // caminho sem ele quando o chip recusa a chave.
        exigirQueNao("aparelho com StrongBox; este caso roda num que não o tenha", temStrongBox())
        val tentaStrongBox = CofreDeChaves(trava, armazem, JANELA, alias, temStrongBox = true)
        autenticar()

        val guarda = tentaStrongBox.guardar(Provedor.CLAUDE, chave)

        val nivel = (guarda as Guarda.Guardada).nivel
        assertNotEquals(NivelDoCofre.STRONGBOX, nivel)
        assertEquals(nivel, tentaStrongBox.nivel())
        assertEquals(chave, (tentaStrongBox.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
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
        assertEquals(true, cofre.configurada(Provedor.CLAUDE))

        shell("locksettings clear --old $PIN")

        // "Configurada" primeiro, como a tela de configurações perguntaria,
        // antes de qualquer leitura ter retirado o registro órfão.
        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
    }

    @Test
    fun chave_nova_do_keystore_apaga_os_cifrados_que_ela_nao_abre() = runBlocking {
        // Removida a trava, a chave some e os cifrados ficam órfãos. Guardar
        // de novo cria outra chave com o mesmo alias; os cifrados antigos não
        // abrem com ela e não podem continuar aparecendo como configurados.
        guardar(Provedor.CLAUDE)
        shell("locksettings clear --old $PIN")
        assertTrue(shell("locksettings set-pin $PIN").contains(PIN))

        guardar(Provedor.CODEX, "valor-novo")

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertEquals("valor-novo", (cofre.chaveDe(Provedor.CODEX) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_substituida_sem_autenticacao_ja_apaga_os_cifrados_orfaos() = runBlocking {
        // Removida a trava, a chave some. Se a guarda seguinte criar a chave
        // nova e parar por falta de autenticação, os cifrados antigos não
        // podem sobreviver para a guarda depois dela, que já encontra o alias.
        guardar(Provedor.CLAUDE)
        shell("locksettings clear --old $PIN")
        assertTrue(shell("locksettings set-pin $PIN").contains(PIN))
        esperarAJanelaVencer()

        assertEquals(Guarda.ExigeAutenticacao, cofre.guardar(Provedor.CODEX, "valor-novo"))

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
    }

    @Test
    fun janela_vencida_com_a_chave_de_pe_nao_apaga_nada() = runBlocking {
        // O par do teste acima: sem chave perdida, a falta de autenticação só
        // adia a guarda, e o que já estava guardado continua lá.
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()

        assertEquals(Guarda.ExigeAutenticacao, cofre.guardar(Provedor.CODEX, "valor-novo"))

        assertEquals(true, cofre.configurada(Provedor.CLAUDE))
        autenticar()
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun o_cofre_grava_na_pasta_que_o_android_nunca_leva_para_o_backup() {
        // `noBackupFilesDir` fica fora do backup e da transferência entre
        // aparelhos sempre, com o temporário do DataStore junto.
        val arquivo = CofreDeChaves.arquivoDoCofre(contexto).canonicalFile

        assertTrue(arquivo.path, arquivo.path.startsWith(contexto.noBackupFilesDir.canonicalPath + File.separator))
    }

    @Test
    fun o_cofre_do_processo_e_um_so_e_grava_na_pasta_dele() = runBlocking {
        // O DataStore recusa duas instâncias sobre o mesmo arquivo.
        val primeiro = CofreDeChaves.criar(contexto, JANELA_DO_PROCESSO)

        assertSame(primeiro, CofreDeChaves.criar(contexto, JANELA_DO_PROCESSO))
        assertThrows(IllegalArgumentException::class.java) {
            CofreDeChaves.criar(contexto, JANELA_DO_PROCESSO + 1.seconds)
        }

        // Grava e lê de verdade no arquivo sob `noBackupFilesDir`, com a pasta
        // criada pelo próprio cofre.
        try {
            autenticar()
            assertTrue(primeiro.guardar(Provedor.CLAUDE, chave) is Guarda.Guardada)
            assertTrue(CofreDeChaves.arquivoDoCofre(contexto).isFile)
            assertEquals(chave, (primeiro.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
            assertTrue(primeiro.apagar(Provedor.CLAUDE))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("maestro_chaves_de_api")
        }
    }

    @Test
    fun guardas_simultaneas_no_primeiro_uso_geram_uma_chave_so() = runBlocking {
        // Sem exclusão mútua, cada guarda veria o alias ausente e geraria a
        // própria chave, e a última apagaria a das outras.
        autenticar()
        val provedores = Provedor.entries
        provedores.map { provedor ->
            async(Dispatchers.IO) { cofre.guardar(provedor, "valor-${provedor.agente}") }
        }.awaitAll().forEach { assertTrue("$it", it is Guarda.Guardada) }

        autenticar()
        for (provedor in provedores) {
            assertEquals(
                provedor.agente,
                "valor-${provedor.agente}",
                (cofre.chaveDe(provedor) as? LeituraDaChave.Presente)?.valor,
            )
        }
    }

    @Test
    fun janela_que_nao_cabe_em_segundos_inteiros_e_recusada() {
        // O Keystore recebe a janela em segundos, num `Int`. Infinita ou acima
        // do teto, a conversão viraria número negativo em silêncio.
        for (janela in listOf(Duration.INFINITE, (Int.MAX_VALUE.toLong() + 1).seconds, 0.seconds, 1.5.seconds)) {
            assertThrows("$janela", IllegalArgumentException::class.java) {
                CofreDeChaves(trava, armazem, janela, alias, temStrongBox = false)
            }
        }
    }

    @Test
    fun sem_trava_de_tela_nada_e_guardado() = runBlocking {
        shell("locksettings clear --old $PIN")

        assertEquals(Guarda.SemTravaDeTela, cofre.guardar(Provedor.CLAUDE, chave))
        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
    }

    @Test
    fun o_cifrado_de_um_provedor_nao_abre_como_o_de_outro() = runBlocking {
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        armazem.edit { it[byteArrayPreferencesKey("chave_codex")] = doClaude }

        // O cabeçalho diz de quem é o registro: não aparece como configurado
        // nem antes de alguém tentar lê-lo.
        assertEquals(false, cofre.configurada(Provedor.CODEX))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CODEX))
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun cabecalho_trocado_nao_basta_o_gcm_recusa_o_conteudo_de_outro_provedor() = runBlocking {
        // Com o cabeçalho do Codex e o conteúdo do Claude, o nome do provedor
        // como dado autenticado do GCM é o que recusa. A única cópia do
        // cifrado do Claude sai do nome do Codex, mas não se perde: fica
        // afastada, e volta a abrir no lugar certo.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val cabecalhoDoClaude = 4 + 1 + "claude".length
        val cabecalhoDoCodex = "MAE1".toByteArray() + byteArrayOf(5) + "codex".toByteArray()
        val trocado = cabecalhoDoCodex + doClaude.copyOfRange(cabecalhoDoClaude, doClaude.size)
        armazem.edit {
            it.remove(byteArrayPreferencesKey("chave_claude"))
            it[byteArrayPreferencesKey("chave_codex")] = trocado
        }

        // "Configurada" abre o conteúdo, e não só confere a chave do Keystore.
        assertEquals(false, cofre.configurada(Provedor.CODEX))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CODEX))

        val afastado = armazem.data.first().asMap().entries
            .single { it.key.name.startsWith("afastado_codex_") }.value as ByteArray
        assertTrue(trocado.contentEquals(afastado))
        armazem.edit {
            it[byteArrayPreferencesKey("chave_claude")] =
                doClaude.copyOfRange(0, cabecalhoDoClaude) + afastado.copyOfRange(cabecalhoDoCodex.size, afastado.size)
        }
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }


    @Test
    fun byte_trocado_no_cabecalho_nao_apaga_um_cifrado_que_ainda_abre() = runBlocking {
        // O cabeçalho não é autenticado: um byte trocado nele não prova que o
        // cifrado se perdeu. O registro não conta como deste provedor, mas
        // continua lá, e volta a abrir com o cabeçalho certo.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val trocado = doClaude.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = trocado }

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertTrue(trocado.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_claude")]))

        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = doClaude }
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun etiqueta_adulterada_que_o_disco_nao_deixa_apagar_nao_volta_como_configurada() = runBlocking {
        // A etiqueta prova a perda, mas o disco recusa apagar. Vencida a
        // janela, o Keystore já não diria nada; o cofre lembra do registro
        // exato e não o mostra como configurado.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val adulterado = doClaude.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = adulterado }
        val semGravacao = CofreDeChaves(trava, ArmazemQueFalha(armazem, falhaAoGravar = true), JANELA, alias, temStrongBox())

        assertEquals(LeituraDaChave.Ausente, semGravacao.chaveDe(Provedor.CLAUDE))
        assertTrue(adulterado.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_claude")]))
        esperarAJanelaVencer()

        assertEquals(false, semGravacao.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, semGravacao.chaveDe(Provedor.CLAUDE))
    }

    @Test
    fun etiqueta_adulterada_nao_aparece_como_configurada_com_a_autenticacao_em_dia() = runBlocking {
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val adulterado = doClaude.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = adulterado }

        // A primeira leitura que prova a perda já tira o registro do nome do
        // provedor, e os bytes ficam afastados.
        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertNull(armazem.data.first()[byteArrayPreferencesKey("chave_claude")])
        assertTrue(
            armazem.data.first().asMap().entries.any {
                it.key.name.startsWith("afastado_claude_") && adulterado.contentEquals(it.value as ByteArray)
            },
        )
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))

        // O registro que não abre sai do cofre: vencida a janela, quando já
        // não daria para abri-lo, ele não volta a aparecer como configurado.
        esperarAJanelaVencer()
        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
    }

    @Test
    fun registro_curto_de_outro_provedor_nao_e_apagado() = runBlocking {
        // O registro do Grok com uma chave de um caractere tem 38 bytes; o
        // mínimo de um registro da Perplexity, com o nome mais longo, é 44.
        // Sob o nome da Perplexity, o cabeçalho diz que ele não é dela, e isso
        // vem antes do tamanho: ele fica, e volta a abrir no lugar certo.
        guardar(Provedor.GROK, "x")
        val doGrok = armazem.data.first()[byteArrayPreferencesKey("chave_grok")]!!
        armazem.edit {
            it.remove(byteArrayPreferencesKey("chave_grok"))
            it[byteArrayPreferencesKey("chave_perplexity")] = doGrok
        }

        assertEquals(false, cofre.configurada(Provedor.PERPLEXITY))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.PERPLEXITY))
        assertTrue(doGrok.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_perplexity")]))

        armazem.edit { it[byteArrayPreferencesKey("chave_grok")] = doGrok }
        autenticar()
        assertEquals("x", (cofre.chaveDe(Provedor.GROK) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun leitura_cancelada_no_meio_do_apagamento_nao_perde_a_prova() = runBlocking {
        // A etiqueta prova a perda, e a leitura é cancelada enquanto o disco
        // apaga. O registro fica, mas a prova não se perde: vencida a janela,
        // ele não volta como configurado.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val adulterado = doClaude.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = adulterado }
        val travado = CofreDeChaves(trava, ArmazemQueTravaNaPrimeiraGravacao(armazem), JANELA, alias, temStrongBox())

        assertNull(withTimeoutOrNull(2.seconds) { travado.chaveDe(Provedor.CLAUDE) })
        assertTrue(adulterado.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_claude")]))
        esperarAJanelaVencer()

        assertEquals(false, travado.configurada(Provedor.CLAUDE))
    }

    @Test
    fun chave_que_nao_e_aes_no_nome_do_cofre_conta_como_perdida_e_e_trocada() = runBlocking {
        // O cofre só gera AES. Outra chave sob o nome dele não abre nada e,
        // tratada como a dele, faria toda guarda falhar para sempre.
        guardar(Provedor.CLAUDE)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        }.generateKey()

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        guardar(Provedor.CODEX, "valor-novo")
        autenticar()
        assertEquals("valor-novo", (cofre.chaveDe(Provedor.CODEX) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_com_caractere_que_nao_vai_num_cabecalho_e_recusada_e_a_anterior_fica() = runBlocking {
        // Metade de um par substituto viraria "?" no UTF-8, e a chave gravada
        // seria outra; espaço no meio não iria num cabeçalho HTTP.
        guardar(Provedor.CLAUDE)
        autenticar()

        assertEquals(Guarda.ChaveInvalida, cofre.guardar(Provedor.CLAUDE, "abc\uD800def"))
        assertEquals(Guarda.ChaveInvalida, cofre.guardar(Provedor.CLAUDE, "abc def"))
        assertEquals(Guarda.ChaveInvalida, cofre.guardar(Provedor.CLAUDE, "chave-com-acento-é"))
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_aes_sem_autenticacao_no_nome_do_cofre_nao_e_usada_e_e_trocada() = runBlocking {
        // Uma chave sem autenticação decifraria sem o usuário se autenticar.
        // Ela não é a do cofre: a guarda troca por uma que exige autenticação.
        chaveDoCofreCom { setUserAuthenticationRequired(false) }

        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()

        assertEquals(LeituraDaChave.ExigeAutenticacao, cofre.chaveDe(Provedor.CLAUDE))
        autenticar()
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_aes_so_de_cifrar_no_nome_do_cofre_e_trocada() = runBlocking {
        // Com ela a guarda gravaria um cifrado que o cofre não decifra.
        chaveDoCofreCom(KeyProperties.PURPOSE_ENCRYPT) {}

        guardar(Provedor.CLAUDE)
        autenticar()

        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_aes_com_autenticacao_a_cada_uso_no_nome_do_cofre_e_trocada() = runBlocking {
        // Com autenticação a cada uso, o Keystore só decifra por um prompt
        // biométrico ligado à operação, que o cofre não usa: ele nunca
        // abriria nada. A guarda troca por uma chave com janela.
        chaveDoCofreCom {
            setUserAuthenticationParameters(0, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        }

        guardar(Provedor.CLAUDE)
        autenticar()

        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun chave_importada_no_nome_do_cofre_e_trocada() = runBlocking {
        // Com todos os outros atributos iguais aos da chave do cofre, uma chave
        // importada tem material conhecido fora do Keystore: quem o conhece
        // decifra sem autenticação. Ela não é a do cofre e é trocada.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply {
            deleteEntry(alias)
            setEntry(
                alias,
                KeyStore.SecretKeyEntry(SecretKeySpec(ByteArray(32) { it.toByte() }, KeyProperties.KEY_ALGORITHM_AES)),
                KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        JANELA.inWholeSeconds.toInt(),
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                    .setInvalidatedByBiometricEnrollment(false)
                    .build(),
            )
        }
        val antes = criacaoDaChave()
        Thread.sleep(1_100)

        guardar(Provedor.CLAUDE)
        autenticar()

        assertNotEquals("a chave importada não foi trocada", antes, criacaoDaChave())
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun texto_e_bytes_nao_convivem_no_mesmo_nome_a_guarda_substitui_o_texto() = runBlocking {
        // A chave de preferência do DataStore é igual a outra pelo nome, não
        // pelo tipo: gravar os bytes substitui o texto que estava lá.
        armazem.edit { it[stringPreferencesKey("chave_claude")] = "texto no lugar de bytes" }

        guardar(Provedor.CLAUDE)
        autenticar()

        assertEquals(1, armazem.data.first().asMap().keys.count { it.name == "chave_claude" })
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun apagar_leva_tambem_os_registros_afastados_do_provedor() = runBlocking {
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val adulterado = doClaude.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = adulterado }
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertEquals(1, armazem.data.first().asMap().keys.count { it.name.startsWith("afastado_claude_") })

        assertTrue(cofre.apagar(Provedor.CLAUDE))

        assertEquals(0, armazem.data.first().asMap().keys.count { it.name.startsWith("afastado_claude_") })
    }

    @Test
    fun registro_trocado_por_uma_guarda_tardia_nao_e_apagado_no_lugar_do_lido() = runBlocking {
        // A leitura vê um registro que não abre e decide afastá-lo; antes de
        // a edição rodar, uma guarda que terminou de gravar depois já pôs
        // outro no lugar. Só sai o registro que foi lido.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        val adulterado = doClaude.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        armazem.edit { it[byteArrayPreferencesKey("chave_claude")] = adulterado }
        val comGuardaTardia = CofreDeChaves(
            trava,
            ArmazemQueGravaAntesDaPrimeiraEdicao(armazem, "chave_claude", doClaude),
            JANELA,
            alias,
            temStrongBox(),
        )

        assertEquals(LeituraDaChave.Ausente, comGuardaTardia.chaveDe(Provedor.CLAUDE))

        assertTrue(doClaude.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_claude")]))
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun registro_de_tipo_errado_trocado_por_uma_guarda_tardia_nao_e_apagado() = runBlocking {
        // O mesmo, no caminho do valor que não pode ser cifrado.
        guardar(Provedor.CLAUDE)
        autenticar()
        val doClaude = armazem.data.first()[byteArrayPreferencesKey("chave_claude")]!!
        armazem.edit {
            it.remove(byteArrayPreferencesKey("chave_claude"))
            it[stringPreferencesKey("chave_claude")] = "texto no lugar de bytes"
        }
        val comGuardaTardia = CofreDeChaves(
            trava,
            ArmazemQueGravaAntesDaPrimeiraEdicao(armazem, "chave_claude", doClaude),
            JANELA,
            alias,
            temStrongBox(),
        )

        assertEquals(LeituraDaChave.Ausente, comGuardaTardia.chaveDe(Provedor.CLAUDE))

        assertTrue(doClaude.contentEquals(armazem.data.first()[byteArrayPreferencesKey("chave_claude")]))
    }

    @Test
    fun chave_do_keystore_que_some_leva_todos_os_registros_na_primeira_leitura() = runBlocking {
        // A chave é uma só para os seis: descobrir que ela sumiu lendo o
        // Claude já retira o registro do Gemini, que também não abre mais.
        guardar(Provedor.CLAUDE)
        guardar(Provedor.GEMINI, "valor-do-gemini")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))

        assertNull(armazem.data.first()[byteArrayPreferencesKey("chave_gemini")])
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.GEMINI))
    }

    /**
     * Uma chave com a forma exata da do cofre, a não ser pelos [propositos] e
     * pela [variacao]: cada teste muda um atributo só, para que só ele a
     * distinga da do cofre.
     */
    private fun chaveDoCofreCom(
        propositos: Int = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        variacao: KeyGenParameterSpec.Builder.() -> Unit,
    ) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, propositos)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(
                        JANELA.inWholeSeconds.toInt(),
                        KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                    )
                    .setInvalidatedByBiometricEnrollment(false)
                    .apply(variacao)
                    .build(),
            )
        }.generateKey()
    }

    private fun criacaoDaChave() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getCreationDate(alias)

    @Test
    fun chave_com_a_forma_exata_do_cofre_e_usada_e_as_de_outra_forma_sao_trocadas() = runBlocking {
        // Controle: a forma exata do cofre não é trocada.
        chaveDoCofreCom {}
        val exata = criacaoDaChave()
        Thread.sleep(1_100)
        guardar(Provedor.CLAUDE)
        assertEquals("a forma exata foi trocada", exata, criacaoDaChave())

        // Cada atributo diferente faz a guarda trocar a chave, e a nova abre.
        // A invalidação por biometria nova e a validade "no corpo" ficam de
        // fora: sem biometria cadastrada nem sensor de corpo, o emulador
        // informa `false` para as duas, e a chave se comporta como a do cofre.
        // Presença confirmada e confirmação do usuário exigem hardware que o
        // emulador não tem.
        val variacoes: List<Pair<String, KeyGenParameterSpec.Builder.() -> Unit>> = listOf(
            "validade de uso vencida" to { setKeyValidityForConsumptionEnd(Date(System.currentTimeMillis() - 86_400_000)) },
            "fim de validade para cifrar" to { setKeyValidityForOriginationEnd(Date(System.currentTimeMillis() + 86_400_000)) },
            "início de validade" to { setKeyValidityStart(Date(System.currentTimeMillis() - 86_400_000)) },
            "limite de usos" to { setMaxUsageCount(5) },
            "só com o PIN" to {
                setUserAuthenticationParameters(JANELA.inWholeSeconds.toInt(), KeyProperties.AUTH_DEVICE_CREDENTIAL)
            },
        )
        for ((nome, variacao) in variacoes) {
            chaveDoCofreCom(variacao = variacao)
            val antes = criacaoDaChave()
            Thread.sleep(1_100)
            guardar(Provedor.CLAUDE)
            autenticar()
            assertNotEquals("$nome: a chave não foi trocada", antes, criacaoDaChave())
            assertEquals(nome, chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
        }
    }

    @Test
    fun valor_de_tipo_errado_e_ausente_sem_estourar_e_nao_toca_os_outros() = runBlocking {
        guardar(Provedor.GEMINI, "valor-do-gemini")
        autenticar()
        armazem.edit { it[stringPreferencesKey("chave_claude")] = "texto no lugar de bytes" }

        assertEquals(false, cofre.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CLAUDE))
        assertEquals("valor-do-gemini", (cofre.chaveDe(Provedor.GEMINI) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun disco_que_nao_le_deixa_a_chave_indisponivel_e_a_configuracao_desconhecida() = runBlocking {
        guardar(Provedor.CLAUDE)
        autenticar()
        val semLeitura = CofreDeChaves(trava, ArmazemQueFalha(armazem, falhaAoLer = true), JANELA, alias, temStrongBox())

        // Falha passageira do disco não é chave perdida, e também não diz se
        // há chave: nem pede a chave de novo, nem responde sim ou não — tanto
        // para o provedor que tem registro quanto para o que não tem.
        assertEquals(LeituraDaChave.Indisponivel, semLeitura.chaveDe(Provedor.CLAUDE))
        assertNull(semLeitura.configurada(Provedor.CLAUDE))
        assertEquals(LeituraDaChave.Indisponivel, semLeitura.chaveDe(Provedor.GEMINI))
        assertNull(semLeitura.configurada(Provedor.GEMINI))
    }

    @Test
    fun disco_que_nao_grava_da_resultado_tipado_e_nao_apaga_nada() = runBlocking {
        guardar(Provedor.CLAUDE)
        val semGravacao = CofreDeChaves(trava, ArmazemQueFalha(armazem, falhaAoGravar = true), JANELA, alias, temStrongBox())
        autenticar()

        assertEquals(Guarda.Falhou, semGravacao.guardar(Provedor.GEMINI, "valor-novo"))
        assertFalse(semGravacao.apagar(Provedor.CLAUDE))
        assertEquals(chave, (cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
    }

    @Test
    fun bytes_quaisquer_nao_aparecem_como_configurados_nem_com_a_janela_vencida() = runBlocking {
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()
        armazem.edit { it[byteArrayPreferencesKey("chave_codex")] = ByteArray(40) { (it * 7).toByte() } }

        assertEquals(false, cofre.configurada(Provedor.CODEX))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CODEX))
    }

    @Test
    fun registro_de_tamanho_impossivel_e_ausente_mesmo_com_a_janela_vencida() = runBlocking {
        // Menos que IV + etiqueta + um byte não é cifrado, nem com o
        // cabeçalho certo, e sai do cofre. Com a janela vencida, chegar ao
        // Keystore o faria parecer chave intacta.
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()
        val cabecalhoDoCodex = "MAE1".toByteArray() + byteArrayOf(5) + "codex".toByteArray()
        armazem.edit { it[byteArrayPreferencesKey("chave_codex")] = cabecalhoDoCodex + ByteArray(20) }

        assertEquals(false, cofre.configurada(Provedor.CODEX))
        assertEquals(LeituraDaChave.Ausente, cofre.chaveDe(Provedor.CODEX))
        assertNull(armazem.data.first()[byteArrayPreferencesKey("chave_codex")])
        // Sai do nome do provedor, mas os bytes ficam, afastados: o cabeçalho
        // não autenticado não prova que eles se perderam.
        assertEquals(1, armazem.data.first().asMap().keys.count { it.name.startsWith("afastado_codex_") })
    }

    @Test
    fun janela_vencida_nao_tira_a_configuracao() = runBlocking {
        // A conferência abre a chave do Keystore; janela vencida é chave
        // intacta, e não chave perdida.
        guardar(Provedor.CLAUDE)
        esperarAJanelaVencer()

        assertEquals(true, cofre.configurada(Provedor.CLAUDE))
    }

    /**
     * Um cofre sobre [arquivo] com escopo próprio, para que se possa fechá-lo
     * e abrir outro sobre o mesmo arquivo — o DataStore recusa dois ao mesmo
     * tempo.
     */
    private class CofreProprio(
        teste: CofreDeChavesTest,
        arquivo: File,
        /** `null` usa a quarentena de verdade do cofre. */
        quarentena: ((File) -> Unit)? = null,
    ) {
        val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val cofre = CofreDeChaves(
            teste.trava,
            if (quarentena == null) {
                CofreDeChaves.armazemDoCofre(arquivo, escopo)
            } else {
                CofreDeChaves.armazemDoCofre(arquivo, escopo, quarentena)
            },
            JANELA,
            teste.alias,
            teste.temStrongBox(),
        )

        suspend fun fechar() = escopo.coroutineContext.job.cancelAndJoin()
    }

    /** Bytes que tornam ilegível um arquivo de preferências: um campo que promete 127 bytes e acaba. */
    private val estrago = byteArrayOf(0x0a, 0x7f)

    @Test
    fun arquivo_corrompido_vai_para_uma_quarentena_propria_com_o_registro_recuperavel() = runBlocking {
        val pasta = File(contexto.cacheDir, "corrompido_$alias").apply { mkdirs() }
        val arquivo = File(pasta, "cofre.preferences_pb")
        try {
            // Um registro de verdade, e o arquivo estragado depois dele.
            val primeiro = CofreProprio(this@CofreDeChavesTest, arquivo)
            autenticar()
            assertTrue(primeiro.cofre.guardar(Provedor.CLAUDE, chave) is Guarda.Guardada)
            primeiro.fechar()
            val integro = arquivo.readBytes()
            arquivo.writeBytes(integro + estrago)
            val estragado1 = arquivo.readBytes()

            val segundo = CofreProprio(this@CofreDeChavesTest, arquivo)
            assertEquals(LeituraDaChave.Ausente, segundo.cofre.chaveDe(Provedor.CLAUDE))
            assertEquals(false, segundo.cofre.configurada(Provedor.CLAUDE))
            segundo.fechar()
            assertEquals(1, CofreDeChaves.quarentenasDe(arquivo).size)
            assertTrue(estragado1.contentEquals(CofreDeChaves.quarentenasDe(arquivo)[0].readBytes()))

            // Uma segunda corrupção ganha outra quarentena; a primeira fica.
            arquivo.writeBytes(arquivo.readBytes() + estrago)
            val terceiro = CofreProprio(this@CofreDeChavesTest, arquivo)
            assertEquals(LeituraDaChave.Ausente, terceiro.cofre.chaveDe(Provedor.CLAUDE))
            terceiro.fechar()
            val quarentenas = CofreDeChaves.quarentenasDe(arquivo)
            assertEquals(2, quarentenas.size)
            assertTrue(estragado1.contentEquals(quarentenas[0].readBytes()))

            // O registro guardado na primeira quarentena ainda abre.
            val recuperado = File(pasta, "recuperado.preferences_pb")
            recuperado.writeBytes(quarentenas[0].readBytes().copyOf(integro.size))
            val quarto = CofreProprio(this@CofreDeChavesTest, recuperado)
            autenticar()
            assertEquals(chave, (quarto.cofre.chaveDe(Provedor.CLAUDE) as LeituraDaChave.Presente).valor)
            quarto.fechar()
        } finally {
            pasta.deleteRecursively()
        }
    }

    @Test
    fun quarentena_que_falha_nao_troca_o_arquivo() = runBlocking {
        val pasta = File(contexto.cacheDir, "sem_quarentena_$alias").apply { mkdirs() }
        val arquivo = File(pasta, "cofre.preferences_pb")
        try {
            val primeiro = CofreProprio(this@CofreDeChavesTest, arquivo)
            autenticar()
            assertTrue(primeiro.cofre.guardar(Provedor.CLAUDE, chave) is Guarda.Guardada)
            primeiro.fechar()
            arquivo.writeBytes(arquivo.readBytes() + estrago)
            val estragado = arquivo.readBytes()

            // Só a cópia falha; trocar o arquivo continuaria possível. O
            // arquivo fica como está, e a leitura fica indisponível, sem sim
            // nem não.
            val semCopia = CofreProprio(this@CofreDeChavesTest, arquivo) { throw IOException("disco cheio") }
            assertEquals(LeituraDaChave.Indisponivel, semCopia.cofre.chaveDe(Provedor.CLAUDE))
            assertNull(semCopia.cofre.configurada(Provedor.CLAUDE))
            semCopia.fechar()
            assertTrue(estragado.contentEquals(arquivo.readBytes()))
            assertTrue(CofreDeChaves.quarentenasDe(arquivo).isEmpty())

            // Com a cópia de volta, a quarentena é feita e o arquivo, trocado.
            val comCopia = CofreProprio(this@CofreDeChavesTest, arquivo)
            assertEquals(LeituraDaChave.Ausente, comCopia.cofre.chaveDe(Provedor.CLAUDE))
            comCopia.fechar()
            assertTrue(estragado.contentEquals(CofreDeChaves.quarentenasDe(arquivo).single().readBytes()))
        } finally {
            pasta.deleteRecursively()
        }
    }

    @Test
    fun cada_provedor_tem_a_propria_chave_e_apagar_uma_nao_toca_as_outras() = runBlocking {
        guardar(Provedor.CLAUDE, "valor-um")
        guardar(Provedor.GEMINI, "valor-dois")
        autenticar()

        assertTrue(cofre.apagar(Provedor.CLAUDE))

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
        // Falha indeterminada do Keystore não dá a chave por perdida.
        assertEquals(LeituraDaChave.Indisponivel, traduzir(ProviderException()))
        // Embrulhadas noutra exceção, a causa é a mesma.
        assertEquals(
            LeituraDaChave.ExigeAutenticacao,
            traduzir(ProviderException(UserNotAuthenticatedException())),
        )
        assertEquals(
            LeituraDaChave.Irrecuperavel,
            traduzir(KeyStoreException("embrulhada", KeyPermanentlyInvalidatedException())),
        )
    }

    /**
     * Antes da primeira edição, grava [valor] em [nome], como uma guarda que
     * terminou de gravar depois de a leitura já ter visto o registro anterior.
     */
    private class ArmazemQueGravaAntesDaPrimeiraEdicao(
        private val base: DataStore<Preferences>,
        private val nome: String,
        private val valor: ByteArray,
    ) : DataStore<Preferences> {
        private var primeira = true

        override val data: Flow<Preferences> = base.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (primeira) {
                primeira = false
                base.edit { it[byteArrayPreferencesKey(nome)] = valor }
            }
            return base.updateData(transform)
        }
    }

    /** DataStore que falha de propósito, para provar os caminhos de falha do disco. */
    private class ArmazemQueFalha(
        private val base: DataStore<Preferences>,
        private val falhaAoLer: Boolean = false,
        private val falhaAoGravar: Boolean = false,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> =
            if (falhaAoLer) flow { throw IOException("disco que não lê") } else base.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            if (falhaAoGravar) throw IOException("disco que não grava") else base.updateData(transform)
    }

    /** Trava a primeira gravação até ser cancelada; as seguintes falham como disco que não grava. */
    private class ArmazemQueTravaNaPrimeiraGravacao(private val base: DataStore<Preferences>) : DataStore<Preferences> {
        private var primeira = true

        override val data: Flow<Preferences> = base.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (primeira) {
                primeira = false
                awaitCancellation()
            }
            throw IOException("disco que não grava")
        }
    }

    private companion object {
        const val PIN = "1234"
        val JANELA = 3.seconds
        val JANELA_DO_PROCESSO = 7.seconds
    }
}
