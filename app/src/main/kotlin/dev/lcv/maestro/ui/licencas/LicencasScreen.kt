/*
 * Copyright © 2026 LCV Ideas & Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package dev.lcv.maestro.ui.licencas

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lcv.maestro.R
import dev.lcv.maestro.ui.Cartao
import dev.lcv.maestro.ui.Tema
import dev.lcv.maestro.ui.TextoPreformatado
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text as TextoMd
import org.commonmark.parser.Parser

/**
 * A AGPL exige que a licença acompanhe o programa, os avisos de terceiros
 * viajam com o binário e a Apache-2.0 exige entregar uma cópia do seu texto:
 * os quatro textos são os próprios arquivos do repositório, levados aos assets
 * pelo build (convenção da calculadora-android), sem cópia versionada que
 * possa divergir.
 *
 * O `THIRDPARTY.md` é Markdown com tabelas e é lido pela implementação de
 * referência (commonmark-java com a extensão de tabelas GFM); a tela só
 * percorre a árvore. O `NOTICE`, o `LICENSE` e o texto da Apache-2.0 são
 * texto puro, quebrado a 80 colunas: os parágrafos são os do arquivo
 * (separados por linha em branco), e as quebras de dentro deles viram espaço,
 * para o texto correr na largura do telefone em vez de fazer ziguezague.
 */
@Composable
fun LicencasScreen() {
    val contexto = LocalContext.current
    val secoes = remember {
        listOf(
            R.string.licencas_aviso to textoPuro(lerAsset(contexto, "NOTICE")),
            R.string.licencas_terceiros to markdown(lerAsset(contexto, "THIRDPARTY.md")),
            R.string.licencas_aplicativo to textoPuro(lerAsset(contexto, "LICENSE")),
            R.string.licencas_apache to textoPuro(lerAsset(contexto, "Apache-2.0.txt")),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Tema.espacos.lateral, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreCartoes),
    ) {
        secoes.forEach { (titulo, blocos) ->
            Column(verticalArrangement = Arrangement.spacedBy(Tema.espacos.entreLinhas)) {
                Text(stringResource(titulo), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Tema.cores.texto)
                HorizontalDivider(color = Tema.cores.cartaoBorda)
                blocos.forEach { Desenhar(it) }
            }
        }
    }
}

@Composable
private fun Desenhar(bloco: Bloco) {
    when (bloco) {
        is Bloco.Titulo -> Text(bloco.texto, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
        // Justificado, com hifenização: numa coluna estreita, justificar sem ela abre rios de espaço.
        is Bloco.Paragrafo -> Text(
            text = bloco.texto,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = Tema.cores.textoFraco,
            style = androidx.compose.ui.text.TextStyle(
                textAlign = TextAlign.Justify,
                hyphens = Hyphens.Auto,
                lineBreak = LineBreak.Paragraph,
            ),
        )
        is Bloco.Codigo -> TextoPreformatado(bloco.texto)
        // Uma tabela larga não cabe num telefone: cada linha vira um cartão com o
        // nome em destaque e os outros campos embaixo, rotulados pelo cabeçalho.
        is Bloco.Registro -> Cartao {
            Text(bloco.titulo, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Tema.cores.texto)
            bloco.campos.forEach { (rotulo, valor) ->
                Column {
                    Text(rotulo, fontSize = 11.sp, lineHeight = 14.sp, color = Tema.cores.textoApagado)
                    Text(valor, fontSize = 12.sp, lineHeight = 16.sp, color = Tema.cores.texto)
                }
            }
        }
    }
}

private sealed interface Bloco {
    data class Titulo(val texto: String) : Bloco
    data class Paragrafo(val texto: String) : Bloco
    data class Codigo(val texto: String) : Bloco
    data class Registro(val titulo: String, val campos: List<Pair<String, String>>) : Bloco
}

/** Texto puro: parágrafos separados por linha em branco, cada um numa linha só. */
private fun textoPuro(texto: String): List<Bloco> =
    texto.lines()
        .fold(mutableListOf(mutableListOf<String>())) { paragrafos, linha ->
            if (linha.isBlank()) paragrafos.add(mutableListOf()) else paragrafos.last().add(linha.trim())
            paragrafos
        }
        .filter { it.isNotEmpty() }
        .map { Bloco.Paragrafo(it.joinToString(" ")) }

private val LEITOR: Parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()

/** O Markdown pela commonmark-java; a tela percorre os blocos de primeiro nível e os de dentro de listas e citações. */
private fun markdown(texto: String): List<Bloco> {
    val blocos = mutableListOf<Bloco>()
    filhos(LEITOR.parse(texto)).forEach { blocos += blocosDe(it) }
    return blocos
}

private fun blocosDe(no: Node): List<Bloco> = when (no) {
    is Heading -> listOf(Bloco.Titulo(textoDe(no)))
    is Paragraph -> listOf(Bloco.Paragrafo(textoDe(no)))
    is FencedCodeBlock -> listOf(Bloco.Codigo(no.literal.trimEnd()))
    is IndentedCodeBlock -> listOf(Bloco.Codigo(no.literal.trimEnd()))
    is HtmlBlock -> listOf(Bloco.Codigo(no.literal.trimEnd()))
    is BlockQuote -> filhos(no).flatMap(::blocosDe)
    is ListBlock -> filhos(no).filterIsInstance<ListItem>().flatMap { item ->
        filhos(item).flatMap(::blocosDe).map { bloco -> if (bloco is Bloco.Paragrafo) bloco.copy(texto = "• ${bloco.texto}") else bloco }
    }
    is TableBlock -> tabela(no)
    else -> emptyList()
}

/** O cabeçalho rotula cada campo; a primeira célula de cada linha do corpo é o nome. */
private fun tabela(no: TableBlock): List<Bloco> {
    val cabecalho = filhos(no).filterIsInstance<TableHead>().firstOrNull()
        ?.let { filhos(it).filterIsInstance<TableRow>().firstOrNull() }
        ?.let { filhos(it).map(::textoDe) }
        .orEmpty()
    val linhas = filhos(no).filterIsInstance<TableBody>().flatMap { filhos(it).filterIsInstance<TableRow>() }
    return linhas.map { linha ->
        val celulas = filhos(linha).map(::textoDe)
        Bloco.Registro(
            titulo = celulas.firstOrNull().orEmpty(),
            campos = celulas.drop(1)
                .mapIndexed { indice, valor -> cabecalho.getOrElse(indice + 1) { "" } to valor }
                .filter { (rotulo, valor) -> rotulo.isNotEmpty() && valor.isNotEmpty() },
        )
    }
}

private fun filhos(no: Node): List<Node> = generateSequence(no.firstChild) { it.next }.toList()

/** O texto de um nó como se lê: literais e código, a quebra suave como espaço, a forte como quebra de linha. */
private fun textoDe(no: Node): String {
    val saida = StringBuilder()
    no.accept(
        object : AbstractVisitor() {
            override fun visit(text: TextoMd) {
                saida.append(text.literal)
            }

            override fun visit(code: Code) {
                saida.append(code.literal)
            }

            override fun visit(softLineBreak: SoftLineBreak) {
                saida.append(' ')
            }

            override fun visit(hardLineBreak: HardLineBreak) {
                saida.append('\n')
            }
        },
    )
    return saida.toString().trim()
}

private fun lerAsset(contexto: Context, nome: String): String =
    contexto.assets.open(nome).bufferedReader().use { it.readText() }
