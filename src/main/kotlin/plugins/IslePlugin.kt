package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withGroovyBuilder
import plugins.BuildKitPlugin.Companion.normalizeDockerTag
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

        val Project.commit: Property<String>
            get() = memoizedProperty {
                execCaptureOutput(listOf("git", "rev-parse", "HEAD"), "Failed to get commit hash.")
            }

        val Project.branch: Property<String>
            get() = memoizedProperty {
                execCaptureOutput(
                    listOf("git", "rev-parse", "--abbrev-ref", "HEAD"),
                    "Failed to get branch."
                ).normalizeDockerTag()
            }

        val Project.sourceDateEpoch: Property<String>
            get() = memoizedProperty {
                execCaptureOutput(listOf("git", "log", "-1", "--pretty=%ct"), "Failed to get the date of HEAD")
            }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        apply<BuildKitPlugin>()
        apply<ReportsPlugin>()
        apply<TestsPlugin>()
        apply<DockerHubPlugin>()

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

private inline fun <reified T> Project.memoizedProperty(crossinline function: () -> T): Property<T> {
    val property = objects.property<T>().convention(provider {
        function()
    })
    property.disallowChanges()
    property.finalizeValueOnRead()
    return property
}
