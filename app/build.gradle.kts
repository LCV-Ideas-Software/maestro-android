plugins {
    id("com.android.application")
}

android {
    namespace = "dev.lcv.maestro"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lcv.maestro"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing is injected by the publishing workflow through the
    // android.injected.signing.* properties, so no key material and no
    // password is ever written into this repository.
}
