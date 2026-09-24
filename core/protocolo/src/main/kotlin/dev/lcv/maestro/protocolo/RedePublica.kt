package dev.lcv.maestro.protocolo

/**
 * A defesa de rede da auditoria de links: quais endereços o aplicativo se
 * recusa a buscar. Porte de `maestro-app/src-tauri/src/link_audit.rs`
 * (`public_http_url_rejection_reason`, `is_blocked_link_audit_ip*`, linhas
 * 188–330 em `68528f9`).
 *
 * **O modelo de ameaça é o inverso do canônico** (especificação, seção 5.4).
 * No desktop e no web a regra protege a infraestrutura de quem roda o
 * serviço. No telefone, não há metadados de nuvem a vazar, mas há a rede
 * doméstica do usuário: um `http://192.168.0.1` auditado a partir do aparelho
 * seria o aplicativo varrendo o roteador de quem o instalou. As faixas
 * bloqueadas são as mesmas; o motivo de existirem é proteger a rede do
 * usuário.
 *
 * A resolução de nomes é do sistema e chega por [ResolvedorDeNomes]; o IP
 * literal chega já decodificado pelo parser de URL ([IntegridadeDeLinks.UrlAnalisada.ipDoHost]),
 * para que nenhum DNS rode por acidente.
 */
public object RedePublica {

    /** Resolve um nome em endereços (4 ou 16 bytes); `null` quando não resolve. */
    public fun interface ResolvedorDeNomes {
        public fun resolver(host: String): List<ByteArray>?
    }

    /**
     * `public_http_url_rejection_reason`: o motivo da recusa, ou `null` se a
     * URL é pública. Falha de resolução conta como não bloqueada, como no
     * canônico — a conexão falharia de todo modo, e a coleta usa um
     * resolvedor que recusa por conta própria.
     */
    public fun motivoDeRecusa(
        url: String,
        analisador: IntegridadeDeLinks.AnalisadorDeUrl,
        resolvedor: ResolvedorDeNomes,
    ): String? {
        val analisada = analisador.analisar(url) ?: return "URL invalida ou incompleta"
        if (analisada.esquema != "http" && analisada.esquema != "https") {
            return "somente links http:// ou https:// podem ser auditados"
        }
        val host = analisada.host?.let(EspacoUnicode::caixaBaixaAscii) ?: return "link sem host/dominio"
        if (host == "localhost" || host == "localhost.localdomain" ||
            host.endsWith(".localhost") || host.endsWith(".local")
        ) {
            return "endereco local bloqueado por seguranca"
        }
        val ip = analisada.ipDoHost
        if (ip != null) {
            if (ipBloqueado(ip)) return "IP privado, reservado ou local bloqueado por seguranca"
        } else if (resolvedor.resolver(host)?.any(::ipBloqueado) == true) {
            return "dominio resolve para IP privado/reservado bloqueado por seguranca"
        }
        return null
    }

    /** `is_blocked_link_audit_ip`: 4 bytes são IPv4; 16, IPv6. */
    public fun ipBloqueado(endereco: ByteArray): Boolean = when (endereco.size) {
        4 -> ipv4Bloqueado(endereco.map { it.toInt() and 0xFF })
        16 -> ipv6Bloqueado(endereco)
        else -> true
    }

    /** `is_blocked_link_audit_ipv4`. */
    private fun ipv4Bloqueado(o: List<Int>): Boolean =
        o[0] == 0 ||
            o[0] == 10 ||
            o[0] == 127 ||
            (o[0] == 100 && o[1] in 64..127) ||
            (o[0] == 169 && o[1] == 254) ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 192 && o[1] == 0 && o[2] == 0) ||
            (o[0] == 192 && o[1] == 0 && o[2] == 2) ||
            (o[0] == 198 && o[1] in 18..19) ||
            (o[0] == 198 && o[1] == 51 && o[2] == 100) ||
            (o[0] == 203 && o[1] == 0 && o[2] == 113) ||
            o[0] >= 224

    /** `is_blocked_link_audit_ipv6`. */
    private fun ipv6Bloqueado(bytes: ByteArray): Boolean {
        val segmentos = IntArray(8) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }
        val v4 = { listOf(bytes[12], bytes[13], bytes[14], bytes[15]).map { it.toInt() and 0xFF } }
        // `is_loopback` (::1) e `is_unspecified` (::).
        if (segmentos.take(7).all { it == 0 } && (segmentos[7] == 1 || segmentos[7] == 0)) return true
        // `to_ipv4_mapped`: ::ffff:a.b.c.d.
        if (segmentos.take(5).all { it == 0 } && segmentos[5] == 0xFFFF) return ipv4Bloqueado(v4())
        // IPv4 compatível (::a.b.c.d), que o canônico também desembrulha.
        if (segmentos.take(5).all { it == 0 } && segmentos[5] == 0) return ipv4Bloqueado(v4())
        val primeiro = segmentos[0]
        return (primeiro and 0xFE00) == 0xFC00 ||
            (primeiro and 0xFFC0) == 0xFE80 ||
            (primeiro and 0xFF00) == 0xFF00 ||
            (segmentos[0] == 0x2001 && segmentos[1] == 0x0DB8)
    }
}
