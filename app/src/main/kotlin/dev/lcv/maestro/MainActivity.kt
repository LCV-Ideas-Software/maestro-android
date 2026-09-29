/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.lcv.maestro.ui.AutenticadorDaPlataforma
import dev.lcv.maestro.ui.MaestroApp
import dev.lcv.maestro.ui.MaestroTheme

/**
 * Activity única (`singleTask`): toda a navegação acontece dentro do Compose.
 * O toque na notificação de uma sessão chega aqui com o id da sessão — por
 * `onCreate`, num arranque frio, ou por `onNewIntent`, com o aplicativo já
 * aberto — e a tela da sessão abre nos dois casos.
 */
class MainActivity : ComponentActivity() {

    /** O id da sessão pedido pela última intenção; a navegação o consome, e ele sai do intent. */
    private var sessaoPedida by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `enableEdgeToEdge()` sem argumento segue o modo escuro do aparelho e
        // pintaria os ícones das barras de branco; a interface é sempre clara
        // (desvio declarado na especificação), então as barras são declaradas
        // claras, para que os ícones fiquem escuros e legíveis em qualquer modo.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Só um arranque novo traz pedido: numa recriação (rotação, tema, processo
        // restaurado) a pilha já vem do estado salvo, e um relançamento pelo
        // histórico repete o intent antigo, que já foi atendido.
        val relancadaDoHistorico = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        sessaoPedida = if (savedInstanceState == null && !relancadaDoHistorico) intent?.getStringExtra(EXTRA_SESSAO) else null
        val dependencias = Dependencias.de(maestro)
        val autenticador = AutenticadorDaPlataforma(this)
        setContent {
            MaestroTheme {
                MaestroApp(
                    dependencias = dependencias,
                    autenticador = autenticador,
                    versao = BuildConfig.VERSION_NAME,
                    sessaoPedida = sessaoPedida,
                    aoConsumirSessaoPedida = {
                        sessaoPedida = null
                        // Atendido, o pedido sai do intent que a Activity guarda.
                        intent?.removeExtra(EXTRA_SESSAO)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sessaoPedida = intent.getStringExtra(EXTRA_SESSAO)
    }

    companion object {
        const val EXTRA_SESSAO = "dev.lcv.maestro.SESSAO"

        fun intencao(contexto: Context, sessaoId: String): Intent = Intent(contexto, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SESSAO, sessaoId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Um código por sessão, num espaço diferente do da ação de cancelar da
         * notificação (`sessaoId.hashCode()` no `:core:sessao`): dois
         * `PendingIntent` com o mesmo código e o mesmo tipo se sobrescreveriam.
         */
        fun codigoDaIntencao(sessaoId: String): Int = sessaoId.hashCode() xor 0x5A5A5A5A
    }
}
