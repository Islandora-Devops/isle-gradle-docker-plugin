package tasks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.RootFS
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.ContainerConfig
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import utils.DockerCommandOptions
import utils.DockerCommandOptions.Option
import utils.dockerPluginProject
import utils.imageTags

// Wrapper around a call to `docker buildx build`, please refer to the documentation for more information:
// https://github.com/docker/buildx#documentation
open class DockerBuild : DefaultTask() {

    // Not actually the image digest but rather an approximation that ignores timestamps, etc.
    // So we do not build/test unless the image has actually changed, it only checks contents & configuration.
    data class ApproximateDigest(val config: ContainerConfig, val rootFS: RootFS)

    @Suppress("unused")
    class Options constructor(objects: ObjectFactory) : DockerCommandOptions {
        // Add a custom host-to-IP mapping (host:ip)
        @Input
        @Optional
        @Option("--add-host")
        val addHosts = objects.listProperty<String>()

        // Allow extra privileged entitlement, e.g. network.host, security.insecure
        @Input
        @Optional
        @Option("--allow")
        val allows = objects.listProperty<String>()

        // Set build-time variables
        @Input
        @Optional
        @Option("--build-arg")
        val buildArgs = objects.mapProperty<String, String>()

        // Override the configured builder instance
        @Input
        @Optional
        @Option("--builder")
        val builder = objects.property<String>()

        // External cache sources (eg. user/app:cache,type=local,src=path/to/dir)
        @Input
        @Optional
        @Option("--cache-from")
        val cacheFrom = objects.setProperty<String>()

        // Cache export destinations (eg. user/app:cache,type=local,dest=path/to/dir)
        @Input
        @Optional
        @Option("--cache-to")
        val cacheTo = objects.setProperty<String>()

        // Name of the Dockerfile (Default is 'PATH/Dockerfile')
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        @Optional
        @Option("--file")
        val dockerFile = objects.fileProperty()

        // Write the image ID to the file
        @OutputFile
        @Option("--iidfile")
        val imageIdFile = objects.fileProperty()

        // --label stringArray
        // Set metadata for an image
        @Input
        @Optional
        @Option("--label")
        val labels = objects.mapProperty<String, String>()

        // Shorthand for --output=type=docker
        @Input
        @Optional
        @Option("--load")
        val load = objects.property<Boolean>().convention(false)

        // Set the networking mode for the RUN instructions during build (default "default")
        @Input
        @Optional
        @Option("--network")
        val network = objects.property<String>()

        // Do not use cache when building the image
        @Input
        @Optional
        @Option("--no-cache")
        val noCache = objects.property<Boolean>().convention(false)

        // Output destination (format: type=local,dest=path)
        @Input
        @Optional
        @Option("--output")
        val output = objects.listProperty<String>()

        // Set target platform for build
        @Input
        @Optional
        @Option("--platform")
        val platforms = objects.listProperty<String>()

        // Set type of progress output (auto, plain, tty)
        // Use plain to show container output
        @Input
        @Optional
        @Option("--progress")
        val progress = objects.property<String>().convention("plain")

        // Always attempt to pull a newer version of the image
        @Input
        @Optional
        @Option("--pull")
        val pull = objects.property<Boolean>().convention(false)

        // Shorthand for --output=type=registry
        @Input
        @Optional
        @Option("--push")
        val push = objects.property<Boolean>().convention(false)

        // Secret file to expose to the build: id=mysecret,src=/local/secret
        @Input
        @Optional
        @Option("--secret")
        val secrets = objects.listProperty<String>()

        // SSH agent socket or keys to expose to the build (format: default|<id>[=<socket>|<key>[,<key>]])
        @Input
        @Optional
        @Option("--ssh")
        val ssh = objects.listProperty<String>()

        // Name and optionally a tag in the 'name:tag' format
        @Input
        @Optional
        @Option("--tag")
        val tags = objects.listProperty<String>()

    }

    @Nested
    val options = Options(project.objects)

    // PATH (i.e. Docker build context)
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    val context = project.objects.fileTree().setDir(project.layout.projectDirectory)

    // A json file whose contents can be used to uniquely identify an image by contents. We do not actually need to
    // generate a hash as Gradle will do that when computing dependencies between tasks. This is only used to prevent
    // building / testing when the upstream images have not changed.
    @OutputFile
    val digest = project.objects.fileProperty().convention(project.layout.buildDirectory.file("${name}-digest.json"))

    // Gets list of images names without repository or tag, required to build this image.
    // This assumes all images are building within this project.
    private val requiredImages = options.dockerFile.map { file ->
        file.asFile.readText().let { text ->
            ("\\" + '$' + """\{repository\}/(?<image>[^:@]+)""")
                .toRegex()
                .findAll(text)
                .map { it.groups["image"]!!.value }
                .toSet()
        }
    }

    // The approximate digests of images required to build this one. This is only used to prevent building / testing
    // when the upstream images have not changed.
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @Suppress("unused")
    val sourceImageDigests = project.objects.listProperty<RegularFile>().convention(
        requiredImages.map { images ->
            images.mapNotNull { image ->
                dockerBuildTasks(name)[image]?.get()?.digest?.get()
            }
        }
    )

