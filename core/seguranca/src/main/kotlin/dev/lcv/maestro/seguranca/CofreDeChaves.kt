package dev.lcv.maestro.seguranca

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.security.keystore.BackendBusyException
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.lcv.maestro.provedores.FonteDeChave
import dev.lcv.maestro.provedores.LeituraDaChave
import dev.lcv.maestro.provedores.Provedor
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - **StrongBox, quando o aparelho o tem.** Sem o hardware, a geração vai ao
 *   caminho sem ele, e [nivel] diz qual está em uso — lido do próprio
 *   Keystore (`KeyInfo`), e não de um registro nosso que pudesse divergir;
 * - **autenticação do usuário por tempo, não por operação**, com uma [janela]
 *   fixa. A janela vale a partir da geração da chave: depois dela, outra
 *   janela passada ao cofre é ignorada pelo Keystore. Trocá-la exige gerar
 *   chave nova, decifrando com a antiga e recifrando com a nova na mesma
 *   operação autenticada (seção 6.2, item 3), o que este módulo não faz;
 * - cadastrar biometria nova não invalida a chave.
 *
 * Cada registro começa por um cabeçalho com o nome do provedor, e o nome
 * também é dado autenticado do GCM: o cifrado de um provedor não abre como o
 * de outro.
 *
 * **Toda operação pública roda sob a mesma exclusão mútua e fora da linha
 * principal.** A chave do Keystore é uma só para os seis provedores, e o
 * estado do cofre é o par "chave do Keystore + cifrados no DataStore":
 * nenhuma leitura pode ver metade de uma troca de chave. As operações do
 * Keystore bloqueiam, e nenhuma roda na linha da interface.
 *
 * **Falha indeterminada nunca apaga nada.** Só se apaga o que está
 * confirmadamente perdido: registro que não pode ser cifrado — de outro tipo,
 * ou de tamanho que não cabe um —, cuja chave do Keystore sumiu ou cuja
 * etiqueta do GCM não confere; e, ao trocar a chave, os cifrados da anterior.
 * Registro com cabeçalho que não é deste provedor não conta como dele, mas
 * fica: o cabeçalho não é autenticado, e um byte trocado nele não prova que o
 * cifrado se perdeu.
 */
