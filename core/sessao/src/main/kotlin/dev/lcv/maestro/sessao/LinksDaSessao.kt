package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.LinhaDeLink
import java.time.Instant

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
}
