pluginManagement {
    repositories {
        maven("https://maven.neuralnexus.dev/mirror")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
}

rootProject.name = "archiveingest"
