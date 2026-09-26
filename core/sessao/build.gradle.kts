// O estado do Maestro AI e, na entrega seguinte, a deliberação que roda no
// aparelho (especificação, seções 4 a 4.3). É biblioteca Android porque o Room
// e o WorkManager são API de plataforma; o que decide se um turno é válido
// continua no `:core:protocolo`, e o que fala com a rede, no `:core:provedores`.
// Esta entrega (3a) traz o Room, as configurações, os artefatos, os tetos e a
// retomada do estado circular; a orquestração (3b) vem depois.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.lcv.maestro.sessao"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Os testes de Room rodam num emulador gerenciado pelo próprio Android
    // Gradle Plugin, o mesmo do `:core:seguranca`, em toda PR (especificação,
    // seção 8): banco em arquivo temporário, nunca em memória, porque o caso
    // que mais importa é a retomada depois de o processo morrer.
    testOptions {
        managedDevices {
            localDevices {
                create("api37") {
                    device = "Pixel 6"
                    apiLevel = 37
                    systemImageSource = "google"
                }
            }
        }
    }
}

room {
    // O esquema exportado fica no repositório: é o que uma migração futura
    // compara, e o que a revisão lê para conferir uma coluna.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // `api`: os tipos do protocolo e dos provedores (`LinhaDeLink`, `Coleta`,
    // `Provedor`, `RegistroDeEvidencia`) aparecem nas assinaturas públicas.
    api(project(":core:provedores"))
    // `api`: o cofre responde se a chave de cada provedor está configurada, e
    // quem monta o repositório de configurações precisa do tipo.
    api(project(":core:seguranca"))
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Só declarado nesta entrega: o `CoroutineWorker` entra na 3b.
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.databind)

    // `kotlin("test")` só escolhe a variante da JUnit 5 sozinho em módulo JVM
    // puro; numa biblioteca Android a variante tem de ser dita.
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
