package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Os anexos da sessão (o manifesto de citações e o que mais o operador
 * juntar): metadados na tabela `anexos`, bytes num arquivo de geração
 * imutável sob [pasta] (por padrão `noBackupFilesDir/anexos`), com o teto de
 * 16 MiB do `MAX_OPERATOR_ARTIFACT_BYTES` canônico (decisão do operador de
 * 25/09/2026). A remoção da sessão leva as linhas (`CASCADE`); os arquivos
 * saem na limpeza de órfãos.
 */
public class AnexosDaSessao(
    private val banco: BancoDaSessao,
    private val pasta: File,
    private val relogio: () -> Instant,
) {
    public fun adicionar(sessaoId: String, nomeOriginal: String, tipoDeMidia: String, bytes: ByteArray): Resultado<AnexoEntidade> =
        synchronized(this) {
            if (bytes.size > MAX_BYTES) return Resultado.Recusado(MENSAGEM_ACIMA_DO_TETO)
            val id = "anexo-${UUID.randomUUID()}"
            val arquivo = Geracoes.gravar(pasta, id, bytes)
            val linha = AnexoEntidade(
                id = id,
                sessaoId = sessaoId,
                nomeOriginal = nomeOriginal,
                tipoDeMidia = tipoDeMidia,
                caminho = arquivo.absolutePath,
                bytes = bytes.size.toLong(),
                sha256 = FormatoDoRegistro.sha256(bytes),
                criadoEm = FormatoDeInstante.iso(relogio()),
            )
            try {
                banco.anexos().inserir(linha)
            } catch (erro: RuntimeException) {
                arquivo.delete()
                throw erro
            }
            Resultado.Ok(linha)
        }

    /** Os anexos da sessão como o extrator de manifestos os quer: os bytes só são lidos quando pedidos. */
    public fun listar(sessaoId: String): List<ManifestosDosAnexos.Anexo> = banco.anexos().daSessao(sessaoId).map { linha ->
        ManifestosDosAnexos.Anexo(linha.nomeOriginal, linha.tipoDeMidia) { File(linha.caminho).readBytes() }
    }

    public fun daSessao(sessaoId: String): List<AnexoEntidade> = banco.anexos().daSessao(sessaoId)

    /** A linha some primeiro; o arquivo, depois. Devolve se havia o anexo. */
    public fun remover(id: String): Boolean = synchronized(this) {
        val linha = banco.anexos().um(id) ?: return false
        banco.anexos().remover(id)
        File(linha.caminho).delete()
        true
    }

    public fun limparOrfaos(): Unit = synchronized(this) {
        Geracoes.limparOrfaos(pasta, banco.anexos().caminhos().toSet())
    }

    public companion object {
        /** `MAX_OPERATOR_ARTIFACT_BYTES`: 16 MiB. */
        public const val MAX_BYTES: Int = 16 * 1024 * 1024
        public const val MENSAGEM_ACIMA_DO_TETO: String = "Anexo excede o limite de 16 MiB."
    }
}
