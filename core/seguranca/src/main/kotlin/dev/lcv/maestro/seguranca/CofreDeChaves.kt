package dev.lcv.maestro.seguranca

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.lcv.maestro.provedores.FonteDeChave
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Provedor
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * As chaves de API dos seis provedores, guardadas só neste aparelho
 * (especificação, seção 6).
 *
 * Cada chave de API é cifrada em AES-256-GCM por uma chave gerada **dentro**
 * do Android Keystore, que não é exportável; o cifrado fica num DataStore
 * comum do aplicativo. A chave de API em claro só existe em memória, durante
 * a montagem da chamada. Processo comprometido consegue usar a chave do
 * Keystore, não levá-la.
 *
 * As decisões do operador de 21/09/2026 (seção 6.2), gravadas na chave quando
 * ela nasce, porque depois não mudam:
 * - **StrongBox, quando o aparelho o tem.** Sem o hardware, a geração cai no
 *   caminho sem ele, e [nivel] diz qual está em uso — lido do próprio
 *   Keystore (`KeyInfo`), e não de um registro nosso que pudesse divergir;
 * - **autenticação do usuário por tempo, não por operação**, com uma [janela]
 *   fixa. A janela vale a partir da geração da chave: depois dela, outra
 *   janela passada ao cofre é ignorada pelo Keystore. Trocá-la exige gerar
 *   chave nova, decifrando com a antiga e recifrando com a nova na mesma
 *   operação autenticada (seção 6.2, item 3), o que este módulo não faz;
 * - cadastrar biometria nova não invalida a chave.
 *
 * O nome do provedor entra no GCM como dado autenticado: o cifrado de um
 * provedor não abre como o de outro.
 */