public class CofreDeChaves internal constructor(
    /** Só o serviço da trava de tela, e não um `Context`: o cofre do processo vive o processo inteiro. */
    private val trava: KeyguardManager,
    private val armazem: DataStore<Preferences>,
    private val janela: Duration,
    private val alias: String,
    /** Se o aparelho anuncia StrongBox (`FEATURE_STRONGBOX_KEYSTORE`). */
    private val temStrongBox: Boolean,
) : FonteDeChave {

    init {
        // O Keystore recebe a janela em segundos inteiros, num `Int`. Janela
        // infinita, fracionária ou acima do teto seria convertida em silêncio
        // para outro valor, e a chave não teria a janela pedida.
        require(
            janela.isFinite() && janela.inWholeSeconds in 1..Int.MAX_VALUE.toLong() &&
                janela == janela.inWholeSeconds.seconds,
        ) { "a janela de autenticação tem de ser um número inteiro de segundos, entre 1 e ${Int.MAX_VALUE}" }
    }

    /**
     * Cifra e guarda a chave de API do [provedor], trocando a que houver. A
     * cifra também exige autenticação recente: a chave do Keystore é uma só,
     * presa à janela. [chave] em branco é erro de quem chama.
     *
     * Chave com caractere fora de `0x21..0x7E` é recusada antes de qualquer
     * gravação ([Guarda.ChaveInvalida]), com a mesma regra que o
     * `:core:provedores` aplica antes de enviar: ela não iria num cabeçalho
     * HTTP, e um caractere que o UTF-8 não representa — metade de um par
     * substituto — seria gravado trocado por outro.
     */
    public suspend fun guardar(provedor: Provedor, chave: String): Guarda {
        require(chave.isNotBlank()) { "chave de API vazia" }
        val limpa = chave.trim()
        if (limpa.any { it.code !in 0x21..0x7e }) return Guarda.ChaveInvalida
        return exclusivo { guardarNoCofre(provedor, limpa) }
    }

    private suspend fun guardarNoCofre(provedor: Provedor, chave: String): Guarda = try {
        // Chave perdida — ausente, ou invalidada ao buscá-la ou em qualquer
        // ponto da cifra — é trocada, e a invalidação fica lembrada até a
        // troca dar certo. Qualquer outra falha do Keystore é indeterminada e
        // não apaga nada.
        var chaveDoCofre = try {
            chaveDoKeystore()
        } catch (erro: Exception) {
            if (!invalidada(erro)) throw erro
            chaveInvalidada = true
            null
        } ?: substituir()
        val dados = try {
            cifrar(provedor, chave, chaveDoCofre)
        } catch (erro: Exception) {
            if (!invalidada(erro)) throw erro
            chaveInvalidada = true
            chaveDoCofre = substituir()
            cifrar(provedor, chave, chaveDoCofre)
        }
        armazem.edit { it[chaveDoArmazem(provedor)] = dados }
        perdidos.remove(provedor)
        Guarda.Guardada(nivelDe(chaveDoCofre))
    } catch (erro: Exception) {
        if (erro is CancellationException || !falhaDoAparelho(erro)) throw erro
        when {
            // Sem trava de tela o Keystore não gera nem usa chave presa à
            // autenticação; é a causa, mesmo que a exceção fale de outra.
            !travado() -> Guarda.SemTravaDeTela
            erro.temNaCadeia<UserNotAuthenticatedException>() -> Guarda.ExigeAutenticacao
            else -> Guarda.Falhou
        }
    }

    /** O registro do [provedor]: cabeçalho, IV e o cifrado de [chave], com o nome do provedor como AAD. */
    private fun cifrar(provedor: Provedor, chave: String, chaveDoCofre: SecretKey): ByteArray {
        val cifra = Cipher.getInstance(TRANSFORMACAO)
        cifra.init(Cipher.ENCRYPT_MODE, chaveDoCofre)
        cifra.updateAAD(provedor.agente.toByteArray(Charsets.UTF_8))
        return cabecalho(provedor) + cifra.iv + cifra.doFinal(chave.toByteArray(Charsets.UTF_8))
    }

    /**
     * Troca a chave do Keystore que se perdeu. Os cifrados da anterior não
     * abrem com a nova, e por isso são apagados **antes** de gerá-la: se a
     * guarda parar em seguida, eles não sobrevivem para aparecer como
     * configurados.
     */
    private suspend fun substituir(): SecretKey {
        armazem.edit { preferencias ->
            removerOnde(preferencias) { it.startsWith(PREFIXO) || it.startsWith(AFASTADO) }
        }
        perdidos.clear()
        keystore().deleteEntry(alias)
        return gerar().also { chaveInvalidada = false }
    }

    /**
     * Apaga a chave de API do [provedor], com os registros dele que o cofre
     * tenha afastado; `false` se o disco falhou e nada foi apagado.
     */
    public suspend fun apagar(provedor: Provedor): Boolean = exclusivo {
        try {
            armazem.edit { preferencias ->
                removerOnde(preferencias) { it == nomeNoArmazem(provedor) || it.startsWith(afastadoDe(provedor)) }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Se há chave de API guardada para o [provedor] — é o que a tela de
     * configurações mostra, "configurada" ou "não configurada", nunca o valor;
     * `null` quando o disco ou o Keystore não deixaram saber agora.
     *
     * É a mesma leitura de [chaveDe], que abre o registro de verdade: chave do
     * Keystore presente não basta, porque o registro pode não abrir com ela.
     * Configurada é o registro que abriu e o que só espera autenticação —
     * nada disso é sinal de perda. Não configurada é o que não há e o que se
     * perdeu. Disco que não lê e Keystore que falha de forma indeterminada não
     * dizem se o registro abre, e por isso não viram nenhuma das duas: é o
     * "não foi possível verificar agora" da seção 4.2.
     *
     * Com a janela vencida, o Keystore não decifra nada — é o que a
     * autenticação exigida significa —, e a integridade de um registro bem
     * formado não tem como ser conferida: ele conta como configurado até a
     * próxima leitura autenticada, que o afasta se a etiqueta não conferir.
     * Isso vale para qualquer registro alterado com o aplicativo fechado. O
     * que o cofre guarda, em memória, é o registro que ele mesmo já provou
     * não abrir e que o disco não deixou apagar: esse não volta como
     * configurado enquanto o processo viver. Guardar essa prova em outro
     * arquivo não a tornaria durável, porque o caso é justamente o disco
     * recusando gravação.
     */
    public suspend fun configurada(provedor: Provedor): Boolean? = exclusivo {
        when (lerComTentativas(provedor)) {
            is LeituraDaChave.Presente, LeituraDaChave.ExigeAutenticacao -> true
            LeituraDaChave.Ausente, LeituraDaChave.Irrecuperavel -> false
            LeituraDaChave.Indisponivel -> null
        }
    }

    /**
     * Onde a chave do Keystore vive; `null` enquanto ela não foi gerada, e
     * [NivelDoCofre.DESCONHECIDO] se o Keystore não responder.
     */
    public suspend fun nivel(): NivelDoCofre? = exclusivo {
        try {
            chaveDoKeystore()?.let(::nivelDe)
        } catch (erro: Exception) {
            if (erro is CancellationException || !falhaDoAparelho(erro)) throw erro
            NivelDoCofre.DESCONHECIDO
        }
    }

    override suspend fun chaveDe(provedor: Provedor): LeituraDaChave = exclusivo { lerComTentativas(provedor) }

    /**
     * O Keystore ocupado é falha passageira documentada: tenta de novo antes
     * de responder, e responde [LeituraDaChave.Indisponivel] se continuar
     * ocupado — nunca "não há chave".
     */
    private suspend fun lerComTentativas(provedor: Provedor): LeituraDaChave {
        repeat(TENTATIVAS_COM_KEYSTORE_OCUPADO - 1) {
            val leitura = lerDoCofre(provedor)
            if (leitura !== OCUPADO) return leitura as LeituraDaChave
            delay(ESPERA_COM_KEYSTORE_OCUPADO_MS)
        }
        return lerDoCofre(provedor) as? LeituraDaChave ?: LeituraDaChave.Indisponivel
    }

    /** Uma leitura; [OCUPADO] quando o Keystore pediu para tentar depois. */
    private suspend fun lerDoCofre(provedor: Provedor): Any {
        val valor = try {
            lerRegistro(provedor)
        } catch (_: IOException) {
            return LeituraDaChave.Indisponivel
        } ?: return LeituraDaChave.Ausente
        // Valor que não é bytes não pode ser cifrado: sai. Bytes sem o
        // cabeçalho deste provedor não são dele, mas ficam, seja qual for o
        // tamanho — o cabeçalho não é autenticado, e o registro pode ser o de
        // outro provedor. Com o cabeçalho dele, mas curto demais para IV +
        // etiqueta + um byte, não é cifrado dele: sai do nome dele, afastado —
        // o cabeçalho não autenticado não prova que os bytes se perderam.
        // Nenhum desses chega ao Keystore, onde uma janela vencida o faria
        // parecer chave intacta.
        val dados = valor as? ByteArray ?: run {
            retirar(provedor, valor)
            return LeituraDaChave.Ausente
        }
        if (!doProvedor(dados, provedor)) return LeituraDaChave.Ausente
        if (dados.size < cabecalho(provedor).size + TAMANHO_DO_IV + BITS_DA_ETIQUETA / 8 + 1) {
            afastar(provedor, dados)
            return LeituraDaChave.Ausente
        }
        // Perda que este cofre já provou responde o mesmo desfecho sem
        // perguntar ao Keystore, que numa falha passageira a faria parecer
        // chave intacta: a chave invalidada vale para todos os registros até
        // a troca; o registro cuja etiqueta não conferiu e o disco não deixou
        // afastar tenta sair de novo.
        if (chaveInvalidada) return LeituraDaChave.Irrecuperavel
        if (perdidos[provedor]?.contentEquals(dados) == true) {
            afastar(provedor, dados)
            return LeituraDaChave.Ausente
        }
        return try {
            // Cifrado sem a chave que o cifrou — backup restaurado em outro
            // aparelho, trava de tela removida, que apaga a chave — é "não há
            // chave" (seção 4.2), e não volta mais.
            // A chave é uma só: sem ela, nenhum registro abre, e todos saem.
            val chave = chaveDoKeystore() ?: run {
                retirarTodos()
                return LeituraDaChave.Ausente
            }
            val inicio = cabecalho(provedor).size
            val cifra = Cipher.getInstance(TRANSFORMACAO)
            cifra.init(Cipher.DECRYPT_MODE, chave, GCMParameterSpec(BITS_DA_ETIQUETA, dados, inicio, TAMANHO_DO_IV))
            cifra.updateAAD(provedor.agente.toByteArray(Charsets.UTF_8))
            val claro = cifra.doFinal(dados, inicio + TAMANHO_DO_IV, dados.size - inicio - TAMANHO_DO_IV)
            LeituraDaChave.Presente(String(claro, Charsets.UTF_8))
        } catch (erro: Exception) {
            if (erro is CancellationException || !falhaDoAparelho(erro)) throw erro
            when {
                erro.temNaCadeia<BackendBusyException>() -> OCUPADO
                // Invalidação e falta de autenticação vêm antes: a etiqueta
                // só prova perda quando é a única causa. A invalidação vem
                // primeiro: chave perdida não volta com autenticação.
                erro.temNaCadeia<KeyPermanentlyInvalidatedException>() -> {
                    // A chave invalidada não volta, e é uma só para todos os
                    // provedores: os registros ficam até a próxima guarda
                    // trocá-la, e a invalidação fica lembrada para todos.
                    chaveInvalidada = true
                    LeituraDaChave.Irrecuperavel
                }
                erro.temNaCadeia<UserNotAuthenticatedException>() -> LeituraDaChave.ExigeAutenticacao
                erro.temNaCadeia<AEADBadTagException>() -> {
                    // Etiqueta que não confere prova que o registro não é
                    // deste provedor, não que se perdeu: com o cabeçalho
                    // trocado, ele ainda abriria como o de outro. Sai do nome
                    // do provedor, para não continuar "configurado", mas os
                    // bytes ficam, afastados. A marca vem antes, para
                    // sobreviver a um cancelamento no meio.
                    perdidos[provedor] = dados
                    afastar(provedor, dados)
                    LeituraDaChave.Ausente
                }
                else -> traduzir(erro)
            }
        }
    }

    /**
     * Cabeçalho de cada registro: marca de versão e o nome do provedor. Com
     * ele, registro estranho — de outro provedor, bytes quaisquer — é
     * reconhecido sem autenticação, antes de qualquer decifra.
     */
    private fun cabecalho(provedor: Provedor): ByteArray {
        val agente = provedor.agente.toByteArray(Charsets.UTF_8)
        return MARCA + byteArrayOf(agente.size.toByte()) + agente
    }

    /** Se [dados] começa pelo cabeçalho do [provedor]. */
    private fun doProvedor(dados: ByteArray, provedor: Provedor): Boolean {
        val cabecalho = cabecalho(provedor)
        return dados.size >= cabecalho.size && cabecalho.indices.all { dados[it] == cabecalho[it] }
    }

    /** Todas as operações públicas passam por aqui. */
    private suspend fun <T> exclusivo(bloco: suspend () -> T): T =
        EXCLUSAO.withLock { withContext(Dispatchers.IO) { bloco() } }

    /**
     * O valor guardado sob o nome do [provedor], de qualquer tipo; `null` se
     * não há. Falha do disco sobe como `IOException`.
     */
    private suspend fun lerRegistro(provedor: Provedor): Any? =
        armazem.data.first().asMap().entries.firstOrNull { it.key.name == nomeNoArmazem(provedor) }?.value

    /**
     * Apaga o registro do [provedor] que nunca poderá abrir — de outro tipo,
     * curto demais —, se ele ainda for o [observado]: uma guarda que terminou
     * de gravar depois não é apagada no lugar dele. `false` se o disco falhou.
     */
    private suspend fun retirar(provedor: Provedor, observado: Any): Boolean = try {
        armazem.edit { preferencias ->
            if (mesmoValor(preferencias, provedor, observado)) {
                removerOnde(preferencias) { it == nomeNoArmazem(provedor) }
            }
        }
        true
    } catch (_: IOException) {
        false
    }

    /**
     * A chave do Keystore sumiu, e era ela que cifrava todos os registros:
     * nenhum abre mais. Saem todos os que ainda forem os lidos agora.
     */
    private suspend fun retirarTodos() {
        try {
            val lidos = armazem.data.first().asMap().filterKeys { it.name.startsWith(PREFIXO) }
                .mapKeys { it.key.name }
            armazem.edit { preferencias ->
                val atuais = preferencias.asMap().entries.associate { it.key.name to it.value }
                removerOnde(preferencias) { nome ->
                    val lido = lidos[nome]
                    lido != null && iguais(atuais[nome], lido)
                }
            }
        } catch (_: IOException) {
            // A próxima leitura encontra a chave ausente de novo e tenta outra vez.
        }
    }

    /**
     * Tira o registro do [provedor] do nome dele — onde contaria como
     * configurado — e o guarda num nome à parte, com os bytes intactos, se
     * ele ainda for o [observado]. `false` se o disco falhou; a marca em
     * [perdidos] fica até dar certo.
     */
    private suspend fun afastar(provedor: Provedor, observado: ByteArray): Boolean = try {
        armazem.edit { preferencias ->
            if (mesmoValor(preferencias, provedor, observado)) {
                removerOnde(preferencias) { it == nomeNoArmazem(provedor) }
                preferencias[byteArrayPreferencesKey(afastadoDe(provedor) + UUID.randomUUID())] = observado
            }
        }
        perdidos.remove(provedor)
        true
    } catch (_: IOException) {
        false
    }

    /**
     * Registros cuja etiqueta não conferiu, byte a byte, até o disco deixar
     * afastá-los. Só se lê e escreve sob [exclusivo]; a troca da chave limpa
     * tudo.
     */
    private val perdidos = mutableMapOf<Provedor, ByteArray>()

    /**
     * A chave do Keystore se provou invalidada em definitivo. Vale para todos
     * os registros, que ela cifrou, até a troca dar certo. Só se lê e escreve
     * sob [exclusivo].
     */
    private var chaveInvalidada = false

    /** Invalidação definitiva da chave, em qualquer ponto da cadeia; nunca cancelamento. */
    private fun invalidada(erro: Exception): Boolean =
        erro !is CancellationException && erro.temNaCadeia<KeyPermanentlyInvalidatedException>()

    private fun travado(): Boolean = trava.isDeviceSecure

    /**
     * StrongBox só quando o aparelho o anuncia; lá, só se volta ao caminho sem
     * ele quando o próprio StrongBox diz que não suporta a chave
     * (`StrongBoxUnavailableException`), e depois de apagar o que a tentativa
     * possa ter deixado. Qualquer outra falha sobe: rebaixar em silêncio, por
     * uma falha passageira, deixaria a chave mais fraca para sempre.
     */
    private fun gerar(): SecretKey {
        if (temStrongBox) {
            try {
                return gerarChave(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                keystore().deleteEntry(alias)
            }
        }
        return gerarChave(strongBox = false)
    }

    private fun gerarChave(strongBox: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(especificacao(strongBox)) }.generateKey()

    private fun especificacao(strongBox: Boolean): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(alias, PROPOSITOS)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setIsStrongBoxBacked(strongBox)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(janela.inWholeSeconds.toInt(), AUTENTICACAO)
            .setInvalidatedByBiometricEnrollment(false)
            .build()

    /**
     * A chave do cofre é a que [especificacao] gera, conferida em cada
     * atributo que o `KeyInfo` informa: gerada dentro do Keystore, e não
     * importada com material conhecido fora dele; AES de 256 bits — forma que só uma
     * chave AES tem, e por isso o algoritmo não precisa de conferência à
     * parte —, só para cifrar e decifrar, em GCM sem preenchimento, presa à
     * autenticação do usuário por tempo e pelos mesmos meios, sem datas de
     * validade, sem limite de usos, sem invalidação por biometria nova e sem
     * as exigências de corpo, presença ou confirmação. A janela em si não se
     * confere: ela vale desde a geração da chave.
     * Entrada com qualquer outra forma não é a dele e conta como ausente: a
     * guarda seguinte a troca, em vez de usar uma chave sem a proteção
     * prometida ou ficar presa a ela. Só este módulo escreve nesse nome, no
     * Keystore que é só do aplicativo; outra forma lá é defeito dele, e trocar
     * a chave é decisão do operador de 24/09/2026.
     */
    private fun chaveDoKeystore(): SecretKey? {
        val chave = keystore().getKey(alias, null) as? SecretKey ?: return null
        val info = SecretKeyFactory.getInstance(chave.algorithm, KEYSTORE)
            .getKeySpec(chave, KeyInfo::class.java) as KeyInfo
        // Exigida e por tempo, conferidas à parte, como o `KeyInfo` as
        // documenta: medido no Android 17, o Keystore nem registra a janela
        // de uma chave sem autenticação, mas a documentação não promete isso.
        val daForma = info.isUserAuthenticationRequired &&
            info.userAuthenticationValidityDurationSeconds > 0 &&
            info.userAuthenticationType == AUTENTICACAO &&
            info.purposes == PROPOSITOS &&
            info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM) &&
            info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE) &&
            info.keySize == 256 &&
            info.origin == KeyProperties.ORIGIN_GENERATED &&
            !info.isInvalidatedByBiometricEnrollment &&
            info.keyValidityStart == null &&
            info.keyValidityForOriginationEnd == null &&
            info.keyValidityForConsumptionEnd == null &&
            info.remainingUsageCount == KeyProperties.UNRESTRICTED_USAGE_COUNT &&
            !info.isUserAuthenticationValidWhileOnBody &&
            !info.isTrustedUserPresenceRequired &&
            !info.isUserConfirmationRequired
        return chave.takeIf { daForma }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Diagnóstico: nunca transforma uma guarda já gravada em falha. */
    private fun nivelDe(chave: SecretKey): NivelDoCofre = try {
        val info = SecretKeyFactory.getInstance(chave.algorithm, KEYSTORE)
            .getKeySpec(chave, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> NivelDoCofre.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> NivelDoCofre.AMBIENTE_SEGURO
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> NivelDoCofre.SOFTWARE
            else -> NivelDoCofre.DESCONHECIDO
        }
    } catch (erro: Exception) {
        if (erro is CancellationException) throw erro
        NivelDoCofre.DESCONHECIDO
    }

    public companion object {
        /**
         * O cofre do aplicativo, um só por processo: o DataStore recusa duas
         * instâncias sobre o mesmo arquivo. Chamadas seguintes devolvem o
         * mesmo cofre, e uma [janela] diferente da dele é recusada.
         *
         * A [janela] só vale na primeira vez que a chave do Keystore é
         * gerada; depois, a chave guarda a janela com que nasceu.
         */
        public fun criar(contexto: Context, janela: Duration): CofreDeChaves = synchronized(this) {
            doProcesso?.also {
                require(it.janela == janela) { "o cofre do processo já existe, com janela ${it.janela}" }
            } ?: contexto.applicationContext.let { aplicativo ->
                CofreDeChaves(
                    aplicativo.getSystemService(KeyguardManager::class.java),
                    armazemDoCofre(arquivoDoCofre(aplicativo)),
                    janela,
                    ALIAS,
                    aplicativo.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE),
                ).also { doProcesso = it }
            }
        }

        /**
         * Onde o cofre grava: dentro de `noBackupFilesDir`, que o Android
         * exclui do backup e da transferência entre aparelhos — "sempre
         * excluídos, mesmo se você tentar incluí-los", e "totalmente habilitado
         * para todo o conteúdo, exceto os diretórios no-backup e cache" quando
         * não há regra de transferência, diz a documentação do Auto Backup. A
         * exclusão pega também o temporário que o DataStore grava ao lado do
         * arquivo (seção 4.2): o cifrado não abre sem a chave do Keystore, que
         * não viaja.
         */
        internal fun arquivoDoCofre(contexto: Context): File =
            File(File(contexto.applicationContext.noBackupFilesDir, PASTA), ARQUIVO)

        /**
         * O DataStore do cofre. Arquivo que o DataStore não consegue ler vira
         * cofre vazio pelo mecanismo oficial dele, em vez de uma exceção a cada
         * leitura, que deixaria o cofre inutilizável — mas só depois de copiado
         * inteiro para uma quarentena só dele, gravada no disco. Um registro
         * intacto dentro do arquivo ilegível continua lá. Se a cópia falhar, o
         * arquivo não é trocado, e a leitura fica indisponível até a cópia dar
         * certo. [quarentena] só muda nos testes, para provar esse caminho.
         */
        internal fun armazemDoCofre(
            arquivo: File,
            escopo: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            quarentena: (File) -> Unit = ::quarentenar,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler {
                quarentena(arquivo)
                emptyPreferences()
            },
            scope = escopo,
        ) { arquivo }

        /**
         * Copia [arquivo] para uma quarentena nova, nunca por cima de outra, e
         * grava no disco o arquivo e a entrada dele na pasta antes de voltar:
         * sem a pasta, uma queda de energia poderia perder a quarentena
         * depois de o DataStore já ter trocado o arquivo. Falha sobe como
         * `IOException`, sem deixar cópia pela metade.
         */
        private fun quarentenar(arquivo: File) {
            val destino = generateSequence(1) { it + 1 }
                .map { File(arquivo.path + "$SUFIXO_DA_QUARENTENA$it") }
                .first { it.createNewFile() }
            try {
                destino.outputStream().use { saida ->
                    arquivo.inputStream().use { it.copyTo(saida) }
                    saida.fd.sync()
                }
                gravarPasta(destino.absoluteFile.parentFile!!)
            } catch (erro: IOException) {
                destino.delete()
                throw erro
            }
        }

        /** `fsync` da própria pasta, que grava no disco as entradas dela. */
        private fun gravarPasta(pasta: File) {
            try {
                val descritor = Os.open(pasta.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(descritor)
                } finally {
                    Os.close(descritor)
                }
            } catch (erro: ErrnoException) {
                throw IOException("fsync de ${pasta.name}", erro)
            }
        }

        /** As quarentenas de [arquivo], na ordem em que foram feitas. */
        internal fun quarentenasDe(arquivo: File): List<File> =
            generateSequence(1) { it + 1 }
                .map { File(arquivo.path + "$SUFIXO_DA_QUARENTENA$it") }
                .takeWhile { it.exists() }
                .toList()

        @Volatile
        private var doProcesso: CofreDeChaves? = null

        /** Uma por processo, para todos os cofres: o Keystore é do processo. */
        private val EXCLUSAO = Mutex()

        private const val PASTA = "cofre"
        private const val ARQUIVO = "maestro_chaves.preferences_pb"
        private const val ALIAS = "maestro_chaves_de_api"
        private const val PREFIXO = "chave_"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMACAO = "AES/GCM/NoPadding"
        private const val TAMANHO_DO_IV = 12
        private const val BITS_DA_ETIQUETA = 128
        private const val TENTATIVAS_COM_KEYSTORE_OCUPADO = 3
        private const val ESPERA_COM_KEYSTORE_OCUPADO_MS = 250L
        private val OCUPADO = Any()
        private const val PROPOSITOS = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        private const val AUTENTICACAO = KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG

        /** Prefixo dos registros afastados do nome do provedor. */
        private const val AFASTADO = "afastado_"
        private const val SUFIXO_DA_QUARENTENA = ".corrompido-"

        /** Marca de versão do formato do registro. */
        private val MARCA = "MAE1".toByteArray(Charsets.US_ASCII)

        private fun nomeNoArmazem(provedor: Provedor) = "$PREFIXO${provedor.agente}"

        private fun chaveDoArmazem(provedor: Provedor) = byteArrayPreferencesKey(nomeNoArmazem(provedor))

        private fun afastadoDe(provedor: Provedor) = "$AFASTADO${provedor.agente}_"

        /** Se o valor sob o nome do [provedor] ainda é o [observado]. */
        private fun mesmoValor(preferencias: MutablePreferences, provedor: Provedor, observado: Any): Boolean =
            iguais(preferencias.asMap().entries.firstOrNull { it.key.name == nomeNoArmazem(provedor) }?.value, observado)

        private fun iguais(atual: Any?, observado: Any): Boolean =
            if (atual is ByteArray && observado is ByteArray) atual.contentEquals(observado) else atual == observado

        /** Remove as entradas cujo nome satisfaz [condicao], seja qual for o tipo delas. */
        private fun removerOnde(preferencias: MutablePreferences, condicao: (String) -> Boolean) {
            preferencias.asMap().keys.filter { condicao(it.name) }.forEach {
                @Suppress("UNCHECKED_CAST")
                preferencias.remove(it as Preferences.Key<Any>)
            }
        }
    }
}

/**
 * Falhas que o Keystore e o disco podem dar. Qualquer outra exceção é erro de
 * programa e sobe como está.
 */
private fun falhaDoAparelho(erro: Exception): Boolean =
    erro is GeneralSecurityException || erro is ProviderException || erro is IOException ||
        erro is IllegalStateException || erro is android.security.KeyStoreException

private inline fun <reified T : Throwable> Throwable.temNaCadeia(): Boolean =
    generateSequence(this) { it.cause }.take(PROFUNDIDADE_DA_CADEIA).any { it is T }

private const val PROFUNDIDADE_DA_CADEIA = 8

/**
 * As causas da seção 4.2, cada uma com sua resposta: chave do Keystore
 * invalidada em definitivo é segredo perdido, e vem primeiro, porque não volta
 * com autenticação; janela expirada pede autenticação, nunca a chave; etiqueta do GCM que não confere é cifrado que nunca abrirá,
 * "não há chave". Qualquer outra falha é indeterminada — Keystore ocupado ou
 * falhando —, e a chave fica indisponível agora, sem ser dada por perdida. A
 * causa é procurada na cadeia inteira, porque o Keystore pode entregá-la
 * embrulhada noutra exceção.
 */
internal fun traduzir(erro: Throwable): LeituraDaChave = when {
    erro.temNaCadeia<KeyPermanentlyInvalidatedException>() -> LeituraDaChave.Irrecuperavel
    erro.temNaCadeia<UserNotAuthenticatedException>() -> LeituraDaChave.ExigeAutenticacao
    erro.temNaCadeia<AEADBadTagException>() -> LeituraDaChave.Ausente
    else -> LeituraDaChave.Indisponivel
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

    /**
     * O Keystore ou o disco falharam. Nada que ainda abria foi apagado; vale
     * tentar de novo.
     */
    public data object Falhou : Guarda

    /**
     * A chave tem caractere fora de `0x21..0x7E` e não iria num cabeçalho
     * HTTP; nada foi gravado, e a chave que havia continua.
     */
    public data object ChaveInvalida : Guarda
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
