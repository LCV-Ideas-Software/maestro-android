package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Portão de integridade bibliográfica. Os dois primeiros casos são os do
 * canônico (`session_orchestration.rs` 4496–4535 em `3c8babc`); os demais
 * fixam o que o porte poderia errar em silêncio.
 */
class IntegridadeBibliograficaTest {

    @Test
    fun `marcador de evidencia pendente e recusado`() {
        val motivo = IntegridadeBibliografica.validarCandidato(
            "Texto publicavel com [EVIDENCIA_PENDENTE] ainda visivel.",
        )

        assertContains(assertNotNull(motivo), "bibliographic integrity")
    }

    @Test
    fun `variantes de lacuna sao recusadas`() {
        for (marcador in listOf(
            "[s. l. : s. n.]",
            "[s.l.:s.n.]",
            "[s.l.]",
            "[s.n.]",
            "[n.d.]",
            "[S.l.: s.n., s.d.]",
            "[200-?]",
            "[19--]",
            "[entre 2010 e 2020]",
            "[sine loco]",
            "[sine nomine]",
            "[sine data]",
        )) {
            assertNotNull(
                IntegridadeBibliografica.validarCandidato("Referencia incompleta $marcador"),
                "o marcador devia ser recusado: $marcador",
            )
        }

        assertNull(
            IntegridadeBibliografica.validarCandidato(
                "Texto regular sobre as dificuldades de verificacao, sem marcador bibliografico.",
            ),
        )
    }

    @Test
    fun `digito que nao e ASCII nao faz data incerta`() {
        // U+0663 é dígito para o `isDigit()` do Kotlin e não é para o
        // `is_ascii_digit` do canônico. Com a API da plataforma, este
        // colchete viraria data incerta.
        assertNull(IntegridadeBibliografica.validarCandidato("Ano [٣?] citado."))
        assertNotNull(IntegridadeBibliografica.validarCandidato("Ano [3?] citado."))
    }

    @Test
    fun `acento e caixa nao escondem a pendencia`() {
        assertNotNull(IntegridadeBibliografica.validarCandidato("[EDIÇÃO CONSULTADA NÃO IDENTIFICADA]"))
        assertNotNull(IntegridadeBibliografica.validarCandidato("Fora de colchete: Evidência Pendente."))
    }

    @Test
    fun `colchete comum nao e lacuna`() {
        assertNull(IntegridadeBibliografica.validarCandidato("Veja [a seção interna](#secao) e [1]."))
        assertNull(IntegridadeBibliografica.validarCandidato("Colchete aberto sem fim [s. d."))
    }
}
