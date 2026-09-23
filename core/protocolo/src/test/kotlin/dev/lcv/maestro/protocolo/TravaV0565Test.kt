package dev.lcv.maestro.protocolo

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.fail

/**
 * Os 33 casos que a trava canônica ganhou depois da versão portada: 31 na
 * `maestro-app#395` (v00.05.65) e 2 na `#396` (MAESTRO-31), em
 * `editorial_content_lock.rs` 1012–1606 no `2633767`. MAEANDR-17.
 *
 * Cada caso roda como um agente que segue o prompt **deste** aplicativo o
 * mandaria: com o registro de procedência verdadeiro. Recusar só porque o
 * registro falta não provaria nada, porque falharia por um motivo que o
 * canônico não tem. Por isso cada recusa confere o trecho do motivo.
 *
 * Três grupos, e o nome de cada caso diz a que grupo ele pertence:
 *
 * - **igual**: o Kotlin decide como o canônico, pela mesma regra. Inclui as
 *   regras de forma adotadas nesta issue: relatório sempre objeto, nome de
 *   campo exato, `block_id` exato e presente no manifesto, `change_type` com
 *   tokens exatos, sem lista vazia nem repetida, e `protocol_basis` com texto.
 * - **coberto pelo registro**: o canônico recusa porque não sabe de onde veio
 *   um bloco a mais (regra de fonte local, limite de `new_block_count`,
 *   crescimento entre blocos editados da #396). Com o registro essa origem é
 *   declarada, e o Kotlin aprova. O resíduo é o da Discussion #41: a
 *   declaração pode mentir.
 * - **mais estrito pelo registro**: o canônico deixa um bloco editado mudar de
 *   lugar sem `reorder`, porque não vê o movimento; o registro vê.
 */
class TravaV0565Test {

    private val q = "\n\n"

    private fun recusa(antes: String, depois: String, relatorio: String, trecho: String) {
        when (val v = TravaDeConteudo.validarRevisao(antes, depois, relatorio)) {
            is TravaDeConteudo.Veredito.Violada -> assertContains(v.motivo, trecho)
            TravaDeConteudo.Veredito.Aprovada -> fail("esperava recusa ($trecho), e a trava aprovou")
        }
    }

    private fun aprova(antes: String, depois: String, relatorio: String) {
        val v = TravaDeConteudo.validarRevisao(antes, depois, relatorio)
        if (v is TravaDeConteudo.Veredito.Violada) fail("esperava aprovação, e a trava recusou: ${v.motivo}")
    }

    /**
     * Acrescenta `revised_block_origins` a um relatório. Origem `null` é
     * acréscimo; origem terminada em `+` é pedaço extra de divisão. Os dois
     * levam `protocol_basis` próprio, como o prompt pede.
     */
    private fun comRegistro(relatorio: String, vararg blocos: Pair<String, String?>): String {
        val itens = blocos.joinToString(",") { (prefixo, origem) ->
            when {
                origem == null ->
                    """{"prefix":"$prefixo","origin":"addition","protocol_basis":"required context"}"""
                origem.endsWith("+") ->
                    """{"prefix":"$prefixo","origin":"${origem.dropLast(1)}","protocol_basis":"split piece"}"""
                else -> """{"prefix":"$prefixo","origin":"$origem"}"""
            }
        }
        val abre = relatorio.indexOf('{')
        return relatorio.substring(0, abre + 1) + "\"revised_block_origins\":[$itens]," +
            relatorio.substring(abre + 1)
    }

    private val tres = "Primeiro.${q}Segundo.${q}Terceiro."

    // -- Igual ao canônico ------------------------------------------------------

