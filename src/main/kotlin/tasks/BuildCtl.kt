package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.property
import plugins.BuildKitPlugin.Companion.buildKitBuilder
import plugins.BuildKitPlugin.Companion.buildKitExecutable

abstract class BuildCtl : DefaultTask() {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val executable = project.objects.fileProperty().convention(project.buildKitExecutable)

    @Input
    val builder = project.objects.property<String>().convention(project.buildKitBuilder)

    @get:Internal
    val executablePath: String
        get() = executable.get().asFile.absolutePath

    @get:Internal
    val hostEnvironmentVariables: Map<String, String>
        get() = mapOf("BUILDKIT_HOST" to "docker-container://${builder.get()}")
}