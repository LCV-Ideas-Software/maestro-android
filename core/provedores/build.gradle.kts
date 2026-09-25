// Os seis provedores de IA e a rede da auditoria de links: Kotlin puro, sem
// dependência de Android. Fala com dinheiro alheio, com seis contratos externos
// e com a rede do usuário, e por isso roda na JVM, onde cada requisição é
// conferida contra um servidor falso a cada mudança. A chave chega por
// `FonteDeChave`; quem a implementa sobre o Keystore é o `:core:seguranca`
// (especificação, seção 4). As regras puras da auditoria de links vêm do
// `:core:protocolo`; este módulo implementa as interfaces de rede dele
// (especificação, seções 2.2 e 5.4).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Mesmo JDK usado pela CI e pela publicação.
    jvmToolchain(17)
}

dependencies {
    // `api`, e não `implementation`: o construtor público do
    // `ClienteDeProvedores` recebe um `OkHttpClient`, e quem usa o módulo
    // precisa do tipo para chamá-lo.
    api(libs.okhttp)
    // `api` também: os tipos das interfaces (`RegistroDeEvidencia`,
    // `AnalisadorDeUrl`) aparecem nas assinaturas públicas deste módulo.
    api(project(":core:protocolo"))
    implementation(libs.okhttp.coroutines)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.crawler.commons)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testRuntimeOnly(libs.slf4j.nop)
    testImplementation(libs.kotlinx.coroutines.test)
    // O Gradle não adiciona o launcher da JUnit Platform sozinho; sem ele os
    // testes não são descobertos.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
