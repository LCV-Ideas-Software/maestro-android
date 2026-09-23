// Os seis provedores de IA: Kotlin puro, sem dependência de Android. Fala com
// dinheiro alheio e com seis contratos externos, e por isso roda na JVM, onde
// cada corpo de requisição é conferido contra um servidor falso a cada mudança.
// A chave chega por `FonteDeChave`; quem a implementa sobre o Keystore é o
// `:core:seguranca` (especificação, seção 4).
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
    implementation(libs.okhttp.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.databind)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // O Gradle não adiciona o launcher da JUnit Platform sozinho; sem ele os
    // testes não são descobertos.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
