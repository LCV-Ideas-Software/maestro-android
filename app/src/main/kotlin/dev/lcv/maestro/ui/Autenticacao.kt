/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.lcv.maestro.R
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A autenticação do usuário que renova a janela da chave do Keystore
 * (especificação, seção 6.2). As telas pedem antes de **toda** partida,
 * retomada e "Testar chaves", e nada começa quando ela falha (revisão cruzada
 * de 28/09/2026, emenda A6): o `configurada()` do cofre não distingue uma
 * janela vencida sem decifrar, e pausar logo depois de começar é pior do que
 * pedir antes. A tela suspende aqui e só então chama o `ViewModel`, que
 * nunca segura uma `Activity`; os testes trocam por um dublê.
 */
fun interface Autenticador {
    suspend fun autenticar(): Boolean
}

val LocalAutenticador = staticCompositionLocalOf<Autenticador> { error("nenhum Autenticador fornecido") }

/**
 * Um pedido de autenticação por vez, por tela: um segundo toque enquanto o
 * primeiro pedido está aberto é ignorado, em vez de abrir outro prompt cujo
 * desfecho contradiria o do primeiro ("nada foi iniciado" com a sessão já
 * iniciada). [emCurso] desabilita o botão que pediu.
 */
class AutenticacaoDaTela(private val autenticador: Autenticador, private val escopo: CoroutineScope) {
    var emCurso by mutableStateOf(false)
        private set

    fun pedir(aoAutenticar: () -> Unit, aoRecusar: () -> Unit) {
        if (emCurso) return
        emCurso = true
        escopo.launch {
            try {
                if (autenticador.autenticar()) aoAutenticar() else aoRecusar()
            } finally {
                emCurso = false
            }
        }
    }
}

@Composable
fun rememberAutenticacaoDaTela(): AutenticacaoDaTela {
    val autenticador = LocalAutenticador.current
    val escopo = rememberCoroutineScope()
    return remember(autenticador, escopo) { AutenticacaoDaTela(autenticador, escopo) }
}

/**
 * O `BiometricPrompt` da plataforma (API 28+, disponível no `minSdk` 36): a
 * Activity do aplicativo é `ComponentActivity`, convenção da frota, e o
 * `androidx.biometric` exigiria `FragmentActivity`. Os autenticadores são os
 * da chave — biometria forte ou a credencial do aparelho —, e por isso não há
 * botão negativo: com `DEVICE_CREDENTIAL` ele é proibido, e o próprio pedido
 * de credencial é a saída. Sem `CryptoObject`: a chave é presa a uma janela
 * de tempo, e uma autenticação bem-sucedida com esses autenticadores a
 * renova. Cancelar a corrotina (a tela saiu, a Activity foi destruída)
 * cancela o pedido.
 */
class AutenticadorDaPlataforma(private val activity: Activity) : Autenticador {

    override suspend fun autenticar(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuacao ->
            val cancelamento = CancellationSignal()
            continuacao.invokeOnCancellation { cancelamento.cancel() }
            val pedido = BiometricPrompt.Builder(activity)
                .setTitle(activity.getString(R.string.autenticacao_titulo))
                .setSubtitle(activity.getString(R.string.autenticacao_subtitulo))
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()
            pedido.authenticate(
                cancelamento,
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(resultado: BiometricPrompt.AuthenticationResult) {
                        if (continuacao.isActive) continuacao.resume(true)
                    }

                    // Erro é fim: cancelado pelo usuário, sem credencial no aparelho,
                    // bloqueado por tentativas. Uma digital não reconhecida
                    // (`onAuthenticationFailed`) não é fim: o pedido continua aberto.
                    override fun onAuthenticationError(codigo: Int, mensagem: CharSequence) {
                        if (continuacao.isActive) continuacao.resume(false)
                    }
                },
            )
        }
    }
}
