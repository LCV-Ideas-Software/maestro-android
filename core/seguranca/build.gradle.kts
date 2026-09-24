// A chave de API de cada provedor, guardada neste aparelho e em nenhum outro
// lugar (especificação, seção 6). É biblioteca Android porque o Keystore é API
// de plataforma; implementa a `FonteDeChave` do `:core:provedores`, que por
// isso continua Kotlin puro e testado na JVM (seção 4).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.lcv.maestro.seguranca"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Os testes deste módulo são instrumentados: o Keystore só existe em
    // aparelho ou emulador. O emulador é gerenciado pelo próprio Android
    // Gradle Plugin (Gradle Managed Devices, a solução oficial do Google), e é
    // o mesmo na CI e na máquina de quem desenvolve. Decisão do operador de
    // 23/09/2026: os casos 2 a 5 da seção 8 rodam aqui, em toda PR. O caso 1
    // exige o hardware de StrongBox, que o emulador não tem, e não há aparelho
    // com ele disponível; o teste existe e só roda onde houver o hardware.
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

dependencies {
    // `api`: quem recebe um `CofreDeChaves` o usa como `FonteDeChave`.
    api(project(":core:provedores"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Só para os testes: este é o único módulo com emulador na CI, e as
    // expressões regulares do `:core:protocolo` precisam ser provadas na ICU
    // do aparelho, não só na JVM (ProtocoloNoAparelhoTest).
    androidTestImplementation(project(":core:protocolo"))
}
