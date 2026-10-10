// O estado do Maestro AI e, na entrega seguinte, a deliberação que roda no
// aparelho (especificação, seções 4 a 4.3). É biblioteca Android porque o Room
// e o WorkManager são API de plataforma; o que decide se um turno é válido
// continua no `:core:protocolo`, e o que fala com a rede, no `:core:provedores`.
// A 3a trouxe o Room, as configurações, os artefatos, os tetos e a retomada do
// estado circular; a 3b, a deliberação (`Deliberacao`), o worker, a
// reconciliação na abertura e a notificação.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.lcv.maestro.sessao"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
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

// O esquema exportado entra nos assets dos testes instrumentados: o
// `MigrationTestHelper` do Room abre um banco na versão antiga a partir dele e
// valida a migração automática (PR 4a, esquema v3). API de variantes do AGP 9.
androidComponents {
    onVariants { variante ->
        variante.androidTest?.sources?.assets?.addStaticSourceDirectory("$projectDir/schemas")
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
    // `CoroutineWorker`, `ForegroundInfo`, `WorkManager.enqueueUniqueWork`.
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jackson.databind)

    // `kotlin("test")` só escolhe a variante da JUnit 5 sozinho em módulo JVM
    // puro; numa biblioteca Android a variante tem de ser dita.
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // O cliente HTTP de produção dos provedores contra um servidor local, na
    // JVM: a resposta que passa dos 10 s de leitura padrão do OkHttp.
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // O cliente dos provedores contra um servidor local, pelo construtor
    // público com endereço: os quatro casos de custo contam requisições.
    androidTestImplementation(libs.okhttp.mockwebserver)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
