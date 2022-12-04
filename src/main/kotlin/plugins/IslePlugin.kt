package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withGroovyBuilder
import java.io.ByteArrayOutputStream

@Suppress("unused")
class IslePlugin : Plugin<Project> {

    companion object {
        // Capture stdout from running a command.
        fun Project.execCaptureOutput(command: List<String>, error: String) = ByteArrayOutputStream().use { output ->
            val result = this.exec {
                standardOutput = output
                commandLine = command
            }
            if (result.exitValue != 0) throw RuntimeException(error)
            output.toString()
        }.trim()

        // Check if the project should have docker related tasks.
        val Project.isDockerProject: Boolean
            get() = projectDir.resolve("Dockerfile").exists()
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        apply<BuildKitPlugin>()
        apply<ReportsPlugin>()
        apply<TestsPlugin>()

        extensions.findByName("buildScan")?.withGroovyBuilder {
            setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
            setProperty("termsOfServiceAgree", "yes")
        }

        // Return repository to initial "clean" state.
        tasks.register<Delete>("clean") {
            group = "Isle"
            description = "Destroy absolutely everything"
            delete(layout.buildDirectory)
            dependsOn("destroyBuilderVolume", "destroyRegistryVolume")
        }

        allprojects {
            // Defaults for all tasks created by this plugin.
            tasks.configureEach {
                val displayOutputExceptions = listOf("diskUsage", "prune")
                if (group?.startsWith("Isle") == true && !displayOutputExceptions.contains(name)) {
                    logging.captureStandardOutput(LogLevel.INFO)
                    logging.captureStandardError(LogLevel.INFO)
                }
            }
        }

        // Make all build directories relative to the root, only supports projects up to a depth of one for now.
        subprojects {
            buildDir = rootProject.buildDir.resolve(projectDir.relativeTo(rootDir))
            layout.buildDirectory.set(buildDir)
        }
    }
}