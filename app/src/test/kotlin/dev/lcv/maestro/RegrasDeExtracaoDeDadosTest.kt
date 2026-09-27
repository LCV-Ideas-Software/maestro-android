package dev.lcv.maestro

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * A regra de backup do `:app` (especificação, seção 4.2): o banco do Room e os
 * arquivos do SQLite ao lado dele ficam fora dos dois domínios, e o manifesto
 * aponta para a regra. Lê os arquivos do repositório, não o APK.
 */
class RegrasDeExtracaoDeDadosTest {

    private val raiz = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val regras = File(raiz, "app/src/main/res/xml/data_extraction_rules.xml")
    private val manifesto = File(raiz, "app/src/main/AndroidManifest.xml")

    private val esperados = setOf("maestro.db", "maestro.db-wal", "maestro.db-shm", "maestro.db-journal")

    private fun excluidos(dominio: String): Set<String> {
        val documento = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(regras)
        val secao = documento.getElementsByTagName(dominio)
        assertEquals(1, secao.length, "uma seção <$dominio>")
        val itens = (secao.item(0) as Element).getElementsByTagName("exclude")
        return (0 until itens.length).map { itens.item(it) as Element }
            .filter { it.getAttribute("domain") == "database" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun `o banco e os arquivos do sqlite ficam fora do backup na nuvem`() {
        assertEquals(esperados, excluidos("cloud-backup"))
    }

    @Test
    fun `o banco e os arquivos do sqlite ficam fora da transferencia entre aparelhos`() {
        assertEquals(esperados, excluidos("device-transfer"))
    }

    @Test
    fun `o manifesto aponta para a regra`() {
        assertTrue(manifesto.readText().contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }
}
