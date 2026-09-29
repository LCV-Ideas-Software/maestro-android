package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.EstadoDaEvidencia
import dev.lcv.maestro.protocolo.EstadoDeInteracao
import dev.lcv.maestro.protocolo.EstadoDoCache
import dev.lcv.maestro.protocolo.EstadoDosDireitos
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.protocolo.ModoDeAcesso
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A captura assistida pelo operador (`web_evidence.rs` em `maestro-app`
 * `0e17817`). Os dois primeiros casos são os testes do canônico
 * (`import_magic_rejects_mime_mismatch`, 4535–4540, e
 * `evidence_ids_are_path_safe_sha256_values`, 4560–4566); os demais fixam, uma
 * a uma, as regras de `valid_artifact_name` (2554–2572),
 * `normalized_import_media_type` (2536–2552), `import_operator_evidence`
 * (2589–2680) e `handoff_record` (2357–2392), que o canônico não testa.
 */
class ImportacaoDoOperadorTest {

    private val agora = Instant.parse("2026-09-29T12:00:00Z")

    /** A regra de rede pública chega por aqui: recusa o que tiver `privado` no host, como uma rede interna. */
    private val importacao = ImportacaoDoOperador(
        { url -> if ("privado" in url) "private or local network targets are blocked" else null },
        { agora },
    )

    private fun pedido(nome: String = "pagina-salva (1).txt", tipo: String = "text/plain", bytes: ByteArray = "abc".toByteArray(), url: String? = null) =
        ImportacaoDoOperador.Pedido(nome, tipo, bytes, url)

    private fun recusa(pedido: ImportacaoDoOperador.Pedido): String =
        assertIs<ImportacaoDoOperador.Importacao.Recusada>(importacao.importar(pedido) { null }).motivo

