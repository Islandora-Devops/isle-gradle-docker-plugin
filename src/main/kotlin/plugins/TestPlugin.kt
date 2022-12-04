package plugins

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import java.io.*
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Generate reports via Syft and Grype.
@Suppress("unused")
class TestPlugin : Plugin<Project> {

    open class DockerCompose : DefaultTask() {
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

        @Internal
        val baseArguments = listOf(
            "docker",
            "compose",
            "--project-name", project.path
                .replace(":", "_")
                .toLowerCase()
                .removePrefix("_")
        )

        @get:Internal
        val dockerCompose by lazy {
            DockerComposeFile.deserialize(project.file("docker-compose.yml"))
        }

        // Output of load tasks for dependency caching and resolution.
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        val metadataFiles = project.objects.fileCollection()

        // Any file might be referenced by the docker-compose.yml file / as a volume, etc.
        @InputDirectory
        @PathSensitive(PathSensitivity.RELATIVE)
        val context = project.objects.directoryProperty().convention(project.layout.projectDirectory)

        // Environment for docker-compose not the actual containers.
        @Input
        val env = project.objects.mapProperty<String, String>()
    }

    @CacheableTask
    open class DockerComposeUp : DockerCompose() {

        companion object {
            val pool: ExecutorService = Executors.newCachedThreadPool()
        }

        @Input
        val exitCodeConditions = project.objects.mapProperty<String, Int>()

        @Input
        val outputConditions = project.objects.mapProperty<String, String>()

        // Capture the log output of the command for later inspection.
        // Also prevents test from re-running if successful.
        @OutputFile
        val log = project.objects.fileProperty().convention(project.layout.buildDirectory.file("$name.log"))

        init {
            // By default, limit max execution time to 5 minutes.
            timeout.convention(Duration.ofMinutes(5))

            // Expect each container exits 0 by default.
            exitCodeConditions.putAll(dockerCompose.services.mapValues { 0 })
        }

        // Gets the identifiers of all the services created by the docker compose file.
        private val containers by lazy {
            ByteArrayOutputStream().use { output ->
                project.exec {
                    workingDir(project.projectDir)
                    commandLine(baseArguments + listOf("ps", "-aq"))
                    standardOutput = output
                }
                output
                    .toString()
                    .lines()
                    .filter { it.isNotEmpty() }
            }
        }

        // Performs an `docker inspect` on all the services created by the docker compose file.
        // Builds a map of service names paired with their exit codes.
        @get:Internal
        protected val exitCodes by lazy {
            containers.associate { container ->
                ByteArrayOutputStream().use { output ->
                    project.exec {
                        workingDir(project.projectDir)
                        commandLine("docker", "inspect", container)
                        standardOutput = output
                    }
                    output.toString()
                }.let {
                    val node: JsonNode = ObjectMapper().readTree(it)
                    val service =
                        node.get(0)!!.get("Config")!!.get("Labels")!!.get("com.docker.compose.service")!!.asText()!!
                    val exitCode = node.get(0)!!.get("State")!!.get("ExitCode")!!.asInt()
                    Pair(service, exitCode)
                }
            }
        }

        // Helper for writing tests which need to look for specific exit codes.
        fun expectOutput(service: String, output: String) {
            outputConditions.put(service, output)
        }

        // Helper for writing tests which need to look for specific exit codes.
        fun expectExitCode(service: String, exitCode: Int) {
            exitCodeConditions.put(service, exitCode)
        }

        // Monitor output of the given service.
        private fun monitorService(service: String, output: String): Triple<String, String, Boolean> {
            logger.info("""Looking for "$output" in $service logs""")
            val process = ProcessBuilder().run {
                directory(project.projectDir)
                //command("bash", "-c", "while true; do openssl rand -base64 12; sleep 1; done")
                command(
                    baseArguments + listOf(
                        "logs",
                        "--follow",
                        service
                    )
                )
                redirectErrorStream(true)
                start()
            }
            val reader = CompletableFuture.supplyAsync({
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.contains(output) == true) {
                        process.destroy()
                        logger.info("""Found "$output" in $service logs""")
                        return@supplyAsync Triple(service, output, true)
                    }
                }
                logger.info("""Missing "$output" from $service logs""")
                return@supplyAsync Triple(service, output, false)

            }, pool)
            reader.whenComplete { _, _ ->
                process.destroyForcibly()
            }
            if (!process.waitFor(timeout.get().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            return reader.get()
        }

        @TaskAction
        fun up() {
            val up = CompletableFuture.supplyAsync(
                {
                    val process = ProcessBuilder().run {
                        directory(project.projectDir)
                        command(baseArguments + listOf("up", "--abort-on-container-exit"))
                        redirectOutput(log.get().asFile)
                        redirectErrorStream(true)
                        start()
                    }
                    if (!process.waitFor(timeout.get().toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                    }
                    process.exitValue()
                }, pool
            )

            // Used to fail the task if any condition was not met.
            var failedConditions = false

            if (outputConditions.get().isNotEmpty()) {
                val logMonitors = outputConditions.get().map { (service, output) ->
                    CompletableFuture.supplyAsync({
                        monitorService(service, output)
                    }, pool)
                }.toTypedArray()
                // Will block until found, or timeout.
                CompletableFuture.allOf(*logMonitors).join()
                // Exit ignoring the exit code for docker-compose as we look at each container instead.
                up.complete(0)
                // Check for any monitors that failed to find their expected output.
                failedConditions = logMonitors.any { !it.get().third }
            }
            up.join() // Either ended of its own accord or output conditions have all been satisfied.
            // Wait for all containers to come down before we check their exit codes.
            project.exec {
                workingDir = project.projectDir
                commandLine = baseArguments + listOf("stop")
            }
            exitCodeConditions.get().forEach { (service, expectedExitCode) ->
                val exitCode = exitCodes[service]
                logger.info("Service ($service) exited with: $exitCode, expected $expectedExitCode")
                if (exitCode != expectedExitCode) {
                    failedConditions = true
                }
            }
            if (failedConditions) {
                logger.info("Failed Conditions")
                throw GradleException("Failed conditions")
            }
        }
    }

    open class DockerComposeDown : DockerCompose() {

        @TaskAction
        fun down() {
            project.exec {
                workingDir(project.projectDir)
                commandLine(baseArguments + listOf("down", "-v"))
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        val cleanUpBefore by tasks.registering(DockerComposeDown::class) {
            group = "Isle Tests"
            description = "Clean up resources before running test (if interrupted externally, etc)"
        }

        // Placeholder which can be overridden in tests.
        val setUp by tasks.registering(DockerCompose::class) {
            group = "Isle Tests"
            description = "Prepare to run test"
            dependsOn(cleanUpBefore)
        }

        val cleanUpAfter by tasks.registering(DockerComposeDown::class) {
            group = "Isle Tests"
            description = "Clean up resources after running test"
        }

        tasks.register<DockerComposeUp>("test") {
            group = "Isle Tests"
            description = "Perform test"
            dependsOn(setUp)
            finalizedBy(cleanUpAfter)
        }
    }

}