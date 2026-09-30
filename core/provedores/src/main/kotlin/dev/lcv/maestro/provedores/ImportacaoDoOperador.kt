package dev.lcv.maestro.provedores

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.lcv.maestro.protocolo.EspacoUnicode
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
import java.util.Locale

/**
 * A captura assistida pelo operador (especificação, seção 2.2; plano do
 * `:app`, emenda A7), como o desktop a faz: o registro de passagem ao
 * navegador do sistema e o registro do arquivo que o operador salvou e
 * importou. Porte de `handoff_record` (`web_evidence.rs` 2357–2392), de
 * `open_web_evidence_in_default_browser` (2505–2533) e de
 * `import_operator_evidence` com as suas regras (2536–2680), em `maestro-app`
 * `0e17817`.
 *
 * Nenhum dos dois registros toca a linha de link — no desktop também não: a
 * linha segue o motor, e só a revisão explícita a libera. O registro fica no
 * armazém de evidências, para o operador julgar o link com ele à vista.
 */
public class ImportacaoDoOperador internal constructor(
    private val politica: UrlPublica.PoliticaDeRede,
    private val relogio: () -> Instant,
) {

    public constructor(resolvedor: ResolvedorPublico) : this(
        { RedePublica.motivoDeRecusa(it, AnalisadorDeUrlOkHttp, resolvedor) },
        Instant::now,
    )

    /** `WebEvidenceImportRequest`, sem o base64: os bytes chegam do seletor de documentos. */
    public class Pedido(
        public val nome: String,
        public val tipoDeMidia: String,
        public val bytes: ByteArray,
        public val url: String? = null,
        public val notas: List<String> = emptyList(),
    )

    public sealed interface Importacao {
        /** O registro e os bytes, para `ArmazemDeEvidencias.guardar`. */
        public class Importada(public val coleta: ColetorHttp.Coleta) : Importacao

        /** A mensagem do canônico. */
        public data class Recusada(val motivo: String) : Importacao
    }

    /**
     * `handoff_record`: a URL validada como pública, o estado que pede ação
     * do operador e nenhum conteúdo — antes de abrir o navegador, que só
     * recebe a URL validada ([RegistroDeEvidencia.url]). [existente] devolve
     * o registro anterior de um id, cuja data de criação se mantém. Lança
     * [IntegridadeDeLinks.Falha] com a mensagem do canônico se a URL não
     * servir.
     */
    public fun passagem(url: String, existente: (String) -> RegistroDeEvidencia?): ColetorHttp.Coleta {
        val validada = UrlPublica.validar(url, politica).toString()
        val id = idDaPassagem(validada)
        val agora = relogio()
        val registro = base(id, validada, agora).copy(
            criadaEm = existente(id)?.criadaEm ?: FormatoDoRegistro.rfc3339(agora),
            estado = EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR,
            urlFinal = validada,
            estadoDeInteracao = EstadoDeInteracao.NENHUMA,
            notas = listOf(NOTA_DA_PASSAGEM, NOTA_SEM_PERFIL),
        )
        return ColetorHttp.Coleta(registro, emptyMap(), null)
    }

    /**
     * O que `open_web_evidence_in_default_browser` anota depois do disparo:
     * [falhaAoAbrir] é o erro do navegador, ou `null` quando ele abriu. É
     * este o registro que se guarda.
     */
    public fun aberta(passagem: ColetorHttp.Coleta, falhaAoAbrir: String?): ColetorHttp.Coleta {
        val registro = passagem.registro.copy(
            notas = passagem.registro.notas + (falhaAoAbrir?.let { Erros.sanear(it, 500) } ?: NOTA_DO_NAVEGADOR_ABERTO),
            atualizadaEm = FormatoDoRegistro.rfc3339(relogio()),
        )
        return ColetorHttp.Coleta(registro, passagem.cabecalhos, passagem.corpo)
    }

    /**
     * `import_operator_evidence`: as regras do canônico, na ordem dele. [existente]
     * devolve o registro anterior de um id, cuja data de criação se mantém.
     */
    public fun importar(pedido: Pedido, existente: (String) -> RegistroDeEvidencia?): Importacao {
        if (!nomeValido(pedido.nome)) return Importacao.Recusada("operator artifact name is invalid or contains a path")
        val tipo = tipoPermitido(pedido.tipoDeMidia) ?: return Importacao.Recusada("operator artifact media type is not allowlisted")
        if (pedido.bytes.isEmpty() || pedido.bytes.size > MAX_BYTES) {
            return Importacao.Recusada("operator artifact must contain 1..=$MAX_BYTES decoded bytes")
        }
        if (!bytesDoTipo(tipo, pedido.bytes)) return Importacao.Recusada("operator artifact bytes do not match the declared media type")
        val urlDeOrigem = pedido.url?.let(EspacoUnicode::aparar)?.takeIf { it.isNotEmpty() }?.let { bruta ->
            try {
                UrlPublica.validar(bruta, politica).toString()
            } catch (erro: IntegridadeDeLinks.Falha) {
                return Importacao.Recusada(erro.message.orEmpty())
            }
        }
        val resumo = FormatoDoRegistro.sha256(pedido.bytes)
        val id = FormatoDoRegistro.sha256("operator_import|${urlDeOrigem.orEmpty()}|${pedido.nome}|$resumo")
        val agora = relogio()
        val agoraTexto = FormatoDoRegistro.rfc3339(agora)
        val registro = base(id, urlDeOrigem ?: "maestro://operator-evidence/$id", agora).copy(
            criadaEm = existente(id)?.criadaEm ?: agoraTexto,
            estado = EstadoDaEvidencia.PRONTA,
            urlFinal = urlDeOrigem,
            tipoDeConteudo = tipo,
            sha256 = resumo,
            coletadaEm = agoraTexto,
            expiraEm = FormatoDoRegistro.rfc3339(agora.plus(Duration.ofDays(ColetorHttp.DIAS_DE_CACHE))),
            estadoDoCache = EstadoDoCache.FRESCO,
            estadoDosDireitos = EstadoDosDireitos.FORNECIDO_PELO_OPERADOR,
            estadoDeInteracao = EstadoDeInteracao.RESOLVIDA_POR_PESSOA,
            resolvidaPorPessoa = true,
            bytes = pedido.bytes.size.toLong(),
            nomeDoArtefato = Erros.sanear(EspacoUnicode.aparar(pedido.nome), 180),
            notas = pedido.notas.take(20).map { Erros.sanear(it, 500) }.filter { it.isNotEmpty() } + NOTA_DO_ARTEFATO,
        )
        return Importacao.Importada(ColetorHttp.Coleta(registro, emptyMap(), pedido.bytes))
    }

    /** `base_record` com `OperatorAssistedBrowserCapture` e `GET`. */
    private fun base(id: String, url: String, agora: Instant): RegistroDeEvidencia {
        val agoraTexto = FormatoDoRegistro.rfc3339(agora)
        return RegistroDeEvidencia(
            id = id,
            versaoDoEsquema = ColetorHttp.VERSAO_DO_ESQUEMA,
            estado = EstadoDaEvidencia.COLETANDO,
            url = url,
            metodo = MetodoHttp.GET,
            modoDeAcesso = ModoDeAcesso.CAPTURA_ASSISTIDA_PELO_OPERADOR,
            status = null,
            urlFinal = null,
            titulo = null,
            tipoDeConteudo = null,
            sha256 = null,
            coletadaEm = null,
            expiraEm = null,
            validadeDoCache = ColetorHttp.VALIDADE_DO_CACHE,
            estadoDoCache = EstadoDoCache.AUSENTE,
            estadoDoRobots = EstadoDoRobots.NAO_SE_APLICA,
            estadoDosDireitos = EstadoDosDireitos.DESCONHECIDO,
            estadoDeInteracao = EstadoDeInteracao.NENHUMA,
            resolvidaPorPessoa = false,
            bytes = null,
            duracaoMs = null,
            cadeiaDeRedirecionamento = emptyList(),
            comandoCurl = null,
            provedor = null,
            consulta = null,
            nomeDoArtefato = null,
            notas = emptyList(),
            criadaEm = agoraTexto,
            atualizadaEm = agoraTexto,
        )
    }

    public companion object {
        /** `MAX_OPERATOR_ARTIFACT_BYTES`: 16 MiB, o mesmo teto dos anexos da sessão. */
        public const val MAX_BYTES: Int = 16 * 1024 * 1024

        internal const val NOTA_DA_PASSAGEM =
            "Explicit operator-assisted handoff to the system default browser; Maestro does not read that browser profile"
        internal const val NOTA_SEM_PERFIL =
            "No browser cookies, active profile, password store, or captured page content is imported automatically"
        internal const val NOTA_DO_NAVEGADOR_ABERTO = "Default-browser handoff launched"
        internal const val NOTA_DO_ARTEFATO = "Artifact explicitly supplied by the operator; no browser profile was read"

        /** O id do registro de passagem de [urlValidada]: o `{:?}` do Rust escreve o nome da variante do enum, não o nome serde. */
        public fun idDaPassagem(urlValidada: String): String =
            FormatoDoRegistro.sha256("browser_handoff|OperatorAssistedBrowserCapture|$urlValidada")

        /** `valid_artifact_name`: o nome aparado, até 180 bytes, só ASCII alfanumérico, espaço ASCII e `_-.()`. */
        internal fun nomeValido(nome: String): Boolean {
            val aparado = EspacoUnicode.aparar(nome)
            return aparado.isNotEmpty() &&
                aparado.toByteArray(Charsets.UTF_8).size <= 180 &&
                aparado != "." &&
                aparado != ".." &&
                aparado.none { it == '/' || it == '\\' || it == ':' } &&
                aparado.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ESPACOS_ASCII || it in "_-.()" }
        }

        /** `normalized_import_media_type`: o tipo antes do `;`, aparado e em minúsculas, contra a lista do canônico. */
        internal fun tipoPermitido(tipo: String): String? =
            when (EspacoUnicode.aparar(tipo.substringBefore(';')).lowercase(Locale.ROOT)) {
                "text/plain" -> "text/plain"
                "text/html" -> "text/html"
                "text/markdown", "text/x-markdown" -> "text/markdown"
                "application/pdf" -> "application/pdf"
                "application/json" -> "application/json"
                "image/png" -> "image/png"
                "image/jpeg" -> "image/jpeg"
                "image/webp" -> "image/webp"
                else -> null
            }

        /** `validate_import_magic`: os bytes do começo que cada tipo exige; JSON tem de ser um documento inteiro. */
        internal fun bytesDoTipo(tipo: String, bytes: ByteArray): Boolean = when (tipo) {
            "application/pdf" -> comecaCom(bytes, byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)) // %PDF-
            "image/png" -> comecaCom(bytes, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            "image/jpeg" -> comecaCom(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            "image/webp" -> bytes.size >= 12 && comecaCom(bytes, "RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
            // Só espaço vira `MissingNode` no Jackson; no `serde_json` é fim de arquivo, recusado.
            "application/json" -> try {
                LEITOR_DE_JSON.readTree(bytes)?.isMissingNode == false
            } catch (erro: JacksonException) {
                false
            }
            else -> true
        }

        private fun comecaCom(bytes: ByteArray, prefixo: ByteArray): Boolean =
            bytes.size >= prefixo.size && bytes.copyOfRange(0, prefixo.size).contentEquals(prefixo)

        /** `char::is_ascii_whitespace`: espaço, tab, LF, FF e CR (o VT fica de fora, como no Rust). */
        private const val ESPACOS_ASCII = " \t\n\u000C\r"

        /** `serde_json::from_slice::<Value>`: um documento só, sem nada depois. */
        private val LEITOR_DE_JSON: JsonMapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
    }
}