    @Test
    fun `igual - prosa no motivo nao autoriza reordenar`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"edit","reason":"removed punctuation","protocol_basis":"structure"},
            {"block_id":"B0002","change_type":"edit","reason":"nothing moved","protocol_basis":"structure"}]}"""
        recusa("Primeiro.${q}Segundo.", "Segundo.${q}Primeiro.",
            comRegistro(relatorio, "Segundo." to "B0002", "Primeiro." to "B0001"), "must each declare change_type reorder")
    }

    @Test
    fun `igual - protocol_basis dentro do motivo nao autoriza editar`() {
        val relatorio = """{"changed_blocks":[{"block_id":"B0001","reason":"protocol_basis: not supplied"}]}"""
        recusa("Original.", "Revisado.", comRegistro(relatorio, "Revisado." to "B0001"), "must include protocol_basis")
    }

    @Test
    fun `igual - acrescimo negado no motivo nao concede crescimento`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"edit","reason":"no addition was made","protocol_basis":"style"}]}"""
        recusa("Original.", "Original.${q}Novo.",
            comRegistro(relatorio, "Original." to "B0001", "Novo." to null), "without declaring change_type split/addition")
    }

    @Test
    fun `igual - relatorio e sempre um objeto JSON estrito, ate sem mudanca`() {
        for (relatorio in listOf(
            "Resumo das mudanças: {\"changed_blocks\":[]}",
            "changed_blocks:\n  - block_id: B0001",
            "{\"changed_blocks\":[]} trailing",
            "[[]]",
            "",
        )) {
            recusa("Original.", "Original.", relatorio, "strict JSON object")
        }
    }

    @Test
    fun `igual - B10000 pode ser declarado`() {
        val antes = (1..10_000).joinToString(q) { "Bloco $it." }
        val depois = antes.substringBeforeLast(q) + "${q}Bloco 10000 revisado."
        val registro = (1..9_999).map { "Bloco $it." to "B" + it.toString().padStart(4, '0') } +
            listOf("Bloco 10000 revisado." to "B10000")
        val relatorio = """{"changed_blocks":[{"block_id":"B10000","protocol_basis":"editorial correction"}]}"""
        aprova(antes, depois, comRegistro(relatorio, *registro.toTypedArray()))
    }

    @Test
    fun `igual - chave e aspa escapada no motivo nao quebram os campos`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","reason":"literal { and \"quoted } text\"","protocol_basis":"correction"}]}"""
        aprova("Original.", "Revisado.", comRegistro(relatorio, "Revisado." to "B0001"))
    }

    @Test
    fun `igual - declaracao repetida e campo repetido fecham`() {
        val entradas = """{"changed_blocks":[
            {"block_id":"B0001","protocol_basis":"correction"},
            {"block_id":"B0001","change_type":"addition","protocol_basis":"correction"}]}"""
        val campo = """{"changed_blocks":[{"block_id":"B0001","block_id":"B0002","protocol_basis":"correction"}]}"""
        recusa("Original.", "Revisado.", comRegistro(entradas, "Revisado." to "B0001"), "duplicate changed_blocks declaration for B0001")
        recusa("Original.", "Revisado.", campo, "strict JSON object")
    }

    @Test
    fun `igual - campo repetido em metadado tambem fecha`() {
        recusa("Original.", "Original.", """{"changed_blocks":[],"metadata":{"note":"first","note":"second"}}""",
            "Duplicate field 'note'")
    }

    @Test
    fun `igual - protocol_basis estruturado precisa de folha com texto`() {
        for (base in listOf("[null]", """[null, {"note": "  "}]""", """{"note": false}""", "7")) {
            val relatorio = """{"changed_blocks":[{"block_id":"B0001","protocol_basis":$base}]}"""
            recusa("Original.", "Revisado.", comRegistro(relatorio, "Revisado." to "B0001"), "must include protocol_basis")
        }
    }

    @Test
    fun `igual - bloco fora do manifesto nao concede crescimento`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B9999","change_type":"addition","protocol_basis":"required context"}]}"""
        recusa("Original.", "Original.${q}Novo.",
            comRegistro(relatorio, "Original." to "B0001", "Novo." to null), "B9999 is absent from the received manifest")
    }

    @Test
    fun `igual - linha so com espacos separa blocos`() {
        val relatorio = """{"changed_blocks":[{"block_id":"B0002","protocol_basis":"correction"}]}"""
        aprova("Primeiro.\n   \nSegundo.", "Primeiro.\n   \nSegundo revisado.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Segundo revisado." to "B0002"))
    }

    @Test
    fun `igual - forma de changed_blocks segue o serde do canonico`() {
        val registro = arrayOf<Pair<String, String?>>("Revisado." to "B0001")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"block_id":"B1","protocol_basis":"x"},{"block_id":"B0001","protocol_basis":"c"}]}""",
            *registro), "invalid changed_blocks block_id B1")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"block_id":"b0001","protocol_basis":"c"}]}""", *registro),
            "invalid changed_blocks block_id b0001")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"protocol_basis":"x"},{"block_id":"B0001","protocol_basis":"c"}]}""", *registro),
            "must declare block_id as a string")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":["B0001"]}""", *registro), "must be a JSON object")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"block_id":"B0001","change_type":[],"protocol_basis":"c"}]}""", *registro),
            "empty change_type for B0001")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"block_id":"B0001","change_type":["edit","edit"],"protocol_basis":"c"}]}""", *registro),
            "duplicate change_type for B0001")
        recusa("Original.", "Revisado.", comRegistro(
            """{"changed_blocks":[{"block_id":"B0001","change_type":7,"protocol_basis":"c"}]}""", *registro),
            "must be a string or a list of strings")
    }

    @Test
    fun `igual - origem do registro tambem e copiada exatamente`() {
        // O registro não existe no canônico, mas o identificador que ele leva
        // é o mesmo do manifesto, e segue a mesma regra de `block_id`.
        val relatorio = """{"changed_blocks":[{"block_id":"B0001","protocol_basis":"correction"}]}"""
        for (origem in listOf("b0001", " B0001", "Addition")) {
            recusa("Original.", "Revisado.",
                comRegistro(relatorio, "Revisado." to origem), "which is neither a block ID nor \"addition\"")
        }
    }

    @Test
    fun `igual - relatorio malformado e recusado antes da ambiguidade`() {
        // O canônico lê o relatório antes de olhar os blocos. Com blocos
        // iguais no texto recebido, o motivo tem de ser o relatório.
        recusa("Alpha${q}Alpha", "Novo${q}Alpha", "not json", "strict JSON object")
    }

    @Test
    fun `igual - so o token exato autoriza`() {
        // Sinônimo e lista escrita num texto só não autorizam: é por onde a
        // prosa voltaria a conceder permissão.
        for (tipo in listOf("\"moved\"", "\"Reorder\"", "\"edit, reorder\"")) {
            val relatorio = """{"changed_blocks":[
                {"block_id":"B0001","change_type":$tipo,"protocol_basis":"o"},
                {"block_id":"B0002","change_type":$tipo,"protocol_basis":"o"}]}"""
            recusa("Primeiro.${q}Segundo.", "Segundo.${q}Primeiro.",
                comRegistro(relatorio, "Segundo." to "B0002", "Primeiro." to "B0001"), "must each declare change_type reorder")
        }
    }

    @Test
    fun `igual - nome de campo com outra caixa nao e a secao`() {
        val relatorio = """{"Changed_Blocks":[{"block_id":"B0001","protocol_basis":"correction"}]}"""
        recusa("Original.", "Revisado.", comRegistro(relatorio, "Revisado." to "B0001"), "without matching")
    }

    @Test
    fun `igual - acrescimo e reordenacao juntos no mesmo bloco`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"reorder","protocol_basis":"structure"},
            {"block_id":"B0002","change_type":["addition","reorder"],"protocol_basis":"structure and context"}],"custody":"revised"}"""
        aprova("Primeiro.${q}Segundo.", "Novo.${q}Segundo.${q}Primeiro.",
            comRegistro(relatorio, "Novo." to null, "Segundo." to "B0002", "Primeiro." to "B0001"))
    }

    @Test
    fun `igual - divisao declarada em bloco alheio nao autoriza acrescimo`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0003","change_type":"split","protocol_basis":"required context"}],"custody":"revised"}"""
        recusa(tres, "Primeiro.${q}Novo.${q}Segundo.${q}Terceiro.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Novo." to null, "Segundo." to "B0002", "Terceiro." to "B0003"),
            "without declaring change_type split/addition")
    }

    @Test
    fun `igual - edicao movida nao se disfarca de acrescimo distante`() {
        // Com o registro verdadeiro, o movimento do B0003 aparece, e falta
        // `reorder`: o Kotlin recusa pelo motivo de fato.
        val relatorio = """{"custody":"revised","changed_blocks":[
            {"block_id":"B0003","protocol_basis":"editorial correction"},
            {"block_id":"B0004","change_type":"addition","new_block_count":2,"protocol_basis":"required context"}]}"""
        recusa("# Titulo${q}Primeiro.${q}Segundo.${q}Terceiro.", "# Titulo${q}Primeiro.${q}Terceiro.${q}Novo.${q}Segundo editado.",
            comRegistro(relatorio, "# Titulo" to "B0001", "Primeiro." to "B0002", "Terceiro." to "B0004",
                "Novo." to null, "Segundo editado." to "B0003"),
            "reordered received blocks B0003, B0004 must")
    }

    @Test
    fun `igual - acrescimo depois do bloco editado usa aquele recebido`() {
        val relatorio = """{"custody":"revised","changed_blocks":[
            {"block_id":"B0002","change_type":"addition","new_block_count":1,"protocol_basis":"required context"}]}"""
        aprova(tres, "Primeiro.${q}Segundo revisado.${q}Novo.${q}Terceiro.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Segundo revisado." to "B0002", "Novo." to null, "Terceiro." to "B0003"))
    }

    @Test
    fun `igual - duplicata editada com separador estavel`() {
        val relatorio = """{"custody":"revised","changed_blocks":[{"block_id":"B0004","protocol_basis":"editorial correction"}]}"""
        aprova("Início.${q}Repetido.${q}Meio.${q}Repetido.${q}Fim.", "Início.${q}Repetido.${q}Meio.${q}Revisado.${q}Fim.",
            comRegistro(relatorio, "Início." to "B0001", "Repetido." to "B0002", "Meio." to "B0003",
                "Revisado." to "B0004", "Fim." to "B0005"))
    }

    @Test
    fun `igual - exclusao de duplicata separada mantem o ID recebido`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0002","protocol_basis":"remove unsupported passage"},
            {"block_id":"B0003","protocol_basis":"remove repeated passage"}]}"""
        aprova("Alpha${q}Bravo${q}Alpha${q}Charlie", "Alpha${q}Charlie",
            comRegistro(relatorio, "Alpha" to "B0001", "Charlie" to "B0004"))
    }

    @Test
    fun `igual - duas edicoes entre vizinhos reordenados nao sao crescimento`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"reorder","protocol_basis":"required order"},
            {"block_id":"B0002","protocol_basis":"correction"},
            {"block_id":"B0003","protocol_basis":"correction"},
            {"block_id":"B0004","change_type":"reorder","protocol_basis":"required order"}]}"""
        aprova("A${q}B${q}C${q}D", "D${q}B2${q}C2${q}A",
            comRegistro(relatorio, "D" to "B0004", "B2" to "B0002", "C2" to "B0003", "A" to "B0001"))
    }

    @Test
    fun `igual - acrescimo no fim nao reatribui a edicao de duplicata anterior`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","protocol_basis":"correction"},
            {"block_id":"B0004","change_type":"addition","protocol_basis":"new closing context"}]}"""
        aprova("A${q}X${q}A${q}Y", "N${q}X${q}A${q}Y${q}Z",
            comRegistro(relatorio, "N" to "B0001", "X" to "B0002", "A" to "B0003", "Y" to "B0004", "Z" to null))
    }

    @Test
    fun `igual - toda duplicata pode sair quando cada uma e declarada`() {
        aprova("A${q}A", "", """{"changed_blocks":[
            {"block_id":"B0001","protocol_basis":"remove unsupported text"},
            {"block_id":"B0002","protocol_basis":"remove unsupported text"}]}""")
    }

    @Test
    fun `igual - duplicata com edicao declarada no bloco errado e recusada`() {
        // O canônico recusa por ambiguidade. Com o registro não há ambiguidade:
        // se o B0001 foi editado, falta declará-lo; se foi o B0002, a cópia
        // intacta do B0001 trocou de lugar com ele.
        val relatorio = """{"changed_blocks":[{"block_id":"B0002","protocol_basis":"correction"}]}"""
        recusa("Alpha${q}Alpha", "Novo${q}Alpha", comRegistro(relatorio, "Novo" to "B0001", "Alpha" to "B0002"),
            "changed received blocks B0001 without matching")
        recusa("Alpha${q}Alpha", "Novo${q}Alpha", comRegistro(relatorio, "Novo" to "B0002", "Alpha" to "B0001"),
            "reordered received blocks B0001, B0002 must")
    }

    // -- Coberto pelo registro ----------------------------------------------

    private val acrescimoNoMeio = "Primeiro.${q}Novo.${q}Segundo.${q}Terceiro."

    @Test
    fun `coberto - acrescimo declarado em bloco nao adjacente`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0003","change_type":"addition","protocol_basis":"required context"}],"custody":"revised"}"""
        aprova(tres, acrescimoNoMeio,
            comRegistro(relatorio, "Primeiro." to "B0001", "Novo." to null, "Segundo." to "B0002", "Terceiro." to "B0003"))
    }

    @Test
    fun `coberto - uma entrada de acrescimo com dois blocos novos`() {
        // O limite de `new_block_count` do canônico; aqui cada acréscimo tem
        // a própria linha no registro e a própria `protocol_basis`.
        val registro = arrayOf<Pair<String, String?>>("Original." to "B0001", "Novo um." to null, "Novo dois." to null)
        aprova("Original.", "Original.${q}Novo um.${q}Novo dois.", comRegistro(
            """{"changed_blocks":[{"block_id":"B0001","change_type":"addition","protocol_basis":"required context"}]}""",
            *registro))
        aprova("Original.", "Original.${q}Novo um.${q}Novo dois.", comRegistro(
            """{"changed_blocks":[{"block_id":"B0001","change_type":"addition","new_block_count":2,"protocol_basis":"required context"}]}""",
            *registro))
    }

    @Test
    fun `coberto - acrescimo ao lado de bloco editado`() {
        val relatorio = """{"custody":"revised","changed_blocks":[
            {"block_id":"B0001","change_type":"addition","new_block_count":1,"protocol_basis":"required context"},
            {"block_id":"B0002","protocol_basis":"editorial correction"}]}"""
        aprova(tres, "Primeiro.${q}Novo.${q}Segundo revisado.${q}Terceiro.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Novo." to null, "Segundo revisado." to "B0002", "Terceiro." to "B0003"))
    }

    @Test
    fun `coberto - ancora anterior e acrescimo depois de bloco editado`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"addition","protocol_basis":"context"},
            {"block_id":"B0002","protocol_basis":"correction"}]}"""
        aprova(tres, "Primeiro.${q}Segundo editado.${q}Novo.${q}Terceiro.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Segundo editado." to "B0002", "Novo." to null, "Terceiro." to "B0003"))
    }

    @Test
    fun `coberto - MAESTRO-31, crescimento entre dois blocos editados`() {
        // Os dois casos que a #396 acrescentou. O canônico recusa porque não
        // tem como saber qual dos dois editados produziu o bloco a mais; o
        // registro diz que nenhum produziu: é acréscimo.
        val registro = arrayOf<Pair<String, String?>>("A" to "B0001", "B2" to "B0002", "C2" to "B0003", "N" to null)
        aprova("A${q}B${q}C", "A${q}B2${q}C2${q}N", comRegistro("""{"changed_blocks":[
            {"block_id":"B0002","change_type":"addition","protocol_basis":"required context"},
            {"block_id":"B0003","protocol_basis":"correction"}]}""", *registro))
        aprova("A${q}B${q}C", "A${q}B2${q}C2${q}N", comRegistro("""{"changed_blocks":[
            {"block_id":"B0002","protocol_basis":"correction"},
            {"block_id":"B0003","change_type":"addition","protocol_basis":"required context"}]}""", *registro))
    }

    @Test
    fun `coberto - duas fontes adjacentes de acrescimo`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"addition","protocol_basis":"context"},
            {"block_id":"B0002","change_type":"addition","protocol_basis":"context"}]}"""
        aprova("Primeiro.${q}Segundo.", "Primeiro.${q}Novo.${q}Segundo.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Novo." to null, "Segundo." to "B0002"))
    }

    @Test
    fun `coberto - limite de crescimento local`() {
        val relatorio = """{"changed_blocks":[
            {"block_id":"B0001","change_type":"addition","protocol_basis":"context"},
            {"block_id":"B0003","change_type":"addition","protocol_basis":"context"}]}"""
        aprova(tres, "Primeiro.${q}Novo um.${q}Novo dois.${q}Segundo.${q}Terceiro.",
            comRegistro(relatorio, "Primeiro." to "B0001", "Novo um." to null, "Novo dois." to null,
                "Segundo." to "B0002", "Terceiro." to "B0003"))
    }

    // -- Mais estrito pelo registro -----------------------------------------

    @Test
    fun `mais estrito - divisao entre vizinhos reordenados exige reorder no dividido`() {
        val antes = tres
        val depois = "Terceiro.${q}Primeiro revisado.${q}Complemento do primeiro.${q}Segundo."
        val registro = arrayOf<Pair<String, String?>>("Terceiro." to "B0003", "Primeiro revisado." to "B0001",
            "Complemento do primeiro." to "B0001+", "Segundo." to "B0002")
        val vizinhos = """{"block_id":"B0002","change_type":"reorder","protocol_basis":"required order"},
            {"block_id":"B0003","change_type":"reorder","protocol_basis":"required order"}"""
        recusa(antes, depois, comRegistro("""{"custody":"revised","changed_blocks":[
            {"block_id":"B0001","change_type":"split","new_block_count":1,"protocol_basis":"required split"},$vizinhos]}""",
            *registro), "reordered received blocks B0001 must")
        aprova(antes, depois, comRegistro("""{"custody":"revised","changed_blocks":[
            {"block_id":"B0001","change_type":["split","reorder"],"protocol_basis":"required split"},$vizinhos]}""",
            *registro))
    }

    @Test
    fun `mais estrito - edicao entre vizinhos reordenados exige reorder no editado`() {
        val depois = "Terceiro.${q}Primeiro revisado.${q}Segundo."
        val registro = arrayOf<Pair<String, String?>>("Terceiro." to "B0003", "Primeiro revisado." to "B0001", "Segundo." to "B0002")
        val vizinhos = """{"block_id":"B0002","change_type":"reorder","protocol_basis":"required order"},
            {"block_id":"B0003","change_type":"reorder","protocol_basis":"required order"}"""
        recusa(tres, depois, comRegistro("""{"custody":"revised","changed_blocks":[
            {"block_id":"B0001","protocol_basis":"editorial correction"},$vizinhos]}""", *registro),
            "reordered received blocks B0001 must")
        aprova(tres, depois, comRegistro("""{"custody":"revised","changed_blocks":[
            {"block_id":"B0001","change_type":"reorder","protocol_basis":"editorial correction"},$vizinhos]}""", *registro))
    }

    @Test
    fun `mais estrito - edicao que passa para antes do prefixo exige reorder`() {
        val registro = arrayOf<Pair<String, String?>>("B2" to "B0002", "A" to "B0001", "C" to "B0003")
        recusa("A${q}B${q}C", "B2${q}A${q}C",
            comRegistro("""{"changed_blocks":[{"block_id":"B0002","protocol_basis":"correction"}]}""", *registro),
            "reordered received blocks B0001, B0002 must")
        aprova("A${q}B${q}C", "B2${q}A${q}C", comRegistro("""{"changed_blocks":[
            {"block_id":"B0001","change_type":"reorder","protocol_basis":"order"},
            {"block_id":"B0002","change_type":"reorder","protocol_basis":"correction"}]}""", *registro))
    }
}
