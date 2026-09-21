plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.lcv.maestro"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lcv.maestro"
        // Android 14. Mesma decisão do operador de 19/09/2026 na
        // calculadora-android, e o que torna o StrongBox e o vínculo com
        // autenticação do usuário universalmente disponíveis — ver a seção 6.2
        // de `docs/especificacao-v1.md`.
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing is injected by the publishing workflow through the
    // android.injected.signing.* properties, so no key material and no
    // password is ever written into this repository.
}
