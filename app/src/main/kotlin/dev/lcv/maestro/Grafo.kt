/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.net.toUri
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.CofreDeChaves
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.seguranca.Remocao
import dev.lcv.maestro.sessao.Agendador
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.ArmazemDeEvidenciasEmArquivo
import dev.lcv.maestro.sessao.LinksDaSessao
import dev.lcv.maestro.sessao.RepositorioDeArtefatos
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.Retomada
import dev.lcv.maestro.sessao.TesteDeChaves
import dev.lcv.maestro.sessao.motivoDeArmazenamento
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uma trava só para a reconciliação da abertura e para o par
 * `criar → enfileirar` / `pedir → enfileirar` das telas (revisão cruzada de
 * 28/09/2026, emenda A11): uma linha recém-inserida não pode ser varrida como
 * "sem trabalho vivo" entre a inserção e o enfileiramento.
 */
object Sincronia {
    val reconciliacao = Mutex()
}

/**
 * A reconciliação da abertura (especificação, seção 4.3), sob a trava da [Sincronia], a cada entrada em primeiro plano,
 * e a inicialização do WorkManager. Decisão 25 do operador, estendida em 30/09/2026 (#80): o armazenamento que falha
 * numa ou noutra não derruba o aplicativo. Vira uma [Falha], que a tela inicial avisa, e a próxima entrada em primeiro
 * plano tenta a reconciliação de novo. Na reconciliação, qualquer outra exceção, inclusive o cancelamento, segue adiante.
 */
class ReconciliacaoDaAbertura(private val reconciliar: suspend () -> Unit) {

    /** Uma por tentativa, sem igualdade de valor: duas falhas seguidas com o mesmo motivo são dois avisos. */
    class Falha(@StringRes val mensagem: Int, val motivo: String)

    private val daReconciliacao = MutableStateFlow<Falha?>(null)
    private val doWorkManager = MutableStateFlow<Falha?>(null)

    /** O desfecho da tentativa mais recente da reconciliação: `null` enquanto ela corre e quando deu certo. */
    val falhaDaReconciliacao: StateFlow<Falha?> = daReconciliacao.asStateFlow()

    /** A inicialização do WorkManager que não abriu o banco dele; vale para o processo todo. */
    val falhaDoWorkManager: StateFlow<Falha?> = doWorkManager.asStateFlow()

    /** As duas, para a tela inicial avisar: quem coleta de novo recebe as que ainda valem. */
    val falhas: Flow<Falha> = merge(daReconciliacao.filterNotNull(), doWorkManager.filterNotNull())

    /** Bloqueante no Room e no WorkManager: chamar em `Dispatchers.IO`. */
    suspend fun executar() {
        // Antes da trava: a tela que nasce nesta entrada em primeiro plano não avisa a falha da entrada anterior.
        daReconciliacao.value = null
        daReconciliacao.value = try {
            Sincronia.reconciliacao.withLock { reconciliar() }
            null
        } catch (erro: Exception) {
            Falha(R.string.reconciliacao_falhou, motivoDeArmazenamento(erro))
        }
    }

    /**
     * O `initializationExceptionHandler` do WorkManager: a biblioteca entrega aqui, numa `IllegalStateException`, a
     * falha do SQLite que ela considera acionável ao iniciar o banco dela (`ForceStopRunnable`: no caminho principal
     * depois de três tentativas; na migração do caminho do banco, sem tentar de novo); sem ele, a exceção derrubaria o
     * processo. O filtro é o da biblioteca, não o [motivoDeArmazenamento]. Roda numa linha do WorkManager e nunca
     * relança: o motivo é o da causa.
     */
    fun falhouNoWorkManager(erro: Throwable) {
        doWorkManager.value = Falha(R.string.workmanager_falhou, (erro.cause ?: erro).message.orEmpty())
    }
}

/**
 * O que as telas pedem ao cofre, e nada mais: a presença de cada chave (com o
 * terceiro estado "não foi possível verificar agora"), guardar, apagar, o
 * nível da chave do Keystore e se o aparelho tem trava de tela. O
 * [CofreDeChaves] é preso ao hardware; os testes das telas o substituem por
 * um dublê em memória (especificação, seção 8).
 */
interface CofreDaTela {
    suspend fun chaves(): Map<Provedor, Boolean?>
    suspend fun guardar(provedor: Provedor, chave: String): Guarda
    suspend fun apagar(provedor: Provedor): Remocao
    suspend fun nivel(): NivelDoCofre?

    /** `KeyguardManager.isDeviceSecure`: sem trava, a chave do Keystore não existe, e uma chave perdida foi por isso (seção 4.2). */
    fun travaDeTela(): Boolean
}