    @Test
    fun `os bytes magicos recusam o tipo que nao corresponde`() {
        assertFalse(ImportacaoDoOperador.bytesDoTipo("application/pdf", "not-a-pdf".toByteArray()))
        assertTrue(ImportacaoDoOperador.bytesDoTipo("application/pdf", "%PDF-1.7\n".toByteArray()))
        assertTrue(ImportacaoDoOperador.bytesDoTipo("image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "rest".toByteArray()))
        // Os outros ramos de `validate_import_magic`, da mesma tabela.
        assertTrue(ImportacaoDoOperador.bytesDoTipo("image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)))
        assertTrue(ImportacaoDoOperador.bytesDoTipo("image/webp", "RIFF____WEBPVP8 ".toByteArray()))
        assertFalse(ImportacaoDoOperador.bytesDoTipo("image/webp", "RIFF____WAVE".toByteArray()))
        assertTrue(ImportacaoDoOperador.bytesDoTipo("application/json", "{\"a\": [1, 2]}".toByteArray()))
        assertFalse(ImportacaoDoOperador.bytesDoTipo("application/json", "{\"a\": 1} lixo".toByteArray()))
        assertFalse(ImportacaoDoOperador.bytesDoTipo("application/json", "   ".toByteArray()))
        assertTrue(ImportacaoDoOperador.bytesDoTipo("text/html", "qualquer coisa".toByteArray()))
    }

    @Test
    fun `os ids sao sha256 em hexadecimal, seguros como nome de arquivo`() {
        val id = ImportacaoDoOperador.idDaPassagem("https://exemplo.org/a")
        assertEquals(64, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        // O `{:?}` do Rust escreve o nome da variante, não o nome serde.
        assertEquals(FormatoDoRegistro.sha256("browser_handoff|OperatorAssistedBrowserCapture|https://exemplo.org/a"), id)
    }

    @Test
    fun `o nome do artefato e aparado, curto, ascii e sem caminho`() {
        assertTrue(ImportacaoDoOperador.nomeValido("  pagina-salva (1).pdf "))
        assertTrue(ImportacaoDoOperador.nomeValido("a".repeat(180)))
        assertFalse(ImportacaoDoOperador.nomeValido("a".repeat(181)))
        assertFalse(ImportacaoDoOperador.nomeValido("   "))
        assertFalse(ImportacaoDoOperador.nomeValido("."))
        assertFalse(ImportacaoDoOperador.nomeValido(".."))
        assertFalse(ImportacaoDoOperador.nomeValido("pasta/arquivo.pdf"))
        assertFalse(ImportacaoDoOperador.nomeValido("pasta\\arquivo.pdf"))
        assertFalse(ImportacaoDoOperador.nomeValido("c:arquivo.pdf"))
        assertFalse(ImportacaoDoOperador.nomeValido("página.pdf"))
        // O VT não é espaço ASCII para o Rust.
        assertFalse(ImportacaoDoOperador.nomeValido("a\u000Bb.txt"))
        assertTrue(ImportacaoDoOperador.nomeValido("a\tb.txt"))
    }

    @Test
    fun `o tipo de midia vem da lista, sem parametro e em minusculas`() {
        assertEquals("text/markdown", ImportacaoDoOperador.tipoPermitido(" Text/X-Markdown ; charset=utf-8"))
        assertEquals("application/pdf", ImportacaoDoOperador.tipoPermitido("application/pdf"))
        assertNull(ImportacaoDoOperador.tipoPermitido("image/gif"))
        assertNull(ImportacaoDoOperador.tipoPermitido("application/octet-stream"))
        assertEquals("operator artifact media type is not allowlisted", recusa(pedido(tipo = "image/gif")))
    }

    @Test
    fun `as recusas seguem a ordem e as mensagens do canonico`() {
        assertEquals("operator artifact name is invalid or contains a path", recusa(pedido(nome = "../x.txt", tipo = "image/gif")))
        assertEquals("operator artifact must contain 1..=16777216 decoded bytes", recusa(pedido(bytes = ByteArray(0))))
        assertEquals(
            "operator artifact must contain 1..=16777216 decoded bytes",
            recusa(pedido(bytes = ByteArray(ImportacaoDoOperador.MAX_BYTES + 1))),
        )
        assertEquals("operator artifact bytes do not match the declared media type", recusa(pedido(tipo = "application/pdf")))
        assertEquals("URLs with embedded credentials are blocked", recusa(pedido(url = "https://usuario:senha@exemplo.org/a")))
        assertEquals("private or local network targets are blocked", recusa(pedido(url = "https://privado.exemplo/a")))
        assertEquals("cleartext http:// links are not collected; only https:// is", recusa(pedido(url = "http://exemplo.org/a")))
    }

    @Test
    fun `o artefato importado e um registro pronto, fornecido e resolvido pela pessoa`() {
        val bytes = "conteudo salvo".toByteArray()
        val importada = assertIs<ImportacaoDoOperador.Importacao.Importada>(
            importacao.importar(pedido(nome = " salvo.txt ", bytes = bytes, url = " https://exemplo.org/artigo#secao ")) { null },
        )
        val registro = importada.coleta.registro
        val resumo = FormatoDoRegistro.sha256(bytes)
        assertEquals(FormatoDoRegistro.sha256("operator_import|https://exemplo.org/artigo| salvo.txt |$resumo"), registro.id)
        assertEquals("https://exemplo.org/artigo", registro.url)
        assertEquals("https://exemplo.org/artigo", registro.urlFinal)
        assertEquals(ModoDeAcesso.CAPTURA_ASSISTIDA_PELO_OPERADOR, registro.modoDeAcesso)
        assertEquals(EstadoDaEvidencia.PRONTA, registro.estado)
        assertEquals(EstadoDoCache.FRESCO, registro.estadoDoCache)
        assertEquals(EstadoDosDireitos.FORNECIDO_PELO_OPERADOR, registro.estadoDosDireitos)
        assertEquals(EstadoDeInteracao.RESOLVIDA_POR_PESSOA, registro.estadoDeInteracao)
        assertTrue(registro.resolvidaPorPessoa)
        assertEquals("text/plain", registro.tipoDeConteudo)
        assertEquals(resumo, registro.sha256)
        assertEquals(bytes.size.toLong(), registro.bytes)
        assertEquals("salvo.txt", registro.nomeDoArtefato)
        assertEquals(FormatoDoRegistro.rfc3339(agora), registro.coletadaEm)
        assertEquals(FormatoDoRegistro.rfc3339(Instant.parse("2026-10-29T12:00:00Z")), registro.expiraEm)
        assertEquals(listOf(ImportacaoDoOperador.NOTA_DO_ARTEFATO), registro.notas)
        assertContentEquals(bytes, importada.coleta.corpo)
    }

    @Test
    fun `sem url de origem o endereco exibido e o do proprio registro`() {
        val importada = assertIs<ImportacaoDoOperador.Importacao.Importada>(importacao.importar(pedido()) { null })
        val registro = importada.coleta.registro
        assertEquals("maestro://operator-evidence/${registro.id}", registro.url)
        assertNull(registro.urlFinal)
    }

    @Test
    fun `reimportar o mesmo artefato mantem a data de criacao`() {
        val anterior = assertIs<ImportacaoDoOperador.Importacao.Importada>(importacao.importar(pedido()) { null }).coleta.registro
            .copy(criadaEm = "2026-09-01T00:00:00+00:00")
        val deNovo = assertIs<ImportacaoDoOperador.Importacao.Importada>(importacao.importar(pedido()) { if (it == anterior.id) anterior else null })
        assertEquals("2026-09-01T00:00:00+00:00", deNovo.coleta.registro.criadaEm)
    }

    @Test
    fun `a passagem ao navegador pede acao do operador e nao traz conteudo`() {
        val coleta = importacao.passagem("https://exemplo.org/pagina#topo") { null }
        val registro = coleta.registro
        assertEquals(ImportacaoDoOperador.idDaPassagem("https://exemplo.org/pagina"), registro.id)
        assertEquals("https://exemplo.org/pagina", registro.url)
        assertEquals(EstadoDaEvidencia.EXIGE_ACAO_DO_OPERADOR, registro.estado)
        assertEquals("https://exemplo.org/pagina", registro.urlFinal)
        assertEquals(ModoDeAcesso.CAPTURA_ASSISTIDA_PELO_OPERADOR, registro.modoDeAcesso)
        assertEquals(EstadoDeInteracao.NENHUMA, registro.estadoDeInteracao)
        // Antes do disparo, as duas notas de `handoff_record`; a de abertura vem depois.
        assertEquals(listOf(ImportacaoDoOperador.NOTA_DA_PASSAGEM, ImportacaoDoOperador.NOTA_SEM_PERFIL), registro.notas)
        assertNull(coleta.corpo)
        assertEquals(
            listOf(ImportacaoDoOperador.NOTA_DA_PASSAGEM, ImportacaoDoOperador.NOTA_SEM_PERFIL, ImportacaoDoOperador.NOTA_DO_NAVEGADOR_ABERTO),
            importacao.aberta(coleta, null).registro.notas,
        )
        // O navegador que não abriu fica anotado no lugar da nota de abertura.
        val anterior = registro.copy(criadaEm = "2026-09-01T00:00:00+00:00")
        val semNavegador = importacao.aberta(
            importacao.passagem("https://exemplo.org/pagina") { if (it == anterior.id) anterior else null },
            "failed to open system default browser: no activity found",
        )
        assertEquals("failed to open system default browser: no activity found", semNavegador.registro.notas.last())
        assertEquals("2026-09-01T00:00:00+00:00", semNavegador.registro.criadaEm)
        // A URL que a coleta recusaria, a passagem também recusa, antes de qualquer disparo.
        assertFailsWith<IntegridadeDeLinks.Falha> { importacao.passagem("https://privado.exemplo/a") { null } }
    }
}
