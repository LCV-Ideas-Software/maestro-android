package dev.lcv.maestro

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * O manifesto do `:app` (plano do `:app`, emenda A10): as três permissões que
 * o produto usa, a visibilidade dos navegadores `https`, o `Application` que
 * instala a fábrica, a Activity única em `singleTask` e a inicialização sob
 * demanda do WorkManager — o provedor do App Startup fica, e só o
 * inicializador do WorkManager sai. Lê o arquivo do repositório com o leitor
 * de XML da plataforma Java.
 */
class ManifestoDoAppTest {

    private val raiz = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val documento: Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File(raiz, "app/src/main/AndroidManifest.xml"))

    private fun elementos(nome: String): List<Element> =
        documento.getElementsByTagName(nome).let { lista -> (0 until lista.length).map { lista.item(it) as Element } }

    private fun Element.android(atributo: String): String = getAttributeNS(ANDROID, atributo)

    private fun Element.tools(atributo: String): String = getAttributeNS(TOOLS, atributo)

    @Test
    fun `as permissoes sao exatamente as tres do produto`() {
        assertEquals(
            setOf("android.permission.INTERNET", "android.permission.POST_NOTIFICATIONS", "android.permission.USE_BIOMETRIC"),
            elementos("uses-permission").map { it.android("name") }.toSet(),
        )
    }

    @Test
    fun `os navegadores https sao visiveis, e so eles`() {
        val intencao = elementos("queries").single().getElementsByTagName("intent").let { lista -> (0 until lista.length).map { lista.item(it) as Element } }.single()
        assertEquals(listOf("android.intent.action.VIEW"), intencao.getElementsByTagName("action").let { lista -> (0 until lista.length).map { (lista.item(it) as Element).android("name") } })
        assertEquals(listOf("https"), intencao.getElementsByTagName("data").let { lista -> (0 until lista.length).map { (lista.item(it) as Element).android("scheme") } })
    }

    @Test
    fun `o application e o que instala a fabrica`() {
        assertEquals(".MaestroApplication", elementos("application").single().android("name"))
    }

    @Test
    fun `a activity unica e exportada e singleTask`() {
        val activity = elementos("activity").single()
        assertEquals(".MainActivity", activity.android("name"))
        assertEquals("true", activity.android("exported"))
        assertEquals("singleTask", activity.android("launchMode"))
    }

    @Test
    fun `so o inicializador do WorkManager sai do provedor do App Startup`() {
        val provedor = elementos("provider").single()
        assertEquals("androidx.startup.InitializationProvider", provedor.android("name"))
        assertEquals("merge", provedor.tools("node"))
        val metadados = provedor.getElementsByTagName("meta-data").let { lista -> (0 until lista.length).map { lista.item(it) as Element } }
        assertEquals(listOf("androidx.work.WorkManagerInitializer"), metadados.map { it.android("name") })
        assertEquals("remove", metadados.single().tools("node"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOOLS = "http://schemas.android.com/tools"
    }
}
