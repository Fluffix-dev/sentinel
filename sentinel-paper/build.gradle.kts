plugins {
    id("java")
    id("com.gradleup.shadow") version "9.1.0"
}

group = "dev.fluffix.sentinel"
version = "1.0.0" // oder aus root übernehmen

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper nur bereitstellen, NICHT schaden
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    // Lombok compileOnly + annotationProcessor
    compileOnly("org.projectlombok:lombok:1.18.38")
    annotationProcessor("org.projectlombok:lombok:1.18.38")

    // --- RUNTIME libs, die INS JAR SOLLEN ---
    implementation(project(":sentinel-slave"))

    // Moderne Hikari (keine Javassist-Proxies mehr)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Neuer MySQL/J-Connector (für Java 21)
    implementation("com.mysql:mysql-connector-j:8.4.0")

    // Jackson (deine Version ist ok; hier komplettheitshalber core/annotations dazu)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.gradleup.shadow:shadow-gradle-plugin:9.1.0")
    }
}

// `apply plugin` stuff are used with `buildscript`.
apply(plugin = "java")
apply(plugin = "com.gradleup.shadow")

