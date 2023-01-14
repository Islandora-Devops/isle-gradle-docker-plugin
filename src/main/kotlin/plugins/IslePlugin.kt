package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withGroovyBuilder
import java.io.ByteArrayOutputStream

@Suppress("unused")
class IslePlugin : Plugin<Project> {

    companion object {
        fun String.normalizeDockerTag() = this.replace("""[^a-zA-Z0-9._-]""".toRegex(), "-")

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

        val Project.tag: Property<String>
            get() = memoizedProperty {
                try {
                    execCaptureOutput(
                        listOf("git", "describe", "--exact-match", "--tags", "HEAD"),
                        "HEAD is not a tag."
                    )
                }
                catch (e: Exception) {
                    ""
                }
            }

        // Latest is true if HEAD is a tag and that tag has the highest semantic value.
        val Project.latest: Property<Boolean>
            get() = memoizedProperty {
                val tags = execCaptureOutput(listOf("git", "tag", "-l", "*.*.*", "--sort=version:refname"),  "Could not get tags.")
                    .lines()
                    .filter {
                        !it.contains("-") // Exclude alpha, betas, etc
                    }
                tags.last() == tag.get()
            }

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
        apply<DockerHubPlugin>()
        apply<BuildPlugin>()
        apply<ReportsPlugin>()
        apply<TestsPlugin>()

        // Return repository to initial "clean" state.
        tasks.register<Delete>("clean") {
            group = "Isle"
            description = "Destroy absolutely everything"
            delete(layout.buildDirectory)
            dependsOn("pruneBuildCache", "destroyBuilder", "destroyRegistryVolume", "destroyRegistryNetwork")
        }

        extensions.findByName("buildScan")?.withGroovyBuilder {
            setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
            setProperty("termsOfServiceAgree", "yes")
        }

        // Make all build directories relative to the root, only supports projects up to a depth of one for now.
        subprojects {
            buildDir = rootProject.buildDir.resolve(projectDir.relativeTo(rootDir))
            layout.buildDirectory.set(buildDir)
        }
    }
}

inline fun <reified T> Project.memoizedProperty(crossinline function: () -> T): Property<T> {
    val property = objects.property<T>()
    val value: T by lazy { function() }
    property.set(value)
    property.disallowChanges()
    property.finalizeValueOnRead()
    return property
}
