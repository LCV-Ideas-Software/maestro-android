/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro

import android.app.KeyguardManager
import android.content.Context
import dev.lcv.maestro.provedores.Provedor
import dev.lcv.maestro.seguranca.CofreDeChaves
import dev.lcv.maestro.seguranca.Guarda
import dev.lcv.maestro.seguranca.NivelDoCofre
import dev.lcv.maestro.sessao.Agendador
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
 * O que as telas usam do grafo: os repositórios sobre o Room, o agendador, o
 * cofre visto pela tela e o teste de chaves. Em produção vem da `Fabrica` do
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
            )
        }
    }
}

/** O `Application` deste processo, de qualquer `Context`. */
val Context.maestro: MaestroApplication
    get() = applicationContext as MaestroApplication
