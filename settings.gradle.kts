pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "maestro-android"

include(":app")
include(":core:protocolo")
include(":core:provedores")
include(":core:seguranca")
include(":core:sessao")
