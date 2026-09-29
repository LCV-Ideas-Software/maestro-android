/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * O vocabulário visual do painel web, em componentes nativos: `.result-card`
 * e `.form-card` (Cartao), `.detail-header` com `.detail-icon` e `.eyebrow`
 * (Cabecalho), `.status-pill` (Pilula), `.primary-button` e `.ghost-button`
 * (BotaoPrimario, BotaoFantasma), `.metric-card` (CartaoDeMetrica),
 * `.result-empty` (VazioDeResultado) e o `.field-group` (CampoDeTexto).
 * Medidas da folha de estilo do web, dentro do `.module-shell`.
 */

/** `.form-card` / `.result-card`: branco, raio 24, borda do acento a 20 %, 22 de respiro. */
@Composable
fun Cartao(
    modifier: Modifier = Modifier,
    borda: Color = Tema.cores.cartaoBorda,
    conteudo: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Tema.formas.cartao),
        color = Tema.cores.superficie,
        border = BorderStroke(1.dp, borda),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(Tema.espacos.dentroDoCartao),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = conteudo,
        )
    }
}

/** `.eyebrow`: 12 sp, caixa alta, espaçamento de 0,08 em, peso 500, cinza. */
@Composable
fun Sobrelinha(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto.uppercase(),
        modifier = modifier,
        fontSize = 12.sp,
        letterSpacing = 0.08.em,
        fontWeight = FontWeight.Medium,
        color = Tema.cores.textoApagado,
    )
}

/**
 * `.detail-header` com `.detail-icon`: o quadro de 40 dp com o ícone no
 * acento sobre o degradê do acento suave para o branco a 50 %, a sobrelinha e
 * o título; [acao] fica à direita, como o botão que o web alinha com
 * `margin-left: auto`.
 */
@Composable
fun Cabecalho(
    @DrawableRes icone: Int,
    sobrelinha: String,
    titulo: String,
    modifier: Modifier = Modifier,
    acao: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    Brush.linearGradient(listOf(Tema.cores.acentoSuave, Color.White.copy(alpha = 0.5f))),
                    RoundedCornerShape(Tema.formas.icone),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icone), contentDescription = null, tint = Tema.cores.acento, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Sobrelinha(sobrelinha)
            Text(text = titulo, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Tema.cores.texto)
        }
        acao?.invoke(this)
    }
}

/** `.status-pill`: branco, borda a 8 %, texto `#5f6368` a 12 sp, raio inteiro. */
@Composable
fun Pilula(texto: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Tema.cores.superficie,
        border = BorderStroke(1.dp, Tema.cores.pilulaBorda),
    ) {
        Text(
            text = texto,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Tema.cores.pilulaTexto,
        )
    }
}

private val RespiroDoBotao = PaddingValues(horizontal = 18.dp, vertical = 9.dp)

@Composable
private fun ConteudoDoBotao(texto: String, @DrawableRes icone: Int?, carregando: Boolean, cor: Color) {
    when {
        carregando -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cor)
        icone != null -> Icon(painterResource(icone), contentDescription = null, modifier = Modifier.size(16.dp), tint = cor)
    }
    if (carregando || icone != null) Box(Modifier.width(8.dp))
    Text(text = texto, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
}

/** `.primary-button` dentro do `.module-shell`: o acento, texto branco, pílula. */
@Composable
fun BotaoPrimario(
    texto: String,
    aoClicar: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icone: Int? = null,
    habilitado: Boolean = true,
    carregando: Boolean = false,
) {
    Button(
        onClick = aoClicar,
        modifier = modifier,
        enabled = habilitado && !carregando,
        shape = CircleShape,
        contentPadding = RespiroDoBotao,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tema.cores.botaoPrimario,
            contentColor = Tema.cores.botaoPrimarioTexto,
        ),
    ) {
        ConteudoDoBotao(texto, icone, carregando, Tema.cores.botaoPrimarioTexto)
    }
}

/**
 * `.ghost-button` dentro do `.module-shell`: acento a 10 % de fundo, texto no
 * acento, borda a 24 %. [selecionado] é a linha escolhida de uma lista: o web
 * troca o fundo para `--module-accent-soft` (8 %, quase igual aos 10 % do
 * botão); aqui a borda passa ao acento a 60 %, a cor de foco do web, para a
 * escolha ser visível num telefone (desvio declarado).
 */
