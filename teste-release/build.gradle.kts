// O teste do aplicativo minificado pelo R8: instala o build `minificado` do
// `:app` (o release, assinado com a chave de depuração) e o percorre de fora do
// processo, pelo UI Automator, como a pessoa usaria. É o tipo oficial de módulo
// para testar o build de release (`com.android.test` com `targetProjectPath`),
// o mesmo da amostra de Macrobenchmark e do `:teste-release` da
// calculadora-android. Roda no emulador gerenciado do portão local, como os
// testes instrumentados do `:app` (a CI não tem emulador; decisão do operador,
// #97).
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "dev.lcv.maestro.teste.release"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // O par do `minificado` do `:app`: o mesmo nome escolhe a variante a
        // instalar; a chave de depuração é a mesma dos dois lados.
        create("minificado") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    // O teste roda no próprio processo, e não no do aplicativo: é o que deixa o
    // aplicativo minificado de verdade, sem o código de teste dentro dele.
    experimentalProperties["android.experimental.self-instrumenting"] = true

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

// Só a variante `minificado` existe: um `debug` aqui instalaria o aplicativo sem o R8.
androidComponents {
    beforeVariants(selector().all()) { variante ->
        variante.enable = variante.buildType == "minificado"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
}
