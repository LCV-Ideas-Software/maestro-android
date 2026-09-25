package dev.lcv.maestro.provedores

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDoRobots
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.MetodoHttp
import dev.lcv.maestro.protocolo.ModoDeAcesso
import dev.lcv.maestro.protocolo.RedePublica
import dev.lcv.maestro.protocolo.RegistroDeEvidencia
import java.time.Duration
import java.time.Instant
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * `search_web_evidence_inner` (`web_evidence.rs:1616-1776`), o
 * [IntegridadeDeLinks.BuscadorDeEvidencia] da auditoria: a busca
 * bibliográfica nas APIs oficiais do Crossref e do OpenAlex, sem chave.
 * Só os dois conectores embutidos do canônico (`:1426-1455`); o arquivo de
 * conectores configuráveis não é portado (especificação, seção 11).
 *
 * O e-mail de contato do usuário, se ele o informou, vai só ao Crossref —
 * no parâmetro `mailto` e na variante polida do `User-Agent`, que o Crossref
 * documenta como "polite pool" — e como cabeçalho da origem: um
 * redirecionamento para outra origem é recusado, e o e-mail não a
 * atravessa. O OpenAlex recebe o agente base, sem e-mail.
 */
public class BuscaDeEvidencias internal constructor(
    clienteBase: OkHttpClient,
    dns: Dns,
    politica: UrlPublica.PoliticaDeRede,
    private val agente: AgenteDeColeta,
    private val relogio: () -> Instant,
    /** O endpoint de cada conector; os testes o apontam para o servidor falso. */
    private val endpointDe: (Conector) -> String = { it.endpoint },
    /** O que mais cancelar em [cancelarTudo]: em produção, as consultas DoH do resolvedor. */
    private val cancelador: () -> Unit = {},
) : IntegridadeDeLinks.BuscadorDeEvidencia {

    public constructor(resolvedor: ResolvedorPublico, agente: AgenteDeColeta) : this(
        TransportePublico.clienteLimpo(),
        resolvedor,
        { RedePublica.motivoDeRecusa(it, AnalisadorDeUrlOkHttp, resolvedor) },
        agente,
        Instant::now,
        cancelador = resolvedor::cancelar,
    )

    private val transporte = TransportePublico(clienteBase, dns, politica, agente)
    private val politica = transporte.politicaGuardada()

    /** `built_in_search_connectors`. */
    internal enum class Conector(
        val id: String,
        val rotulo: String,
        val endpoint: String,
        val parametroDeConsulta: String,
        val parametroDeLimite: String,
        val caminhoDosResultados: String,
        val campoDoTitulo: String,
        val campoDaUrl: String,
        val campoDoTrecho: String,
    ) {
        CROSSREF(
            "crossref", "Crossref", "https://api.crossref.org/works", "query.bibliographic", "rows",
            "message.items", "title", "URL", "publisher",
        ),
        OPENALEX(
            "openalex", "OpenAlex", "https://api.openalex.org/works", "search", "per-page",
            "results", "display_name", "id", "doi",
        ),
    }

    override fun buscar(consulta: String, provedor: String, limite: Int): List<RegistroDeEvidencia> {
        val consultaSaneada = Erros.sanear(consulta.trim(), 500)
        if (consultaSaneada.isEmpty()) throw IntegridadeDeLinks.Falha("web evidence search query cannot be empty")
        val idDoProvedor = curto(provedor.trim(), 80)
        if (!identificadorValido(idDoProvedor)) throw IntegridadeDeLinks.Falha("invalid web evidence search provider")
        val limiteEfetivo = limite.coerceIn(1, MAX_RESULTADOS)
        val conector = Conector.entries.firstOrNull { it.id == idDoProvedor } ?: throw IntegridadeDeLinks.Falha(
            "unknown web evidence search provider '$idDoProvedor'; available built-ins are crossref and openalex",
        )

        val construtor = UrlPublica.validar(endpointDe(conector), politica).newBuilder()
            .addQueryParameter(conector.parametroDeConsulta, consultaSaneada)
            .addQueryParameter(conector.parametroDeLimite, limiteEfetivo.toString())
        val cabecalhos = linkedMapOf<String, String>()
        val email = agente.emailDeContato
        if (conector == Conector.CROSSREF && email != null) {
            construtor.addQueryParameter("mailto", email)
            cabecalhos["User-Agent"] = agente.userAgentPolido
        }
        val bruta = transporte.executar(MetodoHttp.GET, construtor.build(), cabecalhos, ColetorHttp.TETO_DO_CORPO_BYTES)
        if (bruta.status !in 200..299) {
            throw IntegridadeDeLinks.Falha("search provider '${conector.id}' returned HTTP ${bruta.status}")
        }
        // `readTree` de conteúdo vazio não lança: devolve nó ausente (ou nulo,
        // conforme a versão). O canônico (`serde_json`) recusa como JSON inválido.
        val raiz: JsonNode? = try {
            Json.LEITOR.readTree(bruta.corpo)
        } catch (erro: JacksonException) {
            throw IntegridadeDeLinks.Falha(
                Erros.sanear("search provider '${conector.id}' returned invalid JSON: ${erro.message}", 500),
            )
        }
        if (raiz == null || raiz.isMissingNode) {
            throw IntegridadeDeLinks.Falha("search provider '${conector.id}' returned invalid JSON: empty response body")
        }
        val resultados = noEm(raiz, conector.caminhoDosResultados)?.takeIf { it.isArray }
            ?: throw IntegridadeDeLinks.Falha(
                "search provider '${conector.id}' response did not contain array '${conector.caminhoDosResultados}'",
            )

        val agora = relogio()
        val agoraTexto = FormatoDoRegistro.rfc3339(agora)
        val registros = mutableListOf<RegistroDeEvidencia>()
        for (item in resultados.take(limiteEfetivo)) {
            val urlDoResultado = textoDoCampo(item, conector.campoDaUrl) ?: continue
            val urlValidada = try {
                UrlPublica.validar(urlDoResultado, politica).toString()
            } catch (erro: IntegridadeDeLinks.Falha) {
                continue
            }
            val id = FormatoDoRegistro.sha256("official_api|${conector.id}|$consultaSaneada|$urlValidada")
            val bytesDoItem = Json.LEITOR.writeValueAsBytes(item)
            val notas = mutableListOf(
                "Metadata returned by the ${Erros.sanear(conector.rotulo, 120)} official/configured API; " +
                    "target page was not fetched",
            )
            textoDoCampo(item, conector.campoDoTrecho)?.let { notas += Erros.sanear(it, 500) }
            registros += RegistroDeEvidencia(
                id = id,
                versaoDoEsquema = ColetorHttp.VERSAO_DO_ESQUEMA,
                estado = EstadoDaEvidencia.PRONTA,
                url = Erros.sanear(urlValidada, 2_048),
                metodo = MetodoHttp.GET,
                modoDeAcesso = ModoDeAcesso.API_OFICIAL,
                status = bruta.status,
                urlFinal = urlValidada,
                titulo = textoDoCampo(item, conector.campoDoTitulo)?.let { Erros.sanear(it, 240) },
                tipoDeConteudo = "application/json",
                sha256 = FormatoDoRegistro.sha256(bytesDoItem),
                coletadaEm = agoraTexto,
                expiraEm = FormatoDoRegistro.rfc3339(agora.plus(Duration.ofDays(ColetorHttp.DIAS_DE_CACHE))),
                validadeDoCache = ColetorHttp.VALIDADE_DO_CACHE,
                estadoDoCache = EstadoDoCache.FRESCO,
                estadoDoRobots = EstadoDoRobots.NAO_SE_APLICA,
                estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
                estadoDeInteracao = EstadoDeInteracao.NENHUMA,
                resolvidaPorPessoa = false,
                bytes = bytesDoItem.size.toLong(),
                duracaoMs = bruta.duracaoMs,
                cadeiaDeRedirecionamento = bruta.redirecionamentos,
                comandoCurl = null,
                provedor = conector.id,
                consulta = consultaSaneada,
                nomeDoArtefato = null,
                notas = notas,
                criadaEm = agoraTexto,
                atualizadaEm = agoraTexto,
            )
        }
        return registros
    }

    /** Cancela toda busca em curso — transporte e consultas DoH — e fecha esta busca ([ColetaCancelada] daí em diante). */
    public fun cancelarTudo() {
        transporte.cancelarTudo()
        cancelador()
    }

    /** `json_value_at_path`: segmentos separados por ponto, vazios ignorados. */
    private fun noEm(raiz: JsonNode, caminho: String): JsonNode? {
        var atual: JsonNode = raiz
        for (segmento in caminho.split('.').filter { it.isNotEmpty() }) {
            atual = atual.get(segmento) ?: return null
        }
        return atual
    }

    /** `json_field_text`: texto, o primeiro texto de uma lista, ou um número. */
    private fun textoDoCampo(item: JsonNode, campo: String): String? {
        val no = noEm(item, campo) ?: return null
        return when {
            no.isTextual -> no.textValue()
            no.isArray -> no.firstOrNull { it.isTextual }?.textValue()
            no.isNumber -> no.numberValue().toString()
            else -> null
        }
    }

    /** `sanitize_short`. */
    private fun curto(valor: String, limite: Int): String =
        Erros.sanear(valor, limite).filter {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' || it == ':'
        }

    /** `valid_connector_identifier`. */
    private fun identificadorValido(valor: String): Boolean =
        valor.isNotEmpty() && valor.length <= 80 &&
            valor.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    public companion object {
        /** `MAX_SEARCH_RESULTS`. */
        public const val MAX_RESULTADOS: Int = 20
    }
}
