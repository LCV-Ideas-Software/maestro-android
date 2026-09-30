package dev.lcv.maestro.ui.sessoes

import dev.lcv.maestro.R
import dev.lcv.maestro.ui.anexos.AnexosViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A regra do manifesto do formulário de nova sessão: nada começa durante a leitura dele (achado do Codex na #78), nem com um arquivo recusado. */
class ManifestoDoFormularioTest {

    private fun escolhido(leitura: AnexosViewModel.Manifesto) =
        SessoesViewModel.ManifestoEscolhido("citation-manifest.json", "application/json", ByteArray(1), leitura)

    @Test
    fun `durante a leitura nada comeca, com ou sem um manifesto anterior`() {
        assertEquals(R.string.manifesto_em_leitura, SessoesViewModel.motivoDoManifesto(true, null))
        assertEquals(R.string.manifesto_em_leitura, SessoesViewModel.motivoDoManifesto(true, escolhido(AnexosViewModel.Manifesto.Lido(1, 1, false))))
    }

    @Test
    fun `lido segue, recusado ou ausente como manifesto nao segue, e sem manifesto segue`() {
        assertNull(SessoesViewModel.motivoDoManifesto(false, escolhido(AnexosViewModel.Manifesto.Lido(1, 1, false))))
        assertEquals(R.string.erro_manifesto, SessoesViewModel.motivoDoManifesto(false, escolhido(AnexosViewModel.Manifesto.Recusado("motivo"))))
        assertEquals(R.string.erro_manifesto, SessoesViewModel.motivoDoManifesto(false, escolhido(AnexosViewModel.Manifesto.Ausente)))
        assertNull(SessoesViewModel.motivoDoManifesto(false, null))
    }
}
