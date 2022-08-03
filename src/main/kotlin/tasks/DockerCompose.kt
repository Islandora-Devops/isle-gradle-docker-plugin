package tasks

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.dockerjava.api.command.InspectContainerResponse
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.provideDelegate
import utils.isDockerProject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Duration

@Suppress("UnstableApiUsage", "MemberVisibilityCanBePrivate")
@CacheableTask
abstract class DockerCompose : DockerClient() {

    data class DockerComposeFile(val services: Map<String, Service>) {
        companion object {
            fun deserialize(file: File): DockerComposeFile =
                ObjectMapper(YAMLFactory())
                    .registerModule(KotlinModule.Builder().build())
                    .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(file)
        }
    }

    data class Service(val image: String) {
        companion object {
            @Suppress("RegExpRedundantEscape")
            val regex = """\$\{(?<variable>[^:]+):-(?<default>.+)\}""".toRegex()
        }

        private fun variable() = regex
            .matchEntire(image)
            ?.groups
            ?.get("variable")
            ?.value

        fun env(image: String) =
            variable()
                ?.let { variable ->
                    variable to image
                }
    }

    // The docker-compose.yml file to run.
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val dockerComposeFile =
        project.objects.fileProperty().convention(project.layout.projectDirectory.file("docker-compose.yml"))

    @get:Internal
    val dockerCompose by lazy {
        DockerComposeFile.deserialize(dockerComposeFile.get().asFile)
    }

    // Environment variables which allow us to override the image used by the service.
    @get:Internal
    val imageEnvironmentVariables by lazy {
        dockerCompose.services.mapNotNull { (name, service) ->
            project.findProject(":$name")
                ?.tasks
                ?.named("build", DockerBuild::class.java)
                ?.get()
                ?.options
                ?.tags
                ?.get()
                ?.first()
                ?.let { image ->
                    service.env(image)
                }
        }.toMap()
    }

    // Not actually the image digest but rather an approximation that ignores timestamps, etc.
    // So we do not run test unless the image has actually changed.
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val digests = project.objects.listProperty<RegularFileProperty>().convention(
        project.provider {
            // If the name of a service matches a known image in this build we will set a dependency on it.
            dockerCompose.services.mapNotNull { (name, _) ->
                project.findProject(":$name")
                    ?.tasks
                    ?.named("build", DockerBuild::class.java)
                    ?.get()
                    ?.digest
            }
        }
    )

    // Capture the log output of the command for later inspection.
    @OutputFile
    val log = project.objects.fileProperty().convention(project.layout.buildDirectory.file("$name.log"))

    // Environment for docker-compose not the actual containers.
    @Input
    val environment = project.objects.mapProperty<String, String>()

    @Internal
    val info = project.objects.mapProperty<String, InspectContainerResponse>()

    init {
        // Rerun test if any of the files in the directory of the docker-compose.yml file changes, as likely they are
        // bind mounted or secrets, etc. The could affect the outcome of the test.
        inputs.dir(project.projectDir)
        // By default limit max execution time to a minute.
        timeout.convention(Duration.ofMinutes(5))
        // Ensure we do not leave container running if something goes wrong.
        project.gradle.buildFinished {
            ByteArrayOutputStream().use { output ->
                invoke("down", "-v", output = output, error = output)
                logger.info(output.toString())
            }
        }
    }

    fun invoke(
        vararg args: String,
        env: Map<String, String> = imageEnvironmentVariables.plus(environment.get()),
        ignoreExitValue: Boolean = false,
        output: OutputStream? = null,
        error: OutputStream? = null
    ) = project.exec {
        environment.putAll(env)
        workingDir = dockerComposeFile.get().asFile.parentFile
        isIgnoreExitValue = ignoreExitValue
        if (output != null) standardOutput = output
        if (error != null) errorOutput = error
        commandLine(
            "docker",
            "compose",
            "--project-name",
            project.path
                .replace(":", "_")
                .toLowerCase()
                .removePrefix("_"),
            *args
        )
    }

    fun up(vararg args: String, ignoreExitValue: Boolean = false) = try {
        invoke("up", *args, ignoreExitValue = ignoreExitValue)
    } catch (e: Exception) {
        log()
        throw e
    }

    @Suppress("unused")
    fun exec(vararg args: String) = invoke("exec", *args)

    fun stop(vararg args: String) = invoke("stop", *args)

    fun down(vararg args: String) = invoke("down", *args)

    fun pull() = dockerCompose.services.keys.mapNotNull { name ->
        // Find services that do not match any projects and pull them as they must refer to an external image.
        // Other images will be provided by dependency on the image digests.
        if (project.rootProject.allprojects.none { it.isDockerProject && it.name == name }) name else null
    }.let { services ->
        if (services.isNotEmpty()) {
            invoke("pull", *services.toTypedArray())
        }
    }

    fun log() {
        log.get().asFile.outputStream().buffered().use { writer ->
            invoke("logs", "--no-color", "--timestamps", output = writer, error = writer)
        }
    }

    fun inspect() = ByteArrayOutputStream().use { output ->
        invoke("ps", "-aq", output = output)
        output
            .toString()
            .lines()
            .filter { it.isNotEmpty() }
            .map { container ->
                dockerClient.inspectContainerCmd(container).exec()
            }
    }

    fun setUp() {
        pull()
    }

    fun tearDown() {
        stop()
        info.set(inspect().associateBy { it.config.labels["com.docker.compose.service"]!! })
        log()
        down("-v")
    }

    fun checkExitCodes(expected: Long) {
        info.get().forEach { (name, info) ->
            val state = info.state
            if (state.exitCodeLong != expected) {
                throw RuntimeException("Service $name exited with ${state.exitCodeLong} and status ${state.status}.")
            }
        }
    }
}
