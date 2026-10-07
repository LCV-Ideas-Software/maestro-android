// A interface do Maestro AI Android (quarta entrega, especificação, seções
// 2.3, 4.1, 4.4, 6 e 8): Jetpack Compose sobre os quatro módulos `:core:*`. A
// `Fabrica` do `:core:sessao` é a raiz de composição (decisão 18 do operador,
// 28/09/2026: sem Hilt); este módulo só apresenta, coleta e liga o worker, a
// permissão de notificações e a autenticação do Keystore ao que o núcleo já
// decide.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.lcv.maestro"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lcv.maestro"
        // Android 16: nenhum aplicativo *-android abaixo dele (decisão do
        // operador, 04/10/2026, que superou a de 19/09/2026, Android 14), e o
        // que torna o StrongBox e o vínculo com autenticação do usuário
        // universalmente disponíveis — ver a seção 6.2 de
        // `docs/especificacao-v1.md`.
        minSdk = 36
        targetSdk = 37
        // 1.0.0, a primeira publicação na Play (#100). O versionCode 1 foi o do
        // bundle de 17/09/2026 (MAEANDR-9) na faixa interna; o 2, o primeiro
        // bundle da 1.0.0, foi ao rascunho da produção sem o R8 e é trocado por
        // este antes da revisão (#104). A Play exige sempre um maior.
        versionCode = 3
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        // `BuildConfig.VERSION_NAME` é a versão que vai no `User-Agent` da
        // auditoria de links (`AgenteDeColeta`): o núcleo não conhece o manifesto.
        buildConfig = true
    }

    // As telas rodam no mesmo emulador gerenciado dos módulos `:core:seguranca`
    // e `:core:sessao` (especificação, seção 8): ViewModel sobre o Room real em
    // arquivo temporário, cofre e agendador substituídos por dublês, sem rede.
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

    buildTypes {
        release {
            // R8 no build publicado (#104): reduz, otimiza e ofusca o código e os
            // recursos, e grava o `r8.json` e o mapeamento no metadado do bundle,
            // de onde a Play tira as porcentagens e desofusca as falhas. É a DSL
            // do AGP 9.3+, que já inclui as regras padrão do Android. Room,
            // WorkManager, OkHttp, Compose, DataStore e kotlinx.serialization
            // trazem as suas regras. O Jackson não traz, e o aplicativo o usa só
            // pelo modelo de árvore (`readTree`, `ObjectNode`), sem ligar JSON a
            // classes próprias, o que o `:teste-release` confere no minificado;
            // o aplicativo não usa reflexão própria.
            optimization {
                enable = true
            }
        }
        // O release minificado, assinado com a chave de depuração, só para o
        // `:teste-release` instalar e percorrer de fora do processo (padrão
        // oficial dos módulos de teste do build de release). A otimização é
        // repetida aqui de propósito: o teste vale para o que a Play recebe.
        create("minificado") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            optimization {
                enable = true
            }
        }
    }

    // Release signing is injected by the publishing workflow through the
    // android.injected.signing.* properties, so no key material and no
    // password is ever written into this repository.
}

/**
 * Os textos que a tela de licenças mostra são os próprios arquivos do
 * repositório (a AGPL exige que a licença acompanhe o programa, os avisos de
 * terceiros têm de viajar com o binário, e a Apache-2.0 exige entregar uma
 * cópia do seu texto). Copiá-los para dentro de `app/src/main/assets` criaria
 * uma segunda versão; esta tarefa os leva aos assets no build, pela API
 * oficial de fontes geradas do AGP.
 */
abstract class ReunirLicencas : DefaultTask() {
    @get:InputFiles
    abstract val arquivos: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destino: DirectoryProperty

    @TaskAction
    fun executar() {
        val pasta = destino.get().asFile
        // A pasta espelha a lista: um texto que saiu dela não pode sobrar no APK de um build incremental.
        pasta.deleteRecursively()
        pasta.mkdirs()
        arquivos.forEach { origem ->
            origem.copyTo(destino.file(origem.name).get().asFile, overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variante ->
        val tarefa = tasks.register<ReunirLicencas>(
            "reunirLicencas" + variante.name.replaceFirstChar { it.uppercase() },
        ) {
            arquivos.from(
                rootProject.file("LICENSE"),
                rootProject.file("NOTICE"),
                rootProject.file("THIRDPARTY.md"),
                rootProject.file("LICENSES/Apache-2.0.txt"),
            )
        }
        variante.sources.assets?.addGeneratedSourceDirectory(tarefa, ReunirLicencas::destino)
    }
}

dependencies {
    // O `:core:sessao` reexporta (`api`) os provedores e o cofre, e por eles o protocolo.
    implementation(project(":core:sessao"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.core)
    // `Configuration.Provider` do WorkManager e o `WorkManager.getInstance` do teste de arranque.
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // A tela de licenças lê o `THIRDPARTY.md` com a implementação de referência do CommonMark.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)

    // A variante JUnit 5 do kotlin-test tem de ser explícita num módulo
    // Android: só o plugin kotlin.jvm a escolhe sozinho pela plataforma.
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)

    // Os testes de Compose usam JUnit 4, que é o que o executor instrumentado
    // do AndroidX entende; não conflita com o JUnit 5 dos testes de JVM.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
