import java.text.SimpleDateFormat
import java.util.Date

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "ca.islandora"

repositories {
    gradlePluginPortal()
}

dependencies {
    implementation("com.bmuschko:gradle-docker-plugin:6.4.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.named<Jar>("jar") {
    manifest {
        attributes["Implementation-Title"] = "Islandora Gradle Docker plugin"
        attributes["Implementation-Version"] = project.version
        attributes["Built-By"] = System.getProperty("user.name")
        attributes["Built-Date"] = SimpleDateFormat("MM/dd/yyyy").format(Date())
        attributes["Built-JDK"] = System.getProperty("java.version")
        attributes["Built-Gradle"] = gradle.gradleVersion
    }
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY")}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}