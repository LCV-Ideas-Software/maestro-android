package dev.lcv.maestro.sessao

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * O toque na notificação da sessão (plano do `:app`, lacuna 0.4d e emenda
 * A10): o destino é o que o `:app` fornece para aquela sessão; sem destino, a
 * notificação não tem toque.
 */
@RunWith(AndroidJUnit4::class)
class NotificacaoTest {

    private val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    private val progresso = Progresso(rodada = 1, agente = null, custoObservadoUsd = BigDecimal.ZERO)

    @Test
    fun oToqueLevaAIntencaoQueOAppDeuParaAquelaSessao() {
        val pedidas = mutableListOf<String>()
        val intencao = PendingIntent.getActivity(
            contexto,
            7,
            Intent("dev.lcv.maestro.TESTE").setPackage(contexto.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notificacao = Notificacao(contexto) { sessaoId ->
            pedidas += sessaoId
            intencao
        }.primeiroPlano("android-x", progresso).notification
        assertSame(intencao, notificacao.contentIntent)
        assertEquals(listOf("android-x"), pedidas)
    }

    @Test
    fun semDestinoANotificacaoNaoTemToque() {
        assertNull(Notificacao(contexto).primeiroPlano("android-x", progresso).notification.contentIntent)
    }
}
