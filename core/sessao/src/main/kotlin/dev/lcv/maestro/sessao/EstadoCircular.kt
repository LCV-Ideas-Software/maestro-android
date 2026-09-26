package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.PromptsDaSessao
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.sessao.Agentes.rotulo

/**
 * A custódia circular viva (`CircularReviewState`, `sessions.ts:145-159`):
 * o que o checkpoint grava nas colunas tipadas de [SessaoEntidade] e o que a
 * retomada lê de volta depois de validar.
 */
public data class Custodia(
    val autorAtual: Provedor,
    /** O texto aceito, canônico ([EstadoCircular.textoCanonico]). */
    val textoAtual: String,
    val custodiaArtefatoId: String,
    val artefatoAnteriorId: String,
    val rodada: Int,
    val indiceDoTurno: Int,
    val escala: List<Provedor>,
    val agentesValidos: Set<Provedor>,
    val aprovacoesEstaveis: Set<Provedor>,
    val turnoDoArtefato: Int,
)

/** `CircularResumeProgress`: o que a retomada restaura antes do primeiro turno. */
public data class ProgressoDaRetomada(
    val rodada: Int,
    val indiceDoTurno: Int,
    val agentesValidos: Set<Provedor>,
    val aprovacoesEstaveis: Set<Provedor>,
    val artefatoDeCustodiaId: String,
    val turnoDoArtefato: Int,
    val artefatoAnteriorId: String,
    val relatorios: List<PromptsDaSessao.RelatorioDeTurno>,
)

/**
 * As funções puras da custódia circular (`sessions.ts:3022-3310`). O web
 * guardava a custódia como JSON e a validava com treze verificações; aqui
 * ela está em colunas tipadas, e ficam as verificações que ainda têm
 * significado, cada uma com a mensagem do web quando ela existe. O que
 * toca o banco fica em [Retomada]; aqui entra a linha já carregada e um
 * carregador de artefatos.
 */
public object EstadoCircular {

    /**
     * O texto aceito, canônico: aparado como o `trim` do JavaScript (o que o
     * web hasheava), `\r\n` como `\n`, e sem NUL — um NUL é recusado, não
     * apagado em silêncio, porque o mesmo texto vai para a sessão e para o
     * artefato e a retomada os compara por igualdade. Idempotente.
     */
    public fun textoCanonico(texto: String): String {
        if (texto.contains('\u0000')) throw IntegridadeDeLinks.Falha("Accepted text contains a NUL character.")
        return TrimJs.aparar(texto.replace("\r\n", "\n"))
    }

    /** `["claude","codex"]`: a lista gravada; `null` quando não é uma lista de chaves conhecidas. */
    public fun lerAgentes(json: String): List<Provedor>? {
        val raiz = Json.tolerante(json) ?: return null
        if (!raiz.isArray) return null
        return raiz.map { no -> no.takeIf { it.isTextual }?.let { Agentes.porChave(it.textValue()) } ?: return null }
    }

    public fun agentesJson(agentes: Collection<Provedor>): String = RepositorioDeSessoes.agentesJson(agentes.toList())

