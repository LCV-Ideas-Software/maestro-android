package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Os corpos de evidência e os anexos são arquivos, não colunas: um corpo
 * chega a 8 MiB e um anexo a 16 MiB, e o `CursorWindow` do Android não lê
 * uma linha acima de 2 MiB. Cada arquivo é uma **geração imutável**
 * `<id>-<sha256>` (emenda A2): escreve-se em `.tmp`, faz-se `fsync`, e o
 * `rename` atômico publica; a geração anterior só some depois que a linha
 * que apontava para ela deixou de existir.
 */
internal object Geracoes {
    fun nome(id: String, bytes: ByteArray): String = "$id-${FormatoDoRegistro.sha256(bytes)}"

    /** O arquivo da geração, gravado se ainda não existir (o nome já é o conteúdo). */
    fun gravar(pasta: File, id: String, bytes: ByteArray): File {
        if (!pasta.isDirectory && !pasta.mkdirs() && !pasta.isDirectory) {
            throw java.io.IOException("cannot create directory ${pasta.absolutePath}")
        }
        val destino = File(pasta, nome(id, bytes))
        if (destino.isFile) return destino
        val temporario = File(pasta, "${destino.name}.tmp")
        FileOutputStream(temporario).use { saida ->
            saida.write(bytes)
            saida.fd.sync()
        }
        Files.move(temporario.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return destino
    }

    /** Apaga o que está na pasta e não consta em [referenciados] (caminhos absolutos), inclusive `.tmp`. */
    fun limparOrfaos(pasta: File, referenciados: Set<String>) {
        val arquivos = pasta.listFiles() ?: return
        for (arquivo in arquivos) {
            if (arquivo.isFile && arquivo.absolutePath !in referenciados) arquivo.delete()
        }
    }
}
