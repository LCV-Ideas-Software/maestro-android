package dev.lcv.maestro.sessao

import com.fasterxml.jackson.core.JacksonException
import dev.lcv.maestro.protocolo.FormatoDeLinks
import dev.lcv.maestro.provedores.ColetorHttp
import java.io.File
import java.time.Instant

/**
 * `StoredWebEvidence` do canônico: o registro e os cabeçalhos seguros na
 * tabela `evidencias`; o corpo, quando existe, num arquivo de geração
 * imutável sob [pasta] (por padrão `noBackupFilesDir/evidencias`, fora do
 * backup sem regra de caminho). Uma [ColetorHttp.Coleta] sem corpo mantém a
 * geração atual, como o coletor já faz com o último corpo pronto.
 *
 * Um armazém por processo, com exclusão pelo próprio objeto: [guardar] e
 * [limparOrfaos] nunca se cruzam.
 */
public class ArmazemDeEvidenciasEmArquivo(
    private val banco: BancoDaSessao,
    private val pasta: File,
    private val relogio: () -> Instant,
) : ColetorHttp.ArmazemDeEvidencias {

    override fun existente(id: String): ColetorHttp.Coleta? = synchronized(this) {
        val linha = banco.evidencias().carregar(id) ?: return null
        val registro = FormatoDeLinks.lerEvidencia(linha.registroJson) ?: return null
        val cabecalhos = lerCabecalhos(linha.cabecalhosJson)
        val corpo = linha.caminhoDoCorpo?.let { caminho -> File(caminho).takeIf { it.isFile }?.readBytes() }
        ColetorHttp.Coleta(registro, cabecalhos, corpo)
    }

    override fun guardar(coleta: ColetorHttp.Coleta): Unit = synchronized(this) {
        val id = coleta.registro.id
        val geracaoNova = coleta.corpo?.let { Geracoes.gravar(pasta, id, it).absolutePath }
        var geracaoAnterior: String? = null
        banco.runInTransaction {
            val existente = banco.evidencias().carregar(id)
            geracaoAnterior = existente?.caminhoDoCorpo
            banco.evidencias().gravar(
                EvidenciaEntidade(
                    id = id,
                    registroJson = FormatoDeLinks.serializarEvidencia(coleta.registro),
                    cabecalhosJson = Json.ESTRITO.writeValueAsString(coleta.cabecalhos),
                    caminhoDoCorpo = geracaoNova ?: geracaoAnterior,
                    atualizadaEm = FormatoDeInstante.iso(relogio()),
                ),
            )
        }
        // Só depois do commit, e só se nenhuma linha ainda aponta para ela.
        val anterior = geracaoAnterior
        if (geracaoNova != null && anterior != null && anterior != geracaoNova && anterior !in banco.evidencias().caminhosDosCorpos()) {
            File(anterior).delete()
        }
    }

    /** Apaga os arquivos da pasta que nenhuma linha referencia (a reconciliação chama na abertura). */
    public fun limparOrfaos(): Unit = synchronized(this) {
        Geracoes.limparOrfaos(pasta, banco.evidencias().caminhosDosCorpos().toSet())
    }

    private fun lerCabecalhos(texto: String): Map<String, String> = try {
        val raiz = Json.ESTRITO.readTree(texto)
        if (raiz == null || !raiz.isObject) {
            emptyMap()
        } else {
            raiz.properties().filter { it.value.isTextual }.associate { it.key to it.value.textValue() }
        }
    } catch (erro: JacksonException) {
        emptyMap()
    }
}
