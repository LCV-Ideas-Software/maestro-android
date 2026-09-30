package dev.lcv.maestro.sessao

import android.app.PendingIntent
import android.content.Context
import androidx.work.WorkManager
import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.AgenteDeColeta
import dev.lcv.maestro.provedores.AnalisadorDeUrlOkHttp
import dev.lcv.maestro.provedores.BuscaDeEvidencias
import dev.lcv.maestro.provedores.ClienteDeProvedores
import dev.lcv.maestro.provedores.ColetorHttp
import dev.lcv.maestro.provedores.ImportacaoDoOperador
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
    private val versaoDoAplicativo: String,
    /** O toque na notificação abre a tela da sessão (o `:app` fornece o `PendingIntent`); `null` deixa a notificação sem destino. */
    abrirSessao: ((sessaoId: String) -> PendingIntent)? = null,
    private val relogio: () -> Instant = Instant::now,
    /** Só os testes trocam: é por aqui que provam que construir a fábrica não inicializa o WorkManager. */
    workManager: () -> WorkManager = contexto.applicationContext.let { aplicativo -> { WorkManager.getInstance(aplicativo) } },
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
    public val links: LinksDaSessao = LinksDaSessao(banco, relogio)

    /**
     * O WorkManager só é tocado no primeiro uso do agendador, nunca durante a
     * construção. Com a inicialização sob demanda, a primeira chamada a
     * `WorkManager.getInstance` inicializa a biblioteca, e a inicialização pode
     * começar na hora um trabalho pendente cuja restrição já está satisfeita;
     * o worker nasce pela `FabricaDeTrabalhos`, que lê [doProcesso] — e, se a
     * fábrica ainda estivesse no meio da construção, sem ter sido instalada, o
     * WorkManager marcaria o trabalho daquela sessão como falho. Preguiçoso, o
     * agendador só existe depois de `instalar`, que é quando alguém o usa.
     */
    public val agendador: Agendador by lazy { Agendador(workManager()) }
    override val notificacao: Notificacao = Notificacao(contexto.applicationContext, abrirSessao)
    private val resolvedor = ResolvedorPublico.dnsDoGoogle()

    /** A captura assistida pelo operador (seção 2.2): a passagem ao navegador e o arquivo importado, sob a mesma regra de rede pública. */
    public val importacao: ImportacaoDoOperador = ImportacaoDoOperador(resolvedor)

    /**
     * O agente de coleta com o e-mail de contato **atual** das configurações
     * (seção 5.4, item 7), montado a cada auditoria e a cada busca: um agente
     * guardado na fábrica congelaria o `mailto` do Crossref no valor do
     * arranque (revisão cruzada de 28/09/2026, emenda A3). Lê o Room: chamar
     * fora da linha principal.
     */
    internal fun agenteDeColeta(): AgenteDeColeta = AgenteDeColeta(versaoDoAplicativo, configuracoes.carregar().emailDeContato)

    /**
     * A busca de evidências (Crossref e OpenAlex) com o e-mail atual, que guarda
     * cada resultado no armazém; um objeto por chamada. Bloqueante: `Dispatchers.IO`.
     */
    public fun buscaDeEvidencias(): BuscaDeEvidencias = BuscaDeEvidencias(resolvedor, agenteDeColeta(), evidencias)

    /** O cliente dos seis provedores: novo, limpo e só TLS moderno, como o transporte da auditoria. */
    private val cliente = ClienteDeProvedores(OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build(), cofre)

    /** O "Testar chaves" da tela de configurações, sobre o mesmo cliente das sessões. */
    public val testeDeChaves: TesteDeChaves = TesteDeChaves(cliente::chamar)

    /**
     * A auditoria de cinco estágios com o motor de links real: um coletor por
     * auditoria, em `Dispatchers.IO`, cancelado (`cancelarTudo`) se a
     * corrotina for cancelada no meio da coleta.
     */
    public val auditoria: AuditoriaDaSessao = AuditoriaDaSessao { sessaoId, texto, citacoes ->
        val coletor = ColetorHttp(resolvedor, withContext(Dispatchers.IO) { agenteDeColeta() }, evidencias)
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
        artefatos = artefatos,
        anexos = anexos,
        chamador = cliente::chamar,
        auditoria = auditoria,
        relogio = relogio,
        parar = parar,
        aoAvancar = aoAvancar,
    )

    /** Preguiçosa pela mesma razão do [agendador], que ela recebe. */
    public val reconciliacao: Reconciliacao by lazy {
        Reconciliacao(banco, sessoes, retomada, agendador, configuracoes::chaves, evidencias, anexos, relogio)
    }

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
