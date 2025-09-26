plugins {
    id("java")
    id("com.gradleup.shadow") version "9.1.0"

}

group = "dev.fluffix.sentinel"
version = version

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.zaxxer:HikariCP:2.3.2")
    implementation("mysql:mysql-connector-java:5.1.13")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")

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