    /**
     * `validatePersistedCircularState` (`sessions.ts:3118-3185`) sobre as
     * colunas: contadores e referências, autor, listas, artefato de custódia
     * aceito e do autor, artefato anterior, turnos coerentes, e o texto da
     * linha igual ao texto aceito do artefato. Lança [IntegridadeDeLinks.Falha].
     */
    public fun validar(sessao: SessaoEntidade, carregarArtefato: (id: String) -> ArtefatoEntidade?): Custodia {
        val custodiaId = sessao.custodiaArtefatoId?.takeIf { it.isNotEmpty() }
        val anteriorId = sessao.artefatoAnteriorId?.takeIf { it.isNotEmpty() }
        if (sessao.rodada < 1 || sessao.indiceDoTurno < 0 || sessao.turnoDoArtefato < 1 || custodiaId == null || anteriorId == null) {
            throw IntegridadeDeLinks.Falha("Circular custody progress contains invalid counters or artifact references.")
        }
        val autor = Agentes.porChave(sessao.autorAtual)
            ?: throw IntegridadeDeLinks.Falha("Circular custody state contains an unknown draft author.")
        val escala = lerAgentes(sessao.escalaJson)
        val validos = lerAgentes(sessao.agentesValidosJson)
        val estaveis = lerAgentes(sessao.aprovacoesEstaveisJson)
        if (escala == null || validos == null || estaveis == null) {
            throw IntegridadeDeLinks.Falha("Circular custody progress contains an unknown reviewer.")
        }
        if (escala.toSet().size != escala.size) throw IntegridadeDeLinks.Falha("Circular custody state contains duplicate roster members.")
        if ((validos + estaveis).any { it !in escala }) {
            throw IntegridadeDeLinks.Falha("Circular custody progress references a reviewer outside its roster.")
        }
        val custodia = carregarArtefato(custodiaId)
        val anterior = carregarArtefato(anteriorId)
        if (custodia == null || custodia.sessaoId != sessao.id || !custodiaAceita(custodia)) {
            throw IntegridadeDeLinks.Falha("Circular custody references a missing or rejected artifact.")
        }
        if (anterior == null || anterior.sessaoId != sessao.id) {
            throw IntegridadeDeLinks.Falha("Circular custody chain references a missing previous artifact.")
        }
        if (custodia.agente != autor.agente || custodia.turno > sessao.turnoDoArtefato || anterior.turno > sessao.turnoDoArtefato) {
            throw IntegridadeDeLinks.Falha("Circular custody artifact author or turn does not match persisted state.")
        }
        val texto = textoCanonico(sessao.textoAtual)
        if (texto.isEmpty() || texto != custodia.textoAceito) {
            throw IntegridadeDeLinks.Falha("Circular custody artifact text does not match the session row.")
        }
        return Custodia(
            autorAtual = autor,
            textoAtual = texto,
            custodiaArtefatoId = custodiaId,
            artefatoAnteriorId = anteriorId,
            rodada = sessao.rodada,
            indiceDoTurno = sessao.indiceDoTurno,
            escala = escala,
            agentesValidos = validos.toSet(),
            aprovacoesEstaveis = estaveis.toSet(),
            turnoDoArtefato = sessao.turnoDoArtefato,
        )
    }

    /** `restorePersistedCircularProgress` (`sessions.ts:3187-3220`). */
    public fun restaurar(
        custodia: Custodia,
        escalaAtual: List<Provedor>,
        autorAtual: Provedor,
        relatorios: List<PromptsDaSessao.RelatorioDeTurno>,
    ): ProgressoDaRetomada {
        val mesmaEscala = custodia.escala == escalaAtual
        var rodada = maxOf(1, custodia.rodada)
        var indiceDoTurno = custodia.indiceDoTurno
        var cruzouRodada = false
        while (escalaAtual.isNotEmpty() && indiceDoTurno >= escalaAtual.size) {
            rodada += 1
            indiceDoTurno -= escalaAtual.size
            cruzouRodada = true
        }
        if (!mesmaEscala) indiceDoTurno = 0
        val validos = if (cruzouRodada || !mesmaEscala) emptySet() else custodia.agentesValidos.filter { it in escalaAtual }.toSet()
        val estaveis = custodia.aprovacoesEstaveis.filter { it in escalaAtual && it != autorAtual }.toSet()
        return ProgressoDaRetomada(
            rodada = rodada,
            indiceDoTurno = indiceDoTurno,
            agentesValidos = validos,
            aprovacoesEstaveis = estaveis,
            artefatoDeCustodiaId = custodia.custodiaArtefatoId,
            turnoDoArtefato = custodia.turnoDoArtefato,
            artefatoAnteriorId = custodia.artefatoAnteriorId,
            relatorios = relatorios,
        )
    }

    /** `acceptedCustodyArtifact` (`sessions.ts:3052-3057`). */
    public fun custodiaAceita(artefato: ArtefatoEntidade): Boolean {
        val status = artefato.status.lowercase()
        return (artefato.papel == "draft" || artefato.papel == "revision") && (status == "ready" || status == "not_ready")
    }

    /** `acceptedChainArtifact` (`sessions.ts:3059-3064`). */
    public fun cadeiaAceita(artefato: ArtefatoEntidade): Boolean =
        (artefato.papel == "draft" || artefato.papel == "revision") && artefato.status.lowercase() !in setOf("blocked", "error", "running")

    /** `deliberativeArtifactReport` (`sessions.ts:3066-3077`). */
    public fun relatorioDeliberativo(artefato: ArtefatoEntidade): PromptsDaSessao.RelatorioDeTurno? {
        if (artefato.papel != "revision" || !custodiaAceita(artefato)) return null
        val status = artefato.status.uppercase()
        if (status in PromptsDaSessao.STATUS_NAO_DELIBERATIVOS) return null
        return PromptsDaSessao.RelatorioDeTurno(
            nome = Agentes.sanear(artefato.agente, Provedor.CLAUDE).rotulo,
            papel = "review",
            status = status,
            relatorio = artefato.relatorioDeRevisaoJson.takeIf { it.isNotEmpty() },
            artefato = artefato.id,
        )
    }
}
