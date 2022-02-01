package utils

import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.provideDelegate
import java.io.ByteArrayOutputStream

// Finds the parent project which the plugin is applied.
fun Project.dockerPluginProject() = generateSequence(this) { it.parent }
    .plus(this)
    .filterNotNull()
    .first { project ->
        project.extra.has("isDockerBuild")
    }

// Computes a set of image tags for the given repository.
fun Project.imageTags(repository: String): Set<String> {
    val dockerTags: Set<String> by dockerPluginProject().extra
    return dockerTags.map { "$repository/$name:$it" }.toSet()
}

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
