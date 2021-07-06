package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.property
import utils.DockerCommandOptions
import utils.DockerCommandOptions.Option

// Wrapper around a call to `docker buildx create`, please refer to the documentation for more information:
// https://github.com/docker/buildx#documentation
open class DockerBuilder : DefaultTask() {

    @Suppress("unused")
    class Options constructor(objects: ObjectFactory) : DockerCommandOptions {
        // Append a node to builder instead of changing it
        @Input
        @Optional
        @Option("--append")
        val append = objects.property<Boolean>().convention(false)

        // Override the configured builder instance
        @Input
        @Optional
        @Option("--builder")
        val builder = objects.property<String>()

        // Flags for buildkitd daemon
        @Input
        @Optional
        @Option("--buildkitd-flags")
        val buildkitdFlags = objects.property<String>()

        // BuildKit config file
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        @Optional
        @Option("--config")
        val config = objects.fileProperty()

        // Driver to use (available: [docker docker-container kubernetes])
        @Input
        @Optional
        @Option("--driver")
        val driver = objects.property<String>()

        // Options for the driver
        @Input
        @Optional
        @Option("--driver-opt")
        val driverOpts = objects.property<String>()

        // Remove a node from builder instead of changing it
        @Input
        @Optional
        @Option("--leave")
        val leave = objects.property<Boolean>().convention(false)

        // Builder instance name
        @Input
        @Optional
        @Option("--name")
        val name = objects.property<String>()

        // Create/modify node with given name
        @Input
        @Optional
        @Option("--node")
        val node = objects.property<String>()

        // Fixed platforms for current node
        @Input
        @Optional
        @Option("--platform")
        val platform = objects.property<String>()

        @Input
        @Optional
        @Option("--use")
        val use = objects.property<Boolean>()
    }

    @Nested
    val options = Options(project.objects)

    @TaskAction
    fun exec() {
        project.exec {
            workingDir = project.projectDir
            commandLine = listOf("docker", "buildx", "create") + options.toList()
        }
    }
}