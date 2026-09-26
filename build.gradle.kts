plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // O Android Gradle Plugin 9 traz o Kotlin embutido com uma versão mínima do
    // Kotlin Gradle Plugin; declarar o KGP aqui fixa a versão do catálogo para
    // todos os módulos, inclusive o `:core:protocolo`, que é JVM puro.
    alias(libs.plugins.kotlin.jvm) apply false
    // O `:core:sessao` gera os DAOs do Room com o KSP e exporta o esquema pelo
    // plugin oficial do Room; declarados aqui, sem aplicar, pela mesma razão.
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
