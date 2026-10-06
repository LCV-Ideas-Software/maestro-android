package dev.lcv.maestro.protocolo

/**
 * A [IntegridadeDeLinks.UrlAnalisada] dos dublês do parser: a leitura pelo `java.net.URI` do próprio motor
 * ([IntegridadeDeLinks.analisarPorUri]), a mesma que o analisador real usa para o esquema que não é http nem https.
 * Serve para exercitar as regras em volta do parser, e não o parser: a forma serializada é a própria URL. [ipDoHost]
 * é o que um teste de rede quer fingir.
 */
internal fun urlDeTeste(url: String, ipDoHost: ByteArray? = null): IntegridadeDeLinks.UrlAnalisada? =
    IntegridadeDeLinks.analisarPorUri(url)?.copy(ipDoHost = ipDoHost)
