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
    // O relatório do agente é JSON por contrato. Ler JSON com varredura própria
    // foi o defeito que custou quatro rodadas de revisão nesta entrega: um
    // varredor escrito à mão discorda da especificação em algum lugar, sempre,
    // e num portão de integridade cada discordância é uma autorização indevida.
    implementation(libs.jackson.databind)
    // O mesmo vale para o HTML dentro do Markdown do texto final: a auditoria
    // de citações o reconhece pela especificação CommonMark, não por varredura
    // própria.
    implementation(libs.commonmark)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    // O Gradle não adiciona o launcher da JUnit Platform sozinho; sem ele os
    // testes não são descobertos.
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
