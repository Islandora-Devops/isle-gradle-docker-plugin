version = "0.5"
group = "ca.islandora"

plugins {
    id("com.gradle.plugin-publish") version "0.15.0"
    `java-gradle-plugin`
    `kotlin-dsl`
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.4.31"
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.bmuschko:gradle-docker-plugin:7.1.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.3")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.12.3")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

gradlePlugin {
    plugins {
        create("IsleDocker") {
            id = "IsleDocker"
            implementationClass = "IsleDocker"
        }
    }
}

// The configuration example below shows the minimum required properties
// configured to publish your plugin to the plugin portal
pluginBundle {
    website = "https://islandora.ca/"
    vcsUrl = "https://github.com/Islandora-Devops/isle-gradle-docker-plugin"
    description = "Gradle plugin that supports building interdependent Docker images with Buildkit support for the Isle project."
    tags = listOf("isle", "islandora", "docker")
    (plugins) {
        "IsleDocker" {
            displayName = "Docker build plugin for the Islandora Isle project"
        }
    }
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}