class CofreReal(
    private val contexto: Context,
    private val cofre: CofreDeChaves,
    private val configuracoes: RepositorioDeConfiguracoes,
) : CofreDaTela {
    override suspend fun chaves(): Map<Provedor, Boolean?> = configuracoes.chaves()
    override suspend fun guardar(provedor: Provedor, chave: String): Guarda = cofre.guardar(provedor, chave)
    override suspend fun apagar(provedor: Provedor): Remocao = cofre.apagar(provedor)
    override suspend fun nivel(): NivelDoCofre? = cofre.nivel()
    override fun travaDeTela(): Boolean = contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure
}

/**
 * O navegador do sistema, que abre o link na captura assistida pelo operador
 * (especificação, seção 2.2): devolve o erro do disparo, com o texto do
 * canônico, ou `null` quando abriu. Os testes das telas o trocam por um dublê
 * que só anota a URL — nenhum teste abre navegador.
 */
fun interface Navegador {
    fun abrir(contexto: Context, url: String): String?

    companion object {
        /**
         * `ACTION_VIEW` com `CATEGORY_BROWSABLE`, só para a URL já validada, a
         * partir do contexto da Activity (fora dela, o Android recusa o
         * disparo sem `FLAG_ACTIVITY_NEW_TASK`). A falta de navegador chega
         * como `ActivityNotFoundException` e o disparo barrado como
         * `SecurityException`; os dois ficam anotados no registro de passagem.
         */
        val DO_SISTEMA = Navegador { contexto, url ->
            try {
                contexto.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                null
            } catch (erro: ActivityNotFoundException) {
                "failed to open system default browser: ${erro.message}"
            } catch (erro: SecurityException) {
                // `startActivity` também lança isto quando uma política, um perfil de trabalho ou o próprio
                // aplicativo de destino barra o disparo; vai para o registro como a falha acima (achado do Codex na #78).
                "failed to open system default browser: ${erro.message}"
            }
        }
    }
}

/**
 * Uma busca de evidências e o jeito de pará-la: a busca bloqueia no HTTP, e
 * cancelar a corrotina não a interrompe — só o `cancelarTudo` dela. A tela
 * cancela quando sai (achado do Codex na #78).
 */
class BuscaDaTela(val buscador: IntegridadeDeLinks.BuscadorDeEvidencia, val cancelar: () -> Unit)

/**
 * O que as telas usam do grafo: os repositórios sobre o Room, o agendador, o
 * cofre visto pela tela, o teste de chaves, os anexos e a revisão dos links
 * (linhas, evidências, captura assistida, busca e navegador). Em produção vem da `Fabrica` do
 * processo ([de]); os testes das telas montam a mesma classe sobre um banco
 * de teste e dublês, sem Hilt (decisão 18 do operador, 28/09/2026).
 */
class Dependencias(
    val sessoes: RepositorioDeSessoes,
    val artefatos: RepositorioDeArtefatos,
    val retomada: Retomada,
    val configuracoes: RepositorioDeConfiguracoes,
    val agendador: Agendador,
    val cofre: CofreDaTela,
    val testeDeChaves: TesteDeChaves,
    val anexos: AnexosDaSessao,
    val links: LinksDaSessao,
    val evidencias: ArmazemDeEvidenciasEmArquivo,
    val importacao: ImportacaoDoOperador,
    /** A busca de evidências (Crossref e OpenAlex) com o e-mail de contato atual; lê o Room, então fora da linha principal. */
    val busca: () -> BuscaDaTela,
    val navegador: Navegador,
    /** As falhas da reconciliação da abertura e da inicialização do WorkManager, que a tela inicial avisa (#80). */
    val falhasDaAbertura: Flow<ReconciliacaoDaAbertura.Falha>,
    val relogio: () -> Instant = Instant::now,
) {
    companion object {
        fun de(aplicativo: MaestroApplication): Dependencias = aplicativo.grafo.let { grafo ->
            Dependencias(
                sessoes = grafo.sessoes,
                artefatos = grafo.artefatos,
                retomada = grafo.retomada,
                configuracoes = grafo.configuracoes,
                agendador = grafo.agendador,
                cofre = CofreReal(aplicativo, aplicativo.cofre, grafo.configuracoes),
                testeDeChaves = grafo.testeDeChaves,
                anexos = grafo.anexos,
                links = grafo.links,
                evidencias = grafo.evidencias,
                importacao = grafo.importacao,
                busca = { grafo.buscaDeEvidencias().let { BuscaDaTela(it, it::cancelarTudo) } },
                navegador = Navegador.DO_SISTEMA,
                falhasDaAbertura = aplicativo.abertura.falhas,
            )
        }
    }
}

/** O `Application` deste processo, de qualquer `Context`. */
val Context.maestro: MaestroApplication
    get() = applicationContext as MaestroApplication