@Composable
fun BotaoFantasma(
    texto: String,
    aoClicar: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icone: Int? = null,
    habilitado: Boolean = true,
    carregando: Boolean = false,
    selecionado: Boolean = false,
) {
    OutlinedButton(
        onClick = aoClicar,
        modifier = modifier,
        enabled = habilitado && !carregando,
        shape = CircleShape,
        contentPadding = RespiroDoBotao,
        border = BorderStroke(1.dp, if (selecionado) Tema.cores.acento.copy(alpha = 0.6f) else Tema.cores.botaoFantasmaBorda),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selecionado) Tema.cores.acentoSuave else Tema.cores.botaoFantasmaFundo,
            contentColor = Tema.cores.botaoFantasmaTexto,
        ),
    ) {
        ConteudoDoBotao(texto, icone, carregando, Tema.cores.botaoFantasmaTexto)
    }
}

/**
 * `.metric-card`: a sobrelinha e o valor em negrito; [marca] é o `testTag` do valor.
 * Lado a lado, os cartões têm a mesma altura, como na grade do web.
 */
@Composable
fun LinhaDeMetricas(conteudo: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = conteudo,
    )
}

@Composable
fun CartaoDeMetrica(sobrelinha: String, valor: String, marca: String, modifier: Modifier = Modifier) {
    Cartao(modifier = modifier.fillMaxHeight()) {
        Sobrelinha(sobrelinha)
        Text(
            text = valor,
            modifier = Modifier.testTag(marca),
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = Tema.cores.texto,
        )
    }
}

/** `.result-empty`: itálico, centralizado, cinza, com folga. */
@Composable
fun VazioDeResultado(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        fontStyle = FontStyle.Italic,
        fontSize = 14.5.sp,
        lineHeight = 23.sp,
        textAlign = TextAlign.Center,
        color = Tema.cores.textoApagado,
    )
}

/** Legenda de uma linha (`<small style="color: #64748b">`). */
@Composable
fun Legenda(texto: String, modifier: Modifier = Modifier) {
    Text(text = texto, modifier = modifier, fontSize = 13.sp, lineHeight = 18.sp, color = Tema.cores.textoFraco)
}

/**
 * A linha de lista do web, que é um `.ghost-button` de largura cheia
 * (sessões recentes, artefatos): pílula, fundo do acento a 10 %, borda a
 * 24 %, e a escolha com a borda de foco, como [BotaoFantasma].
 */
@Composable
fun LinhaEscolhivel(
    aoClicar: () -> Unit,
    marca: String,
    modifier: Modifier = Modifier,
    selecionada: Boolean = false,
    conteudo: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = aoClicar,
        modifier = modifier
            .fillMaxWidth()
            .testTag(marca),
        shape = CircleShape,
        color = if (selecionada) Tema.cores.acentoSuave else Tema.cores.botaoFantasmaFundo,
        contentColor = Tema.cores.texto,
        border = BorderStroke(1.dp, if (selecionada) Tema.cores.acento.copy(alpha = 0.6f) else Tema.cores.botaoFantasmaBorda),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = conteudo,
        )
    }
}

/** O `<pre>` do web: monoespaçado, quebrando linha. */
@Composable
fun TextoPreformatado(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto,
        modifier = modifier.fillMaxWidth(),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = Tema.cores.texto,
    )
}

/** O `<label>` de um `.field-group`: 12 sp, peso 600, `#5f6368`. */
@Composable
fun RotuloDeCampo(texto: String, modifier: Modifier = Modifier) {
    Text(text = texto, modifier = modifier, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tema.cores.pilulaTexto)
}

/**
 * `.field-group`: o rótulo pequeno (`#5f6368`, peso 600) sobre o campo branco
 * de borda a 12 %; o foco é a borda do acento a 60 %, como no web.
 */
@Composable
fun CampoDeTexto(
    valor: String,
    aoMudar: (String) -> Unit,
    rotulo: String,
    modifier: Modifier = Modifier,
    exemplo: String? = null,
    linhas: Int = 1,
    senha: Boolean = false,
    teclado: KeyboardType = KeyboardType.Text,
    habilitado: Boolean = true,
    ajuda: String? = null,
    marca: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RotuloDeCampo(rotulo)
        OutlinedTextField(
            value = valor,
            onValueChange = aoMudar,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (marca != null) Modifier.testTag(marca) else Modifier),
            enabled = habilitado,
            placeholder = exemplo?.let { { Text(it, fontSize = 13.5.sp, color = Tema.cores.textoApagado) } },
            singleLine = linhas == 1,
            minLines = linhas,
            maxLines = if (linhas == 1) 1 else maxOf(linhas, 12),
            visualTransformation = if (senha) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (senha) KeyboardType.Password else teclado,
                autoCorrectEnabled = !senha,
            ),
            shape = RoundedCornerShape(Tema.formas.campo),
            textStyle = TextStyle(fontSize = 13.5.sp, color = Tema.cores.texto),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tema.cores.acento.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.Black.copy(alpha = 0.12f),
                focusedContainerColor = Tema.cores.superficie,
                unfocusedContainerColor = Tema.cores.superficie,
            ),
        )
        if (ajuda != null) Legenda(ajuda)
    }
}
