package dev.lcv.maestro.seguranca

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lcv.maestro.protocolo.AuditoriaAbnt
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.ManifestoDeCitacoes
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import dev.lcv.maestro.protocolo.ResultadoAbnt
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * As expressões regulares da auditoria do candidato final (`:core:protocolo`,
 * MAEANDR-18), rodando **no aparelho**.
 *
 * O `:core:protocolo` é Kotlin puro e seus testes rodam na JVM. Mas no Android
 * o `java.util.regex` é a ICU4C, e não a implementação do OpenJDK: as classes
 * `\s`, `\d`, `\w` e `\b` mudam de sentido, e a flag `UNICODE_CHARACTER_CLASS`
 * lança exceção. O porte evita tudo isso com classes explícitas, e só o
 * aparelho prova que a ICU as lê como a JVM. Um padrão que ela recusasse
 * derrubaria a inicialização do objeto na primeira auditoria final.
 *
 * O mesmo vale para o decodificador de UTF-8 dos anexos: o do Android não é o
 * do OpenJDK, e o manifesto só pode ser lido se for UTF-8 válido.
 *
 * Mora aqui porque este é o único módulo com emulador na CI (Gradle Managed
 * Devices, verificação obrigatória). Cada caso repete, pela API pública, um
 * caso que o `:core:protocolo` já prova na JVM.
 */
@RunWith(AndroidJUnit4::class)
class ProtocoloNoAparelhoTest {

    private val agora = Instant.parse("2026-09-24T12:00:00Z")

    private fun auditar(texto: String, manifesto: ManifestoDeCitacoes? = null): ResultadoAbnt {
        val saida = AuditoriaAbnt.auditar(texto, manifesto?.hashDoProtocolo, manifesto, null, agora)
        return (saida as AuditoriaAbnt.Saida.Concluida).resultado
    }

    private fun ResultadoAbnt.temBloqueio(codigo: String) = bloqueios.any { it.codigo == codigo }

    @Test
    fun citacaoComEspacoUnicodeEDetectada() {
        // O `\s` do Rust inclui o NBSP.
        assertTrue(auditar("Ver (Silva,\u00A02020).").temBloqueio("structured_manifest_missing"))
    }

    @Test
    fun anoComDigitoNaoAsciiEDetectado() {
        // O `\d` do Rust é `\p{Nd}`.
        assertTrue(auditar("Ver (Silva, 19٣٣).").temBloqueio("structured_manifest_missing"))
    }

    @Test
    fun fronteiraDePalavraSegueORust() {
        val vazio = AuditoriaAbnt.manifestoVazio("h")
        // O ZWJ é caractere de palavra no `\w` do Rust: "apud" colado a ele
        // não termina em fronteira.
        assertFalse(auditar("Texto apud\u200D algo.", vazio).temBloqueio("unstructured_citation_signal"))
        assertTrue(auditar("Texto apud algo.", vazio).temBloqueio("unstructured_citation_signal"))
    }

    @Test
    fun retornoDeCarroSozinhoNaoTerminaLinha() {
        val resultado = auditar("Texto (Silva, 2020).\n\n## Referencias\rSILVA, Ana. Obra. Rio: Ed, 2020.")
        assertTrue(resultado.temBloqueio("reference_section_missing"))
    }

    @Test
    fun caixaDoCabecalhoEUnicode() {
        val resultado = auditar("Texto (Silva, 2020).\n\n## REFERÊNCIAS\nSILVA, Ana. Obra. Rio: Editora, 2020.")
        assertFalse(resultado.temBloqueio("reference_section_missing"))
    }

    @Test
    fun localizadorDeCitacaoDiretaEReconhecido() {
        val citacao = "“Esta e uma citacao direta suficientemente longa”"
        assertFalse(auditar("$citacao (Silva, 2020, p. 12).").temBloqueio("direct_quote_locator_missing"))
        assertTrue(auditar("$citacao (Silva, 2020).").temBloqueio("direct_quote_locator_missing"))
    }

    @Test
    fun linksDeMarkdownHtmlESoltosSaoContados() {
        val texto = "[a](https://example.com/a) e <a href=\"https://example.com/b\">b</a> e https://example.com/c"
        assertEquals(3, IntegridadeDeLinks.contarOcorrencias(texto))
    }

    @Test
    fun manifestoSoELidoEmUtf8Valido() {
        fun extrair(bytes: ByteArray) = ManifestosDosAnexos.extrair(
            listOf(ManifestosDosAnexos.Anexo("citation-manifest.json", "application/json") { bytes }),
        )
        val (antes, depois) = "{\"schema_version\":\"citation_manifest.v1\",\"protocol_hash\":\"h|x\"}"
            .split("|")
            .map { it.toByteArray(Charsets.UTF_8) }
        val recusa = ManifestosDosAnexos.Saida.Recusados("citation manifest attachment is not valid JSON")
        // Surrogate codificado em UTF-8 (CESU-8) dentro do texto.
        val surrogate = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
        assertEquals(recusa, extrair(antes + surrogate + depois))
        assertEquals(recusa, extrair((antes + depois).toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16LE)))
        // Controle: o mesmo manifesto em UTF-8 é lido.
        assertTrue(extrair(antes + depois) is ManifestosDosAnexos.Saida.Lidos)
    }
}
