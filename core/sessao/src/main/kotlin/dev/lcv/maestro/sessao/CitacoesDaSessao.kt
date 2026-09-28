package dev.lcv.maestro.sessao

import dev.lcv.maestro.protocolo.AuditoriaAbnt
import dev.lcv.maestro.protocolo.AuditoriaFinal
import dev.lcv.maestro.protocolo.FormatoDoRegistro
import dev.lcv.maestro.protocolo.ManifestosDosAnexos
import dev.lcv.maestro.protocolo.PromptsDaSessao

/** O que a sessão sabe das citações, lido uma vez antes do primeiro turno. */
public sealed interface Citacoes {
    /**
     * [contexto] é o que a auditoria de cinco estágios recebe; [bloco] é o
     * que os prompts recebem no fim (o `evidence.block` do desktop).
     */
    public data class Lidas(val contexto: AuditoriaFinal.ContextoDeCitacoes, val bloco: String) : Citacoes

    /** Um anexo que se apresenta como manifesto e não pode ser lido: a sessão não segue com ele. */
    public data class Recusadas(val motivo: String) : Citacoes
}

/**
 * `citation_manifests_from_attachments` + `empty_citation_manifest`
 * (`session_orchestration.rs:338-372`), como a sessão do aparelho os usa.
 *
 * Desvio declarado do desktop: lá o hash do protocolo é o que a interface
 * fixou ao importar o arquivo (`protocol_hash`, SHA-256 em 64 hexadecimais);
 * aqui a sessão não tem importação separada, e o hash é o SHA-256 do texto
 * do protocolo gravado na linha — o mesmo algoritmo, o mesmo formato, sobre
 * o texto que o agente recebe.
 */
public object CitacoesDaSessao {
    public fun de(anexos: List<ManifestosDosAnexos.Anexo>, protocolo: String): Citacoes {
        val hash = FormatoDoRegistro.sha256(protocolo)
        val manifestos = when (val saida = ManifestosDosAnexos.extrair(anexos)) {
            is ManifestosDosAnexos.Saida.Lidos -> saida.manifestos
            is ManifestosDosAnexos.Saida.Recusados -> return Citacoes.Recusadas(saida.motivo)
        }
        val implicito = manifestos.atual == null
        val manifesto = manifestos.atual ?: AuditoriaAbnt.manifestoVazio(hash)
        return Citacoes.Lidas(
            contexto = AuditoriaFinal.ContextoDeCitacoes(hashDoProtocolo = hash, manifesto = manifesto, manifestoAnterior = manifestos.anterior),
            bloco = PromptsDaSessao.resumoDoManifesto(implicito, manifesto.citacoes.size, manifesto.fontes.size),
        )
    }
}
