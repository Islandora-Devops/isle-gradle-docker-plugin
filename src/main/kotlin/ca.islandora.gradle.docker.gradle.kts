import com.bmuschko.gradle.docker.tasks.image.DockerPushImage
import com.bmuschko.gradle.docker.tasks.image.Dockerfile
import com.bmuschko.gradle.docker.tasks.image.Dockerfile.*
import java.io.ByteArrayOutputStream

plugins {
    id("com.bmuschko.docker-remote-api")
}

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

val useBuildKit = properties.getOrDefault("useBuildKit", "true") as String
val repository = properties.getOrDefault("repository", "local") as String
val cacheRepository = properties.getOrDefault("cacheRepository", "islandora") as String

val registryUrl = properties.getOrDefault("registryUrl", "https://index.docker.io/v1") as String
val registryUsername = properties.getOrDefault("registryUsername", "") as String
val registryPassword = properties.getOrDefault("registryPassword", "") as String

// https://docs.docker.com/engine/reference/builder/#from
// FROM [--platform=<platform>] <image> [AS <name>]
// FROM [--platform=<platform>] <image>[:<tag>] [AS <name>]
// FROM [--platform=<platform>] <image>[@<digest>] [AS <name>]
val extractProjectDependenciesFromDockerfileRegex =
    """FROM[ \t]+(:?--platform=[^ ]+[ \t]+)?local/([^ :@]+):(.*)""".toRegex()

// If BuildKit is enabled instructions are left as is otherwise BuildKit specific flags are removed.
val extractBuildKitFlagFromInstruction = """(--mount.+ \\)""".toRegex()
val preprocessRunInstruction: (Instruction) -> Instruction = if (useBuildKit.toBoolean()) {
    // No-op
    { instruction -> instruction }
} else {
    // Strip BuildKit specific flags.
    { instruction ->
        // Assumes only mount flags are used and each one is separated onto it's own line.
        val text = instruction.text.replace(extractBuildKitFlagFromInstruction, """\\""")
        GenericInstruction(text)
    }
}

data class BindMount(val from: String?, val source: String?, val target: String) {
    companion object {
        private val EXTRACT_BIND_MOUNT_REGEX = """--mount=type=bind,([^\\]+)""".toRegex()

        fun fromInstruction(instruction: Instruction) = EXTRACT_BIND_MOUNT_REGEX.find(instruction.text)?.let {
            val properties = it.groupValues[1].split(',').map { property ->
                val parts = property.split('=')
                Pair(parts[0], parts[1])
            }.toMap()
            BindMount(properties["from"], properties["source"], properties["target"]!!)
        }
    }

    // eg. COPY /packages
    // eg. COPY /home/builder/packages/x86_64 /packages
    // eg. COPY --from=imagemagick /home/builder/packages/x86_64 /packages
    fun toCopyInstruction(): GenericInstruction {
        val from = if (from != null) "--from=${from}" else ""
        return GenericInstruction("COPY $from $source $target")
    }
}

// Generate a list of image tags for the given image, using the project, and tag properties.
fun imagesTags(image: String, project: Project): Set<String> {
    val tags = properties.getOrDefault("tags", "") as String
    return setOf("$image:latest") + tags.split(' ').filter { it.isNotEmpty() }.map { "$image:$it" }
}

fun imageExists(project: Project, imageIdFile: RegularFileProperty) = try {
    val imageId = imageIdFile.asFile.get().readText()
    val result = project.exec {
        commandLine = listOf("docker", "image", "inspect", imageId)
        // Ignore output, only concerned with exit value.
        standardOutput = ByteArrayOutputStream()
        errorOutput = ByteArrayOutputStream()
    }
    result.exitValue == 0
} catch (e: Exception) {
    false
}