    init {
        // Exclude changes to files/directories mentioned in the Docker ignore file.
        val ignore = context.dir.resolve(".dockerignore")
        if (ignore.exists()) {
            ignore.readLines().forEach { line ->
                context.exclude(line)
            }
        }

        options.run {
            // Get project properties used to set defaults.
            val pluginProject = project.dockerPluginProject()
            val buildPlatforms: Set<String> by pluginProject.extra
            val cacheFromEnabled: Boolean by pluginProject.extra
            val cacheToEnabled: Boolean by pluginProject.extra
            val cacheFromRepositories: Set<String> by pluginProject.extra
            val cacheToRepositories: Set<String> by pluginProject.extra
            val cacheToMode: String by pluginProject.extra
            val noBuildCache: Boolean by pluginProject.extra
            val isDockerBuild: Boolean by pluginProject.extra

            // Assume docker file is in the project directory.
            dockerFile.convention(project.layout.projectDirectory.file("Dockerfile"))
            // We always want to generate an imageIdFile if applicable i.e. when --load is specified.
            imageIdFile.convention(project.layout.buildDirectory.file("${name}-imageId.txt"))
            // It is not possible to use --platform with "docker" builder.
            if (!isDockerBuild) {
                platforms.convention(buildPlatforms)
            }
            // Load if no platform is given as it will be the host platform so we can safely `load`.
            // Also cannot load while pushing: https://github.com/docker/buildx/issues/177
            load.convention(platforms.map { !push.get() && it.isEmpty() })
            if (!noBuildCache) {
                // Cache from user provided repositories.
                if (cacheFromEnabled) {
                    cacheFrom.convention(
                        cacheFromRepositories.map { repository ->
                            "type=registry,ref=$repository/${project.name}:cache"
                        }
                    )
                }
                // Cache to registry or cache inline.
                if (cacheToEnabled) {
                    cacheTo.convention(
                        cacheToRepositories.map { repository ->
                            when (cacheToMode) {
                                "min", "max" -> "type=registry,mode=${cacheToMode},ref=$repository/${project.name}:cache"
                                "inline" -> "type=inline"
                                else -> throw RuntimeException("Unknown cacheToMode $cacheToMode")
                            }
                        }
                    )
                }
            }
            // Enable / disable the local build cache.
            noCache.convention(noBuildCache)
        }

        // Check that another process has not removed the image since it was last built.
        outputs.upToDateWhen { task -> (task as DockerBuild).imagesExist() }

        // Enforce build ordering.
        this.dependsOn(
            requiredImages.map { images ->
                images.mapNotNull { image ->
                    dockerBuildTasks(name)[image]
                }
            }
        )
    }

    // Get list of all DockerBuild tasks with the given name.
    private fun dockerBuildTasks(name: String) = project.rootProject.allprojects
        .filter { it.projectDir.resolve("Dockerfile").exists() }.associate { project ->
            project.name to project.tasks.named<DockerBuild>(name)
        }

    // Checks if all images denoted by the given tag(s) exists locally.
    private fun imagesExist(): Boolean {
        val dockerClient: DockerClient by project.dockerPluginProject().extra
        return options.tags.get().all { tag ->
            try {
                dockerClient.inspectImageCmd(tag).exec()
                true
            } catch (e: NotFoundException) {
                false
            }
        }
    }

    // --iidfile is only generated when the image is exported to `docker images`
    // i.e. with `--load` is specified.
    private val shouldCreateImageIdFile
        get() = options.load.get()

    // Execute the Docker build command.
    private fun build() {
        val exclude = if (!shouldCreateImageIdFile) listOf("--iidfile") else emptyList()
        val options = options.toList(exclude)
        project.exec {
            workingDir = context.dir
            commandLine = listOf("docker", "buildx", "build") + options + listOf(context.dir.absolutePath)
        }
    }

    // "push and load may not be set together at the moment", so we must manually pull after building.
    // Only applies to when driver is not set to `docker`.
    private fun pull() {
        val pluginProject = project.dockerPluginProject()
        val isDockerBuild: Boolean by pluginProject.extra
        val isLocalRepository: Boolean by pluginProject.extra
        if (!isDockerBuild) {
            options.tags.get().forEach { tag ->
                project.exec {
                    workingDir = context.dir
                    commandLine = listOf("docker", "pull", tag)
                }
            }
            // Additionally if pulling from local repository tag them as such.
            if (isLocalRepository) {
                project.imageTags("local").forEach {
                    project.exec {
                        workingDir = context.dir
                        commandLine = listOf("docker", "tag", options.tags.get().first(), it)
                    }
                }
            }
        }
    }

    // Due to https://github.com/docker/buildx/issues/420 we cannot rely on the imageId file to be populated
    // correctly so we take matters into our own hands.
    private fun updateImageFile() {
        val pluginProject = project.dockerPluginProject()
        val isDockerBuild: Boolean by pluginProject.extra
        val dockerClient: DockerClient by pluginProject.extra
        if (!isDockerBuild) {
            dockerClient.inspectImageCmd(options.tags.get().first()).exec().run {
                options.imageIdFile.get().asFile.writeText(id)
            }
        }
    }

    // We generate an approximate digest to prevent rebuilding downstream images as this will be used as an input to
    // those images.
    private fun updateDigest() {
        val pluginProject = project.dockerPluginProject()
        val dockerClient: DockerClient by pluginProject.extra
        dockerClient.inspectImageCmd(options.tags.get().first()).exec().run {
            digest.get().asFile.writeText(jacksonObjectMapper().writeValueAsString(ApproximateDigest(config, rootFS)))
        }
    }

    @TaskAction
    fun exec() {
        build()
        pull()
        updateDigest()
        updateImageFile()
    }
}
