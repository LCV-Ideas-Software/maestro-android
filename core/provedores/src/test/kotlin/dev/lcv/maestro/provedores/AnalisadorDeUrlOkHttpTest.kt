package dev.lcv.maestro.provedores

import dev.lcv.maestro.protocolo.RedePublica
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * O parser real (`HttpUrl`) sob a regra pública do `:core:protocolo`: a
 * lista de `link_audit_blocks_local_and_private_targets` (`lib.rs:1341`) e
 * `public_url_gate_blocks_private_metadata_and_credentials`
 * (`web_evidence.rs:3605`), mais o que a revisão cruzada de 25/09/2026
 * pediu: literal IPv4 com limites, senha vazia, esquema ausente.
 */
class AnalisadorDeUrlOkHttpTest {

    private fun ip(texto: String) = InetAddress.getByName(texto).address

    private val publico = RedePublica.ResolvedorDeNomes { listOf(ip(RedeDeTeste.PUBLICO)) }
    private val privado = RedePublica.ResolvedorDeNomes { listOf(ip("10.0.0.1")) }

    private fun motivo(url: String, resolvedor: RedePublica.ResolvedorDeNomes = publico) =
        RedePublica.motivoDeRecusa(url, AnalisadorDeUrlOkHttp, resolvedor)

    @Test
    fun `alvos locais e privados do canonico sao recusados pelo parser real`() {
        val bloqueadas = listOf(
            "http://localhost:8787/test", "http://127.0.0.1/test", "http://0.0.0.0/test", "http://10.0.0.1/test",
            "http://192.168.1.10/test", "http://172.16.0.1/test", "http://100.64.0.1/test",
            "http://169.254.169.254/latest", "http://192.0.2.1/test", "http://198.51.100.1/test",
            "http://203.0.113.1/test", "http://224.0.0.1/test", "http://255.255.255.255/test", "http://[::1]/test",
            "http://[fc00::1]/test", "http://[fe80::1]/test", "http://[ff02::1]/test", "http://[2001:db8::1]/test",
            "http://[::127.0.0.1]/test", "http://[::ffff:127.0.0.1]/test",
        )
        for (url in bloqueadas) assertNotNull(motivo(url), url)
        assertNull(motivo("https://example.com/source"))
        assertNull(motivo("https://10.0.0.1.example.com/source"))
        assertEquals(
            "dominio resolve para IP privado/reservado bloqueado por seguranca",
            motivo("https://10.0.0.1.example.com/source", privado),
        )
    }

    @Test
    fun `IP literal e julgado no texto, dentro dos limites do inet_aton`() {
        assertContentEquals(ip("127.0.0.1"), AnalisadorDeUrlOkHttp.ipLiteral("127.0.0.1"))
        assertContentEquals(ip("127.0.0.1"), AnalisadorDeUrlOkHttp.ipLiteral("127.1"))
        assertContentEquals(ip("127.0.0.1"), AnalisadorDeUrlOkHttp.ipLiteral("2130706433"))
        assertContentEquals(ip("10.0.0.1"), AnalisadorDeUrlOkHttp.ipLiteral("10.1"))
        assertEquals(16, AnalisadorDeUrlOkHttp.ipLiteral("::1")!!.size)
        // Fora dos limites ou fora da forma: nome, não literal — e nunca vai ao `InetAddress`.
        for (host in listOf("999.999.999.999", "256.1.1.1", "0x7f.0.0.1", "1.2.3.4.5", "1..2", "4294967296", "example.com")) {
            assertNull(AnalisadorDeUrlOkHttp.ipLiteral(host), host)
        }
        // O julgamento do literal não consulta resolvedor nenhum.
        val proibido = RedePublica.ResolvedorDeNomes { error("literal foi ao DNS") }
        assertEquals("IP privado, reservado ou local bloqueado por seguranca", motivo("http://127.1/test", proibido))
        assertEquals("IP privado, reservado ou local bloqueado por seguranca", motivo("http://2130706433/", proibido))
        // A sequência inválida é nome: sem resposta do resolvedor, não bloqueia aqui e falha fechada na conexão.
        val nenhum = RedePublica.ResolvedorDeNomes { null }
        assertNull(motivo("http://999.999.999.999/test", nenhum))
    }

    @Test
    fun `credenciais embutidas, com senha vazia inclusive, ficam visiveis ao protocolo`() {
        assertTrue(AnalisadorDeUrlOkHttp.temSenha("https://user:secret@example.com/"))
        assertTrue(AnalisadorDeUrlOkHttp.temSenha("https://:@example.com/"))
        assertTrue(AnalisadorDeUrlOkHttp.temSenha("https://:pass@example.com/a:b"))
        assertFalse(AnalisadorDeUrlOkHttp.temSenha("https://user@example.com/"))
        assertFalse(AnalisadorDeUrlOkHttp.temSenha("https://example.com/a:b@c"))
        assertFalse(AnalisadorDeUrlOkHttp.temSenha("https://example.com/?x=a:b@c"))
        assertEquals("", AnalisadorDeUrlOkHttp.analisar("https://:@example.com/")!!.senha)
        assertEquals("secret", AnalisadorDeUrlOkHttp.analisar("https://user:secret@example.com/")!!.senha)
        assertNull(AnalisadorDeUrlOkHttp.analisar("https://user@example.com/")!!.senha)
        assertEquals("user", AnalisadorDeUrlOkHttp.analisar("https://user@example.com/")!!.usuario)
    }

    @Test
    fun `http e https malformados sao nulos, outros esquemas so dizem qual sao`() {
        assertNull(AnalisadorDeUrlOkHttp.analisar("http://exa mple.com/"))
        assertNull(AnalisadorDeUrlOkHttp.analisar("HTTP://[::1"))
        assertNull(AnalisadorDeUrlOkHttp.analisar("sem-esquema"))
        assertNull(AnalisadorDeUrlOkHttp.analisar("ht tp://x"))
        // Porta inválida: o HttpUrl recusa; o `java.net.URI` aceitaria, e não pode ser consultado.
        assertNull(AnalisadorDeUrlOkHttp.analisar("http://example.com:99999/"))
        val correio = AnalisadorDeUrlOkHttp.analisar("MAILTO:editor@example.com")!!
        assertEquals("mailto", correio.esquema)
        assertNull(correio.host)
        assertNull(correio.ipDoHost)
        assertEquals("javascript", AnalisadorDeUrlOkHttp.analisar("javascript:alert(1)")!!.esquema)
        assertEquals("ftp", AnalisadorDeUrlOkHttp.analisar("ftp://user:pw@files.example.com/a.zip")!!.esquema)
        assertEquals("pw", AnalisadorDeUrlOkHttp.analisar("ftp://user:pw@files.example.com/a.zip")!!.senha)
    }

    @Test
    fun `a serializacao e a do navegador`() {
        val analisada = AnalisadorDeUrlOkHttp.analisar("HTTPS://Example.COM/a?b#c")!!
        assertEquals("https", analisada.esquema)
        assertEquals("example.com", analisada.host)
        assertEquals("/a", analisada.caminho)
        assertEquals("https://example.com/a?b#c", analisada.serializada)
        assertNull(analisada.ipDoHost)
        assertContentEquals(ip("127.0.0.1"), AnalisadorDeUrlOkHttp.analisar("http://127.0.0.1/x")!!.ipDoHost)
        assertEquals("xn--caf-dma.example", AnalisadorDeUrlOkHttp.analisar("https://café.example/")!!.host)
    }
}