subprojects {
    // Make all build directories relative to the root, only supports projects up to a depth of one for now.
    buildDir = rootProject.buildDir.resolve(projectDir.relativeTo(rootDir))
    layout.buildDirectory.set(buildDir)

    // If there is a docker file in the project add the appropriate tasks.
    if (projectDir.resolve("Dockerfile").exists()) {
        apply(plugin = "com.bmuschko.docker-remote-api")

        val imageTags = imagesTags("$repository/$name", project)
        val cachedImageTags = imagesTags("$cacheRepository/$name", project)

        val createDockerfile = tasks.register<Dockerfile>("createDockerFile") {
            instructionsFromTemplate(projectDir.resolve("Dockerfile"))
            // To simplify processing the instructions group them by keyword.
            val originalInstructions = instructions.get().toList()
            val groupedInstructions = mutableListOf<Pair<String, MutableList<Instruction>>>(
                Pair(originalInstructions.first().keyword, mutableListOf(originalInstructions.first()))
            )
            originalInstructions.drop(1).forEach { instruction ->
                // An empty keyword means the line of text belongs to the previous instruction keyword.
                if (instruction.keyword != "") {
                    groupedInstructions.add(Pair(instruction.keyword, mutableListOf(instruction)))
                } else {
                    groupedInstructions.last().second.add(instruction)
                }
            }
            // Using bind mounts from other images needs to be mapped to COPY instructions, if not using BuildKit.
            // Add these COPY instructions prior to the RUN instructions that used the bind mount.
            val iterator = groupedInstructions.listIterator()
            while (iterator.hasNext()) {
                val (keyword, instructions) = iterator.next()
                when (keyword) {
                    RunCommandInstruction.KEYWORD -> if (!useBuildKit.toBoolean()) { // Convert bind mounts to copies when BuildKit is not enabled.
                        // Get any bind mount flags and convert them into copy instructions.
                        val bindMounts = instructions.mapNotNull { instruction ->
                            BindMount.fromInstruction(instruction)
                        }
                        bindMounts.forEach { bindMount ->
                            // Add before RUN instruction, previous is safe here as there has to always be at least a
                            // single FROM instruction preceding it.
                            iterator.previous()
                            iterator.add(
                                Pair(
                                    CopyFileInstruction.KEYWORD,
                                    mutableListOf(bindMount.toCopyInstruction())
                                )
                            )
                            iterator.next()
                        }
                    }
                }
            }
            // Process instructions in place, and flatten to list.
            val processedInstructions = groupedInstructions.flatMap { (keyword, instructions) ->
                when (keyword) {
                    // Use the 'repository' name for the images when building, defaults to 'local'.
                    FromInstruction.KEYWORD -> {
                        instructions.map { instruction ->
                            extractProjectDependenciesFromDockerfileRegex.find(instruction.text)?.let {
                                val name = it.groupValues[2]
                                val remainder = it.groupValues[3]
                                FromInstruction(From("$repository/$name:$remainder"))
                            } ?: instruction
                        }
                    }
                    // Strip BuildKit flags if applicable.
                    RunCommandInstruction.KEYWORD -> instructions.map { preprocessRunInstruction(it) }
                    else -> instructions
                }
            }
            instructions.set(processedInstructions)
            destFile.set(buildDir.resolve("Dockerfile"))
        }

        val buildDockerImage = tasks.register<DockerBuildKitBuildImage>("build") {
            group = "islandora"
            description = "Creates Docker image."
            dockerFile.set(createDockerfile.map { it.destFile.get() })
            buildKit.set(useBuildKit.toBoolean())
            images.addAll(imageTags)
            inputDir.set(projectDir)
            // Use the remote cache to build this image if possible.
            cacheFrom.addAll(cachedImageTags)
            // Check that another process has not removed the image since it was last built.
            outputs.upToDateWhen { task ->
                imageExists(project, (task as DockerBuildKitBuildImage).imageIdFile)
            }
        }

        tasks.register<DockerPushImage>("push") {
            images.set(buildDockerImage.map { it.images.get() })
            registryCredentials {
                url.set(registryUrl)
                username.set(registryUsername)
                password.set(registryPassword)
            }
        }
    }
}

inline fun <reified T : DefaultTask> getBuildDependencies(childTask: T) = childTask.project.run {
    val contents = projectDir.resolve("Dockerfile").readText()
    // Extract the image name without the prefix 'local' it should match an existing project.
    val matches = extractProjectDependenciesFromDockerfileRegex.findAll(contents)

    // If the project is found and it has a build task, it is a dependency.
    matches.mapNotNull {
        rootProject.findProject(it.groupValues[2])?.tasks?.withType<T>()
    }.flatten()
}

// This used to replace the FROM statements such that the referred to the Image ID rather
// than "latest". Though this is currently broken when BuildKit is enabled:
// https://github.com/moby/moby/issues/39769
// Now it uses whatever repository we're building / latest since that is variable.
subprojects {
    tasks.withType<DockerBuildKitBuildImage> {
        getBuildDependencies(this).forEach { parentTask ->
            inputs.file(parentTask.imageIdFile.asFile) // If generated image id changes, rebuild.
            dependsOn(parentTask)
        }
    }
}

//=============================================================================
// Helper functions.
//=============================================================================

// Override the DockerBuildImage command to use the CLI since BuildKit is not supported in the java docker api.
// https://github.com/docker-java/docker-java/issues/1381
open class DockerBuildKitBuildImage : DefaultTask() {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val dockerFile = project.objects.fileProperty()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    val inputDir = project.objects.directoryProperty()

    @Input
    val buildKit = project.objects.property<Boolean>()

    @Input
    @get:Optional
    val cacheFrom = project.objects.listProperty<String>()

    @Input
    @get:Optional
    val buildArgs = project.objects.mapProperty<String, String>()

    @Input
    @get:Optional
    val images = project.objects.setProperty<String>()

    @OutputFile
    val imageIdFile = project.objects.fileProperty()

    @Internal
    val imageId = project.objects.property<String>()

    init {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.ERROR)
        imageIdFile.set(project.buildDir.resolve("${path.replace(":", "_")}-imageId.txt"))
    }

    private fun cacheFromValid(image: String): Boolean {
        return try {
            val result = project.exec {
                environment("DOCKER_CLI_EXPERIMENTAL", "enabled")
                workingDir = inputDir.get().asFile
                commandLine = listOf("docker", "manifest", "inspect", image)
            }
            result.exitValue == 0
        } catch (e: Exception) {
            logger.error("Failed to find cache image: ${image}, either it does not exist, or authentication failed.")
            false
        }
    }

    @TaskAction
    fun exec() {
        val command = mutableListOf("docker", "build", "--progress=plain")
        command.addAll(listOf("--file", dockerFile.get().asFile.absolutePath))
        if (buildKit.get()) {
            // Only BuildKit allows us to use existing images as a cache.
            command.addAll(cacheFrom.get().filter { cacheFromValid(it) }.flatMap { listOf("--cache-from", it) })
            // Allow image to be used as a cache when building on other machine.
            command.addAll(listOf("--build-arg", "BUILDKIT_INLINE_CACHE=1"))
        }
        command.addAll(buildArgs.get().flatMap { listOf("--build-arg", "${it.key}=${it.value}") })
        command.addAll(images.get().flatMap { listOf("--tag", it) })
        command.addAll(listOf("--iidfile", imageIdFile.get().asFile.absolutePath))
        command.add(".")
        project.exec {
            // Use BuildKit to build.
            if (buildKit.get()) {
                environment("DOCKER_BUILDKIT" to 1)
            }
            workingDir = inputDir.get().asFile
            commandLine = command
        }
        imageId.set(imageIdFile.map { it.asFile.readText() })
    }
}
