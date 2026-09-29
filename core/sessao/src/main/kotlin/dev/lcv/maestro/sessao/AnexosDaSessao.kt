package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import java.io.File
import java.io.IOException
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
    /**
     * Com a sessão na fila ou em execução, os anexos não mudam ([MENSAGEM_EM_EXECUCAO]). A tela confere
     * antes, mas a leitura lenta de um provedor de documentos deixa a sessão ser retomada no meio: o
     * status é conferido na mesma transação que grava a linha (achado do Codex na #78).
     */
    public fun adicionar(sessaoId: String, nomeOriginal: String, tipoDeMidia: String, bytes: ByteArray): Resultado<AnexoEntidade> =
        synchronized(this) {
            check(!banco.inTransaction()) { "attachment files are published and reclaimed outside any transaction" }
            val id = "anexo-${UUID.randomUUID()}"
            val arquivo = when (val publicado = publicar(id, bytes)) {
                is Resultado.Recusado -> return publicado
                is Resultado.Ok -> publicado.valor
            }
            val linha = linha(id, sessaoId, nomeOriginal, tipoDeMidia, bytes, arquivo)
            val gravou = try {
                banco.runInTransaction<Boolean> {
                    if (emExecucao(sessaoId)) {
                        false
                    } else {
                        banco.anexos().inserir(linha)
                        true
                    }
                }
            } catch (erro: RuntimeException) {
                arquivo.delete()
                throw erro
            }
            if (!gravou) {
                arquivo.delete()
                return Resultado.Recusado(MENSAGEM_EM_EXECUCAO)
            }
            Resultado.Ok(linha)
        }

    /**
     * A sessão nova com o manifesto do formulário, tudo ou nada (achado do Codex na #78): o
     * arquivo é publicado antes, fora de transação, e a linha do anexo entra na transação que
     * cria a sessão. Nenhuma falha, nem a morte do processo, deixa na fila uma sessão que a
     * reconciliação retomaria sem o manifesto; o arquivo sem linha sai aqui ou na limpeza de
     * órfãos, que espera este bloco.
     */
    public fun criarSessao(
        sessoes: RepositorioDeSessoes,
        entrada: EntradaResolvida,
        nomeOriginal: String,
        tipoDeMidia: String,
        bytes: ByteArray,
    ): Resultado<SessaoEntidade> = synchronized(this) {
        check(!banco.inTransaction()) { "attachment files are published and reclaimed outside any transaction" }
        val id = "anexo-${UUID.randomUUID()}"
        val arquivo = when (val publicado = publicar(id, bytes)) {
            is Resultado.Recusado -> return publicado
            is Resultado.Ok -> publicado.valor
        }
        try {
            Resultado.Ok(sessoes.criar(entrada) { sessao -> banco.anexos().inserir(linha(id, sessao.id, nomeOriginal, tipoDeMidia, bytes, arquivo)) })
        } catch (erro: RuntimeException) {
            arquivo.delete()
            throw erro
        }
    }

    /** O teto e o arquivo da geração. O disco que falha é recusa, com a frase do desktop (`persist_session_attachments`). */
    private fun publicar(id: String, bytes: ByteArray): Resultado<File> {
        if (bytes.size > MAX_BYTES) return Resultado.Recusado(MENSAGEM_ACIMA_DO_TETO)
        return try {
            Resultado.Ok(Geracoes.gravar(pasta, id, bytes))
        } catch (erro: IOException) {
            Resultado.Recusado("failed to write attachment: ${erro.message}")
        }
    }

    private fun linha(id: String, sessaoId: String, nomeOriginal: String, tipoDeMidia: String, bytes: ByteArray, arquivo: File) = AnexoEntidade(
        id = id,
        sessaoId = sessaoId,
        nomeOriginal = nomeOriginal,
        tipoDeMidia = tipoDeMidia,
        caminho = arquivo.absolutePath,
        bytes = bytes.size.toLong(),
        sha256 = FormatoDoRegistro.sha256(bytes),
        criadoEm = FormatoDeInstante.iso(relogio()),
    )

    /** Os anexos da sessão como o extrator de manifestos os quer: os bytes só são lidos quando pedidos. */
    public fun listar(sessaoId: String): List<ManifestosDosAnexos.Anexo> = banco.anexos().daSessao(sessaoId).map { linha ->
        ManifestosDosAnexos.Anexo(linha.nomeOriginal, linha.tipoDeMidia) { File(linha.caminho).readBytes() }
    }

    public fun daSessao(sessaoId: String): List<AnexoEntidade> = banco.anexos().daSessao(sessaoId)

    /**
     * A linha some primeiro (commit próprio, com o status da sessão conferido nele, como em
     * [adicionar]); o arquivo, depois. `Ok` diz se havia o anexo. Nunca dentro de uma transação de quem chama.
     */
    public fun remover(id: String): Resultado<Boolean> = synchronized(this) {
        check(!banco.inTransaction()) { "attachment files are published and reclaimed outside any transaction" }
        val linha = banco.anexos().um(id) ?: return Resultado.Ok(false)
        val removeu = banco.runInTransaction<Boolean> {
            if (emExecucao(linha.sessaoId)) {
                false
            } else {
                banco.anexos().remover(id)
                true
            }
        }
        if (!removeu) return Resultado.Recusado(MENSAGEM_EM_EXECUCAO)
        File(linha.caminho).delete()
        Resultado.Ok(true)
    }

    public fun limparOrfaos(): Unit = synchronized(this) {
        Geracoes.limparOrfaos(pasta, banco.anexos().caminhos().toSet())
    }

    private fun emExecucao(sessaoId: String): Boolean = banco.sessoes().carregar(sessaoId)?.status in Estados.ATIVOS

    public companion object {
        /** `MAX_OPERATOR_ARTIFACT_BYTES`: 16 MiB. */
        public const val MAX_BYTES: Int = 16 * 1024 * 1024
        public const val MENSAGEM_ACIMA_DO_TETO: String = "Anexo excede o limite de 16 MiB."

        /** Só do aparelho: o trabalho lê os anexos ao começar. A tela de anexos mostra a mesma frase. */
        public const val MENSAGEM_EM_EXECUCAO: String = "Com a sessão na fila ou em execução, os anexos não mudam: o trabalho os lê ao começar."
    }
}
