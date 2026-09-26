package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import java.time.Instant

/**
 * O diretório de registros de link do canônico (um JSON por `link_id` mais o
 * `events.ndjson`, sob trava de processo) como tabelas do Room. Os registros
 * são globais (emenda A11): [carregar] e [todos] leem tudo o que está
 * gravado, como o diretório; [sessaoId] só anota quem gravou.
 *
 * [emTransacao] é `runInTransaction`: exclusão mútua e atomicidade juntas,
 * para a auditoria (bloqueante, em `Dispatchers.IO`).
 */
public class RegistroDeLinksRoom(
    private val banco: BancoDaSessao,
    private val sessaoId: String?,
    private val relogio: () -> Instant,
) : IntegridadeDeLinks.RegistroDeLinks {

    override fun <T> emTransacao(bloco: () -> T): T = banco.runInTransaction<T>(bloco)

    /** O registro gravado, ou `null` se não existe **ou não é válido** (JSON malformado, campo faltando, esquema estranho). */
    override fun carregar(linkId: String): LinhaDeLink? =
        banco.links().carregar(linkId)?.let(::lerValida)

    override fun salvar(linha: LinhaDeLink) {
        banco.links().gravar(
            LinhaDeLinkEntidade(
                linkId = linha.linkId,
                sessaoId = sessaoId,
                linhaJson = FormatoDeLinks.serializarLinha(linha),
                atualizadaEm = FormatoDeInstante.iso(relogio()),
            ),
        )
    }

    override fun anotar(tipo: String, linha: LinhaDeLink) {
        banco.links().anotar(
            EventoDeLinkEntidade(
                sessaoId = sessaoId,
                tipo = tipo,
                linkId = linha.linkId,
                linhaJson = FormatoDeLinks.serializarLinha(linha),
                em = FormatoDeInstante.iso(relogio()),
            ),
        )
    }

    override fun todos(): List<LinhaDeLink> = banco.links().todos().mapNotNull(::lerValida)

    /** O diário de um link, para a tela: os eventos na ordem em que foram anotados. */
    public fun eventosDe(linkId: String): List<EventoDeLinkEntidade> = banco.links().eventosDe(linkId)

    private fun lerValida(linha: LinhaDeLinkEntidade): LinhaDeLink? =
        FormatoDeLinks.lerLinha(linha.linhaJson)?.takeIf { it.versaoDoEsquema == ESQUEMA }

    public companion object {
        /** `link_evidence.v1`: a versão que o motor grava; outra versão é "não válido". */
        public const val ESQUEMA: String = "link_evidence.v1"
    }
}
