// Protocolo editorial: Kotlin puro, sem dependência de Android. É onde vivem a
// trava de conteúdo aprovado e a leitura do relatório do agente — a parte que
// decide se um turno é válido —, e a fronteira existe para que ela rode na JVM,
// em milissegundos, a cada mudança.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Mesmo JDK usado pela CI e pela publicação.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    // O Gradle não adiciona o launcher da JUnit Platform sozinho; sem ele os
    // testes não são descobertos.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
