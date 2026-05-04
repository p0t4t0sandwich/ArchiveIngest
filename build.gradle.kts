plugins {
    id("java")
    id("com.gradleup.shadow") version("9.4.1")
}

group = "dev.neuralnexus.archiveingest"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}



tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.neuralnexus.archiveingest.Main"
    }
    archiveFileName.set("archiveingest-${version}-dev.jar")
}

tasks.shadowJar {
    archiveFileName.set("archiveingest-${version}.jar")
}

repositories {
    maven("https://maven.neuralnexus.dev/mirror")
}

dependencies {
    compileOnly("org.jspecify:jspecify:1.0.0")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("it.unimi.dsi:fastutil:8.5.18")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.xerial:sqlite-jdbc:3.50.2.0")

    // zst archives
    implementation("com.github.luben:zstd-jni:1.5.7-8:linux_amd64")
}
