plugins {
    alias(libs.plugins.android.application) apply false
    // O Android Gradle Plugin 9 traz o Kotlin embutido com uma versão mínima do
    // Kotlin Gradle Plugin; declarar o KGP aqui fixa a versão do catálogo para
    // todos os módulos, inclusive o `:core:protocolo`, que é JVM puro.
    alias(libs.plugins.kotlin.jvm) apply false
}
