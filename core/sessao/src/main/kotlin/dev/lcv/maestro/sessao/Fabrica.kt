package dev.lcv.maestro.sessao

import android.content.Context
import androidx.work.WorkManager
import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.AgenteDeColeta
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import dev.lcv.maestro.provedores.ClienteDeProvedores
import dev.lcv.maestro.provedores.ColetorHttp
import dev.lcv.maestro.provedores.ResolvedorPublico
import dev.lcv.maestro.seguranca.CofreDeChaves
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * O grafo de produção do `:core:sessao`, montado uma vez por processo pelo
 * `:app`: o Room, os repositórios, o cliente dos provedores sobre o cofre, a
 * auditoria de links com um [ColetorHttp] novo por auditoria (especificação,
 * seção 5.4) e o agendador. Os testes não passam por aqui: montam
 * [GrafoDaSessao] com dublês sobre o mesmo Room.
 */
public class Fabrica(
    contexto: Context,
    private val cofre: CofreDeChaves,
    versaoDoAplicativo: String,
    emailDeContato: String?,
    private val relogio: () -> Instant = Instant::now,
) : GrafoDaSessao {
    // O contexto do aplicativo não fica guardado aqui: a fábrica é referência
    // estática do processo, e um `Context` num campo estático é o que o lint
    // `StaticFieldLeak` vigia. Quem precisa dele (a notificação, o WorkManager)
    // guarda o do aplicativo, que vive o processo inteiro.
    public val banco: BancoDaSessao = BancoDaSessao.abrir(contexto.applicationContext)
    override val sessoes: RepositorioDeSessoes = RepositorioDeSessoes(banco, relogio)
    public val artefatos: RepositorioDeArtefatos = RepositorioDeArtefatos(banco, relogio)
    public val retomada: Retomada = Retomada(banco, sessoes, artefatos, relogio)
    public val ponto: PontoDeRetomada = PontoDeRetomada(banco, artefatos, relogio)
    public val configuracoes: RepositorioDeConfiguracoes = RepositorioDeConfiguracoes(banco, cofre, relogio)
    public val anexos: AnexosDaSessao = AnexosDaSessao(banco, File(contexto.applicationContext.noBackupFilesDir, "anexos"), relogio)
    public val evidencias: ArmazemDeEvidenciasEmArquivo = ArmazemDeEvidenciasEmArquivo(banco, File(contexto.applicationContext.noBackupFilesDir, "evidencias"), relogio)
    public val agendador: Agendador = Agendador(WorkManager.getInstance(contexto.applicationContext))
    override val notificacao: Notificacao = Notificacao(contexto.applicationContext)
    private val agenteDeColeta = AgenteDeColeta(versaoDoAplicativo, emailDeContato)
    private val resolvedor = ResolvedorPublico.dnsDoGoogle()

    /** O cliente dos seis provedores: novo, limpo e só TLS moderno, como o transporte da auditoria. */
    private val cliente = ClienteDeProvedores(OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build(), cofre)

    /**
     * A auditoria de cinco estágios com o motor de links real: um coletor por
     * auditoria, em `Dispatchers.IO`, cancelado (`cancelarTudo`) se a
     * corrotina for cancelada no meio da coleta.
     */
    public val auditoria: AuditoriaDaSessao = AuditoriaDaSessao { sessaoId, texto, citacoes ->
        val coletor = ColetorHttp(resolvedor, agenteDeColeta, evidencias)
        val registro = RegistroDeLinksRoom(banco, sessaoId, relogio)
        val motor = AuditoriaFinal.MotorDeLinks { candidato -> IntegridadeDeLinks.auditar(candidato, AnalisadorDeUrlOkHttp, coletor, registro, relogio) }
        coroutineScope {
            val vigia = launch {
                try {
                    awaitCancellation()
                } finally {
                    coletor.cancelarTudo()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    AuditoriaFinal.falhaComCitacoes(texto, citacoes.hashDoProtocolo, citacoes.manifesto, citacoes.manifestoAnterior, motor, relogio())
                }
            } finally {
                vigia.cancel()
            }
        }
    }

    override fun deliberacao(parar: () -> Boolean, aoAvancar: suspend (Progresso) -> Unit): Deliberacao = Deliberacao(
        sessoes = sessoes,
        retomada = retomada,
        ponto = ponto,
        anexos = anexos,
        chamador = cliente::chamar,
        auditoria = auditoria,
        relogio = relogio,
        parar = parar,
        aoAvancar = aoAvancar,
    )

    public val reconciliacao: Reconciliacao = Reconciliacao(banco, sessoes, retomada, agendador, configuracoes::chaves, evidencias, anexos, relogio)

    public companion object {
        /** A fábrica do processo, instalada pelo `Application` antes de qualquer worker ou receptor. */
        @Volatile
        public var doProcesso: Fabrica? = null
            private set

        public fun instalar(fabrica: Fabrica) {
            doProcesso = fabrica
        }
    }
}