public class CofreDeChaves internal constructor(
    private val contexto: Context,
    private val armazem: DataStore<Preferences>,
    private val janela: Duration,
    private val alias: String,
) : FonteDeChave {

    init {
        require(janela >= 1.seconds) { "a janela de autenticação tem de ser de pelo menos 1 s" }
    }

    /**
     * Cifra e guarda a chave de API do [provedor], trocando a que houver. A
     * cifra também exige autenticação recente: a chave do Keystore é uma só,
     * presa à janela.
     */
    public suspend fun guardar(provedor: Provedor, chave: String): Guarda {
        require(chave.isNotBlank()) { "chave de API vazia" }
        if (!contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure) return Guarda.SemTravaDeTela
        val dados = withContext(Dispatchers.IO) {
            val cifra = Cipher.getInstance(TRANSFORMACAO)
            try {
                cifra.init(Cipher.ENCRYPT_MODE, chaveDoKeystore() ?: gerar())
            } catch (_: UserNotAuthenticatedException) {
                return@withContext null
            } catch (_: KeyPermanentlyInvalidatedException) {
                // O que foi cifrado com a chave anterior já não volta; a nova
                // passa a valer para o que se guardar daqui em diante.
                keystore().deleteEntry(alias)
                cifra.init(Cipher.ENCRYPT_MODE, gerar())
            }
            cifra.updateAAD(provedor.agente.toByteArray(Charsets.UTF_8))
            cifra.iv + cifra.doFinal(chave.trim().toByteArray(Charsets.UTF_8))
        } ?: return Guarda.ExigeAutenticacao
        armazem.edit { it[chaveDoArmazem(provedor)] = dados }
        return Guarda.Guardada(nivel() ?: NivelDoCofre.DESCONHECIDO)
    }

    /** Apaga a chave de API do [provedor]. */
    public suspend fun apagar(provedor: Provedor) {
        armazem.edit { it.remove(chaveDoArmazem(provedor)) }
    }

    /**
     * Se há chave de API guardada e decifrável em princípio. Não decifra, e
     * por isso não exige autenticação: é o que a tela de configurações mostra
     * — "configurada" ou "não configurada", nunca o valor.
     */
    public suspend fun configurada(provedor: Provedor): Boolean =
        armazem.data.first()[chaveDoArmazem(provedor)] != null &&
            withContext(Dispatchers.IO) { keystore().containsAlias(alias) }

    /** Onde a chave do Keystore vive; `null` enquanto ela não foi gerada. */
    public suspend fun nivel(): NivelDoCofre? = withContext(Dispatchers.IO) {
        val chave = chaveDoKeystore() ?: return@withContext null
        val info = SecretKeyFactory.getInstance(chave.algorithm, KEYSTORE)
            .getKeySpec(chave, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> NivelDoCofre.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> NivelDoCofre.AMBIENTE_SEGURO
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> NivelDoCofre.SOFTWARE
            else -> NivelDoCofre.DESCONHECIDO
        }
    }

    override suspend fun chaveDe(provedor: Provedor): LeituraDaChave {
        val dados = armazem.data.first()[chaveDoArmazem(provedor)] ?: return LeituraDaChave.Ausente
        if (dados.size <= TAMANHO_DO_IV) return LeituraDaChave.Ausente
        return withContext(Dispatchers.IO) {
            try {
                // Cifrado sem a chave que o cifrou — o caso do backup
                // restaurado em outro aparelho, ou da trava de tela removida,
                // que apaga a chave do Keystore — é "não há chave" (seção
                // 4.2). A busca da chave fica dentro do `try`: uma entrada
                // corrompida no Keystore também é leitura tipada, e não uma
                // exceção que derrubaria a sessão.
                val chave = chaveDoKeystore() ?: return@withContext LeituraDaChave.Ausente
                val cifra = Cipher.getInstance(TRANSFORMACAO)
                cifra.init(Cipher.DECRYPT_MODE, chave, GCMParameterSpec(BITS_DA_ETIQUETA, dados, 0, TAMANHO_DO_IV))
                cifra.updateAAD(provedor.agente.toByteArray(Charsets.UTF_8))
                val claro = cifra.doFinal(dados, TAMANHO_DO_IV, dados.size - TAMANHO_DO_IV)
                LeituraDaChave.Presente(String(claro, Charsets.UTF_8))
            } catch (erro: GeneralSecurityException) {
                traduzir(erro)
            }
        }
    }

    private fun gerar(): SecretKey {
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            gerador.init(especificacao(strongBox = true))
            gerador.generateKey()
        } catch (_: StrongBoxUnavailableException) {
            gerador.init(especificacao(strongBox = false))
            gerador.generateKey()
        }
    }

    private fun especificacao(strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                janela.inWholeSeconds.toInt(),
                KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
            )
            .setInvalidatedByBiometricEnrollment(false)
            .build()

    private fun chaveDoKeystore(): SecretKey? = keystore().getKey(alias, null) as SecretKey?

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    public companion object {
        /**
         * Nome do arquivo do DataStore, em `files/datastore/`. O `:app` o
         * exclui do backup e da transferência entre aparelhos (seção 4.2): o
         * cifrado não abre sem a chave do Keystore, que não viaja.
         */
        public const val ARQUIVO: String = "maestro_chaves"

        /**
         * O cofre do aplicativo. Um por processo: o DataStore não admite duas
         * instâncias sobre o mesmo arquivo.
         *
         * A [janela] só vale na primeira vez, quando a chave do Keystore é
         * gerada; depois, a chave guarda a janela com que nasceu.
         */
        public fun criar(contexto: Context, janela: Duration): CofreDeChaves {
            val aplicativo = contexto.applicationContext
            return CofreDeChaves(
                aplicativo,
                PreferenceDataStoreFactory.create { aplicativo.preferencesDataStoreFile(ARQUIVO) },
                janela,
                ALIAS,
            )
        }

        private const val ALIAS = "maestro_chaves_de_api"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMACAO = "AES/GCM/NoPadding"
        private const val TAMANHO_DO_IV = 12
        private const val BITS_DA_ETIQUETA = 128

        private fun chaveDoArmazem(provedor: Provedor) = byteArrayPreferencesKey("chave_${provedor.agente}")
    }
}

/**
 * As três causas da seção 4.2, com três respostas: janela expirada pede
 * autenticação, nunca a chave; chave do Keystore invalidada em definitivo é
 * segredo perdido; o resto — etiqueta do GCM que não confere, cifrado órfão —
 * é "não há chave".
 */
internal fun traduzir(erro: GeneralSecurityException): LeituraDaChave = when (erro) {
    is UserNotAuthenticatedException -> LeituraDaChave.ExigeAutenticacao
    is KeyPermanentlyInvalidatedException -> LeituraDaChave.Irrecuperavel
    else -> LeituraDaChave.Ausente
}

/** O resultado de [CofreDeChaves.guardar]. */
public sealed interface Guarda {
    /** Guardada, com a chave do Keystore em [nivel]. */
    public data class Guardada(val nivel: NivelDoCofre) : Guarda

    /**
     * O aparelho não tem trava de tela, e sem ela o Keystore não gera chave
     * presa à autenticação. O usuário precisa definir uma.
     */
    public data object SemTravaDeTela : Guarda

    /** A janela de autenticação expirou; nada foi guardado. */
    public data object ExigeAutenticacao : Guarda
}

/** Onde vive a chave do Keystore que cifra as chaves de API. */
public enum class NivelDoCofre {
    /** Chip dedicado (StrongBox). */
    STRONGBOX,

    /** Ambiente de execução confiável do processador (TEE), sem StrongBox. */
    AMBIENTE_SEGURO,

    /** Sem hardware seguro: a chave vive em software. */
    SOFTWARE,

    /** O Keystore não disse. */
    DESCONHECIDO,
}
