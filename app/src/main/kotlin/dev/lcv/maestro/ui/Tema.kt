/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema de design do porte, na forma da calculadora-android (convenção da
 * frota, modelo Proton): cores nomeadas pelo papel, publicadas por
 * `CompositionLocal` e alimentando o `MaterialTheme`.
 *
 * Os valores são os medidos na folha de estilo do painel web
 * (`admin-app/src/App.css` e `src/index.css`), **dentro do
 * `.module-shell`**, que é onde o módulo Maestro AI vive: ali o botão
 * principal é o acento com texto branco, o botão fantasma é o acento a 10 %
 * com borda a 24 %, e os cartões têm raio 24 com borda do acento a 20 %
 * (revisão cruzada de 28/09/2026, emenda A13). Nenhuma cor inventada.
 *
 * O web é só claro, e o porte acompanha: um tema escuro exigiria inventar
 * tons (desvio declarado, como na calculadora). A fonte Inter do web não vem
 * embutida: a família sem serifa da plataforma a substitui (desvio declarado,
 * sem download de fonte).
 */
@Immutable
data class CoresMaestro(
    /** Fundo da página (`:root` do web). */
    val fundo: Color = Color(0xFFE8EAED),
    val superficie: Color = Color.White,
    val texto: Color = Color(0xFF202124),
    /** O cinza-azulado das legendas (`#64748b`). */
    val textoFraco: Color = Color(0xFF64748B),
    /** `.eyebrow` e `.result-empty` (`#80868b`). */
    val textoApagado: Color = Color(0xFF80868B),
    val pilulaTexto: Color = Color(0xFF5F6368),
    val pilulaBorda: Color = Color(0x14000000),
    val acento: Color = Color(0xFF1A73E8),
    /** `--module-accent-soft`: a linha selecionada. */
    val acentoSuave: Color = Color(0x141A73E8),
    /** `--module-accent-border`: o cabeçalho de seção. */
    val acentoBorda: Color = Color(0x2E1A73E8),
    /** O quadro do ícone de cada cartão. */
    val iconeFundo: Color = Color(0x1A1A73E8),
    /** Borda dos cartões dentro do `.module-shell`: acento a 20 %. */
    val cartaoBorda: Color = Color(0x331A73E8),
    val botaoPrimario: Color = Color(0xFF1A73E8),
    val botaoPrimarioTexto: Color = Color.White,
    val botaoFantasmaFundo: Color = Color(0x1A1A73E8),
    val botaoFantasmaTexto: Color = Color(0xFF1A73E8),
    val botaoFantasmaBorda: Color = Color(0x3D1A73E8),
    /** O cartão "Erro operacional": `rgba(220, 38, 38, 0.35)`. */
    val erroBorda: Color = Color(0x59DC2626),
    val erro: Color = Color(0xFFDC2626),
)

/** Raios medidos: cartões 24, cabeçalho de seção 20, quadro do ícone 12, pílulas e botões inteiramente arredondados. */
@Immutable
data class FormasMaestro(
    val cartao: Dp = 24.dp,
    val cabecalho: Dp = 20.dp,
    val icone: Dp = 12.dp,
    val campo: Dp = 12.dp,
)

/** Espaçamentos medidos: 18 entre cartões, 22 dentro deles. */
@Immutable
data class EspacosMaestro(
    val entreCartoes: Dp = 18.dp,
    val dentroDoCartao: Dp = 22.dp,
    val entreLinhas: Dp = 8.dp,
    val lateral: Dp = 16.dp,
)

val LocalCores = staticCompositionLocalOf { CoresMaestro() }
val LocalFormas = staticCompositionLocalOf { FormasMaestro() }
val LocalEspacos = staticCompositionLocalOf { EspacosMaestro() }

object Tema {
    val cores: CoresMaestro
        @Composable @ReadOnlyComposable get() = LocalCores.current

    val formas: FormasMaestro
        @Composable @ReadOnlyComposable get() = LocalFormas.current

    val espacos: EspacosMaestro
        @Composable @ReadOnlyComposable get() = LocalEspacos.current
}

private fun CoresMaestro.paletaMaterial() = lightColorScheme(
    primary = acento,
    onPrimary = Color.White,
    secondary = acento,
    onSecondary = Color.White,
    background = fundo,
    onBackground = texto,
    surface = superficie,
    onSurface = texto,
    onSurfaceVariant = textoFraco,
    outline = cartaoBorda,
    error = erro,
    // Sem estes, os diálogos, menus e fichas herdam os tons lilases da paleta de
    // referência do Material 3; o web não tem tom nenhum além do branco e do acento.
    surfaceVariant = superficie,
    surfaceContainerLowest = superficie,
    surfaceContainerLow = superficie,
    surfaceContainer = superficie,
    surfaceContainerHigh = superficie,
    surfaceContainerHighest = superficie,
    surfaceTint = superficie,
    primaryContainer = acentoSuave,
    onPrimaryContainer = acento,
    secondaryContainer = acentoSuave,
    onSecondaryContainer = acento,
)

/**
 * A tipografia do Material com o espaçamento entre letras do web: `normal`
 * (zero) em todo texto; só a sobrelinha (`.eyebrow`) tem os seus 0,08 em.
 * Os estilos do Material 3 trazem de 0,1 a 0,5 sp, que deixam o texto
 * corrido visivelmente mais aberto que o do painel.
 */
private val TIPOGRAFIA: Typography = Typography().let { padrao ->
    fun TextStyle.semEspaco() = copy(letterSpacing = 0.sp)
    Typography(
        displayLarge = padrao.displayLarge.semEspaco(),
        displayMedium = padrao.displayMedium.semEspaco(),
        displaySmall = padrao.displaySmall.semEspaco(),
        headlineLarge = padrao.headlineLarge.semEspaco(),
        headlineMedium = padrao.headlineMedium.semEspaco(),
        headlineSmall = padrao.headlineSmall.semEspaco(),
        titleLarge = padrao.titleLarge.semEspaco(),
        titleMedium = padrao.titleMedium.semEspaco(),
        titleSmall = padrao.titleSmall.semEspaco(),
        bodyLarge = padrao.bodyLarge.semEspaco(),
        bodyMedium = padrao.bodyMedium.semEspaco(),
        bodySmall = padrao.bodySmall.semEspaco(),
        labelLarge = padrao.labelLarge.semEspaco(),
        labelMedium = padrao.labelMedium.semEspaco(),
        labelSmall = padrao.labelSmall.semEspaco(),
    )
}

@Composable
fun MaestroTheme(conteudo: @Composable () -> Unit) {
    val cores = CoresMaestro()
    CompositionLocalProvider(
        LocalCores provides cores,
        LocalFormas provides FormasMaestro(),
        LocalEspacos provides EspacosMaestro(),
    ) {
        MaterialTheme(colorScheme = cores.paletaMaterial(), typography = TIPOGRAFIA, content = conteudo)
    }
}
