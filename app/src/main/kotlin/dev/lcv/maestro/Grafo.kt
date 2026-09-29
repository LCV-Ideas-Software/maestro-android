/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.lcv.maestro.protocolo.IntegridadeDeLinks
import dev.lcv.maestro.provedores.ImportacaoDoOperador
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.CofreDeChaves
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.Agendador
import dev.lcv.maestro.sessao.AnexosDaSessao
import dev.lcv.maestro.sessao.ArmazemDeEvidenciasEmArquivo
import dev.lcv.maestro.sessao.LinksDaSessao
import dev.lcv.maestro.sessao.RepositorioDeArtefatos
import dev.lcv.maestro.sessao.RepositorioDeConfiguracoes
import dev.lcv.maestro.sessao.RepositorioDeSessoes
import dev.lcv.maestro.sessao.Retomada
import dev.lcv.maestro.sessao.TesteDeChaves
import java.time.Instant
import kotlinx.coroutines.sync.Mutex

/**
 * Uma trava só para a reconciliação da abertura e para o par
 * `criar → enfileirar` / `pedir → enfileirar` das telas (revisão cruzada de
 * 28/09/2026, emenda A11): uma linha recém-inserida não pode ser varrida como
 * "sem trabalho vivo" entre a inserção e o enfileiramento.
 */
object Sincronia {
    val reconciliacao = Mutex()
}

/**
 * O que as telas pedem ao cofre, e nada mais: a presença de cada chave (com o
 * terceiro estado "não foi possível verificar agora"), guardar, apagar, o
 * nível da chave do Keystore e se o aparelho tem trava de tela. O
 * [CofreDeChaves] é preso ao hardware; os testes das telas o substituem por
 * um dublê em memória (especificação, seção 8).
 */
interface CofreDaTela {
    suspend fun chaves(): Map<Provedor, Boolean?>
    suspend fun guardar(provedor: Provedor, chave: String): Guarda
    suspend fun apagar(provedor: Provedor): Boolean
    suspend fun nivel(): NivelDoCofre?

    /** `KeyguardManager.isDeviceSecure`: sem trava, a chave do Keystore não existe, e uma chave perdida foi por isso (seção 4.2). */
    fun travaDeTela(): Boolean
}

class CofreReal(
    private val contexto: Context,
    private val cofre: CofreDeChaves,
    private val configuracoes: RepositorioDeConfiguracoes,
) : CofreDaTela {
    override suspend fun chaves(): Map<Provedor, Boolean?> = configuracoes.chaves()
    override suspend fun guardar(provedor: Provedor, chave: String): Guarda = cofre.guardar(provedor, chave)
    override suspend fun apagar(provedor: Provedor): Boolean = cofre.apagar(provedor)
    override suspend fun nivel(): NivelDoCofre? = cofre.nivel()
    override fun travaDeTela(): Boolean = contexto.getSystemService(KeyguardManager::class.java).isDeviceSecure
}

/**
 * O navegador do sistema, que abre o link na captura assistida pelo operador
 * (especificação, seção 2.2): devolve o erro do disparo, com o texto do
 * canônico, ou `null` quando abriu. Os testes das telas o trocam por um dublê
 * que só anota a URL — nenhum teste abre navegador.
 */
fun interface Navegador {
    fun abrir(contexto: Context, url: String): String?

    companion object {
        /**
         * `ACTION_VIEW` com `CATEGORY_BROWSABLE`, só para a URL já validada, a
         * partir do contexto da Activity (fora dela, o Android recusa o
         * disparo sem `FLAG_ACTIVITY_NEW_TASK`). A falta de navegador chega
         * como `ActivityNotFoundException`, anotada no registro de passagem.
         */
        val DO_SISTEMA = Navegador { contexto, url ->
            try {
                contexto.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                null
            } catch (erro: ActivityNotFoundException) {
                "failed to open system default browser: ${erro.message}"
            }
        }
    }
}

/**
 * O que as telas usam do grafo: os repositórios sobre o Room, o agendador, o
 * cofre visto pela tela, o teste de chaves, os anexos e a revisão dos links
 * (linhas, evidências, captura assistida, busca e navegador). Em produção vem da `Fabrica` do
 * processo ([de]); os testes das telas montam a mesma classe sobre um banco
 * de teste e dublês, sem Hilt (decisão 18 do operador, 28/09/2026).
 */
class Dependencias(
    val sessoes: RepositorioDeSessoes,
    val artefatos: RepositorioDeArtefatos,
    val retomada: Retomada,
    val configuracoes: RepositorioDeConfiguracoes,
    val agendador: Agendador,
    val cofre: CofreDaTela,
    val testeDeChaves: TesteDeChaves,
    val anexos: AnexosDaSessao,
    val links: LinksDaSessao,
    val evidencias: ArmazemDeEvidenciasEmArquivo,
    val importacao: ImportacaoDoOperador,
    /** A busca de evidências (Crossref e OpenAlex) com o e-mail de contato atual; lê o Room, então fora da linha principal. */
    val busca: () -> IntegridadeDeLinks.BuscadorDeEvidencia,
    val navegador: Navegador,
    val relogio: () -> Instant = Instant::now,
) {
    companion object {
        fun de(aplicativo: MaestroApplication): Dependencias = aplicativo.grafo.let { grafo ->
            Dependencias(
                sessoes = grafo.sessoes,
                artefatos = grafo.artefatos,
                retomada = grafo.retomada,
                configuracoes = grafo.configuracoes,
                agendador = grafo.agendador,
                cofre = CofreReal(aplicativo, aplicativo.cofre, grafo.configuracoes),
                testeDeChaves = grafo.testeDeChaves,
                anexos = grafo.anexos,
                links = grafo.links,
                evidencias = grafo.evidencias,
                importacao = grafo.importacao,
                busca = grafo::buscaDeEvidencias,
                navegador = Navegador.DO_SISTEMA,
            )
        }
    }
}

/** O `Application` deste processo, de qualquer `Context`. */
val Context.maestro: MaestroApplication
    get() = applicationContext as MaestroApplication
