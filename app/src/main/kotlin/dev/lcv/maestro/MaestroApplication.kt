/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import dev.lcv.maestro.seguranca.CofreDeChaves
import dev.lcv.maestro.sessao.Fabrica
import dev.lcv.maestro.sessao.FabricaDeTrabalhos
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A raiz do processo (decisão 18 do operador, 28/09/2026: sem Hilt — a
 * [Fabrica] do `:core:sessao` é a raiz de composição e este `Application` só
 * a monta e a instala).
 *
 * A ordem do arranque é a que a revisão cruzada de 28/09/2026 exigiu (emenda
 * A1): a configuração do WorkManager é uma propriedade que **não** toca a
 * fábrica — a `FabricaDeTrabalhos` resolve o grafo quando um worker nasce —,
 * e a [Fabrica] só toca o WorkManager no primeiro uso do agendador, depois de
 * instalada: com a inicialização sob demanda, a primeira chamada a
 * `WorkManager.getInstance` lê esta configuração e pode começar na hora um
 * trabalho pendente, cujo worker precisa encontrar a fábrica já instalada. Só
 * depois de o cofre e a fábrica existirem é que o observador do processo
 * passa a disparar a reconciliação a cada entrada em primeiro plano
 * (especificação, seção 4.3: "na abertura do aplicativo").
 */
class MaestroApplication : Application(), Configuration.Provider {

    /** Resolvido tarde, e nunca antes de [onCreate] ter instalado a fábrica. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(FabricaDeTrabalhos { Fabrica.doProcesso ?: error("a Fabrica ainda nao foi instalada") })
            // Decisão 25 estendida (#80): o banco do WorkManager que falha ao iniciar é aviso na tela inicial, não queda.
            .setInitializationExceptionHandler { erro -> abertura.falhouNoWorkManager(erro) }
            .build()

    lateinit var cofre: CofreDeChaves
        private set

    lateinit var grafo: Fabrica
        private set

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A reconciliação a cada entrada em primeiro plano; lê [grafo] só quando roda, depois de [onCreate]. */
    val abertura = ReconciliacaoDaAbertura { grafo.reconciliacao.naAbertura() }

    override fun onCreate() {
        super.onCreate()
        cofre = CofreDeChaves.criar(this, JANELA_DE_AUTENTICACAO)
        grafo = Fabrica(this, cofre, BuildConfig.VERSION_NAME, ::intencaoDaSessao)
        Fabrica.instalar(grafo)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    escopo.launch { abertura.executar() }
                }
            },
        )
    }

    /** O toque na notificação abre a tela da sessão: intenção explícita, imutável, com código próprio por sessão. */
    private fun intencaoDaSessao(sessaoId: String): PendingIntent = PendingIntent.getActivity(
        this,
        MainActivity.codigoDaIntencao(sessaoId),
        MainActivity.intencao(this, sessaoId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        /**
         * A janela de autenticação do Keystore (especificação, seção 6.2;
         * decisão 17 do operador, 28/09/2026): 300 minutos, o mesmo teto de
         * tempo de uma sessão (`RepositorioDeConfiguracoes.TETO_DE_MINUTOS`).
         * Fica gravada na chave quando ela nasce; trocá-la depois exige
         * recifrar todas as chaves de API, o que o cofre não faz.
         */
        val JANELA_DE_AUTENTICACAO = 300.minutes
    }
}
