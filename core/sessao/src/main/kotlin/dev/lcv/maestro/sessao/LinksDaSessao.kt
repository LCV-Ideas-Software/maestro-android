package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.LinhaDeLink
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Os links que a sessão tem agora, para a tela de revisão (especificação,
 * seção 2.2; MAEANDR-18). O registro de links é global e não sabe de sessão:
 * cada linha guarda a impressão do texto em que foi auditada
 * (`impressaoDaOrigem`, o SHA-256 do texto inteiro, como `auditar` a calcula),
 * e a sessão audita o texto que ela grava na linha. As linhas da sessão são,
 * então, as da impressão do texto dela — o final, se convergiu; senão o atual,
 * que é o que a próxima auditoria avaliará. Linhas de versões anteriores do
 * texto ficam de fora.
 */
public class LinksDaSessao(private val banco: BancoDaSessao, private val relogio: () -> Instant) {

    /** O registro sobre o qual a tela revisa e propõe correções, anotado em nome da sessão. */
    public fun registro(sessaoId: String): RegistroDeLinksRoom = RegistroDeLinksRoom(banco, sessaoId, relogio)

    /**
     * O [registro] da tela de revisão, que recusa gravar a linha cujo texto de origem é o de uma sessão na
     * fila ou em execução — a desta ou a de outra: o registro é global, e duas sessões com o mesmo texto
     * dividem as linhas (decisão 24 do operador; achado do Codex na #78). A recusa vem da transação da
     * gravação, e o diário (`anotar`) só é gravado depois de a linha ser.
     */
    public fun registroDaTela(sessaoId: String): IntegridadeDeLinks.RegistroDeLinks {
        val base = registro(sessaoId)
        return object : IntegridadeDeLinks.RegistroDeLinks by base {
            override fun salvar(linha: LinhaDeLink) {
                if (emAuditoria(linha.impressaoDaOrigem)) throw IntegridadeDeLinks.Falha(MENSAGEM_EM_AUDITORIA)
                base.salvar(linha)
            }
        }
    }

    private fun emAuditoria(impressao: String): Boolean =
        banco.sessoes().emExecucao().any { FormatoDoRegistro.sha256(it.textoFinal ?: it.textoAtual) == impressao }

    /**
     * As linhas do texto da sessão, na ordem da listagem do motor (`IntegridadeDeLinks.listar`): a
     * verificação mais recente primeiro, depois o id. Uma leitura só do registro — a listagem do motor
     * relê e reordena o registro inteiro a cada página, e o registro só cresce. Os dois campos são
     * ASCII (RFC 3339 e hexadecimal), e neles a comparação do Kotlin é a ordem de bytes do Rust.
     * Bloqueante.
     */
    public fun linhas(sessaoId: String): List<LinhaDeLink> {
        val sessao = banco.sessoes().carregar(sessaoId) ?: return emptyList()
        val impressao = FormatoDoRegistro.sha256(sessao.textoFinal ?: sessao.textoAtual)
        return registro(sessaoId).todos()
            .filter { it.impressaoDaOrigem == impressao }
            .sortedWith(compareByDescending<LinhaDeLink> { it.verificadoEm }.thenBy { it.linkId })
    }

    /**
     * Cada gravação nas tabelas que a tela de links lê (as linhas de link e as evidências), com uma
     * emissão inicial: a auditoria da sessão regrava as linhas do mesmo texto, e a tela não veria
     * isso só pela mudança do texto (achado do Codex na #78).
     */
    public fun mudancas(): Flow<Set<String>> = banco.invalidationTracker.createFlow("links", "evidencias")

    public companion object {
        /** Só do aparelho: a linha é do texto de uma sessão que a auditoria dela ainda regrava. */
        public const val MENSAGEM_EM_AUDITORIA: String =
            "uma sessão com este mesmo texto está na fila ou em execução; a revisão e as propostas esperam ela parar"
    }
}
