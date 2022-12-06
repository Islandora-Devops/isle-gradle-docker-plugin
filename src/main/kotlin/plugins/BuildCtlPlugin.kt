package plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import plugins.BuildKitPlugin.Companion.buildKitCacheRepository
import plugins.BuildKitPlugin.Companion.buildKitCacheTag
import plugins.BuildKitPlugin.Companion.buildKitPlatforms
import plugins.BuildKitPlugin.Companion.buildKitRepository
import plugins.BuildKitPlugin.Companion.buildKitTag
import tasks.BuildCtl


// Configures BuildKit.
@Suppress("unused")
class BuildCtlPlugin : Plugin<Project> {

    abstract class BuildCtlBuild : BuildCtl() {
        // PATH (i.e. Docker build context), trigger re-run if changed.
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        val context = project.objects.fileTree().setDir(project.layout.projectDirectory)

        @Input
        val repository = project.objects.property<String>().convention(project.buildKitRepository)

        @Input
        val tag = project.objects.property<String>().convention(project.buildKitTag)

        @Input
        val platforms = project.objects.listProperty<String>().convention(project.buildKitPlatforms)

        @OutputFile
        val metadata = project.objects.fileProperty()

        @get:Internal
        val image: Provider<String>
            get() = project.provider { "${repository.get()}/${project.name}:${tag.get()}" }

        @get:Internal
        protected val baseArguments: List<String>
            get() = mutableListOf(
                executablePath,
                "build",
                "--frontend=dockerfile.v0",
                "--local", "context=.",
                "--local", "dockerfile=.",
                "--opt", "build-arg:repository=${repository.get()}",
                "--opt", "build-arg:tag=${tag.get()}",
                "--metadata-file", metadata.get().asFile.absolutePath,
            )

        protected fun parseMetadata(field: String) = metadata.get().asFile.let {
            if (it.exists()) {
                val node: JsonNode = ObjectMapper().readTree(metadata.get().asFile.readText())
                node.get(field).asText().trim()
            } else
                ""
        }

        init {
            val ignore = project.projectDir.resolve(".dockerignore")
            if (ignore.exists()) {
                context.setExcludes(ignore.readLines())
            }
        }
    }

    open class BuildCtlBuildImage : BuildCtlBuild() {

        // If we source images change we should rebuild.
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        val sourceBuildMetadata = project.objects.fileCollection()

        @Input
        val images = project.objects.listProperty<String>()

        // Gets list of images names without repository or tag, required to build this image.
        // This assumes all images are built by this project.
        @get:Internal
        val requiredImages by lazy {
            context.dir.resolve("Dockerfile").readText().let { text ->
                ("\\" + '$' + """\{repository\}/(?<image>[^:@]+)""")
                    .toRegex()
                    .findAll(text)
                    .map { it.groups["image"]!!.value }
                    .toSet()
            }
        }

        private val accept: String
            get() = if (platforms.get().isEmpty())
                "application/vnd.docker.distribution.manifest.v2+json"
            else
                "application/vnd.docker.distribution.manifest.list.v2+json"

        private val url: String
            get() = "https://${repository.get()}/v2/${project.name}/manifests/${tag.get()}"

        private val currentDigest: String by lazy {
            val arguments = listOf(
                "docker", "exec", builder.get(),
                "curl", "-s",
                "-D", "-",
                "--cacert", "/certs/rootCA.pem",
                "-H", "'Accept: ${accept}'",
                url,
                "|",
                "grep", "docker-content-digest",
                "|",
                """awk '{ print $2 }'"""
            )
            ByteArrayOutputStream().use { output ->
                project.exec {
                    commandLine("sh", "-c", arguments.joinToString(" "))
                    standardOutput = output
                    // Swallow output.
                    errorOutput = NullOutputStream()
                }
                output.toString().trim()
            }
        }

        @get:Internal
        val digest: String
            get() = parseMetadata("containerimage.digest")

        init {
            // Unique file for build metadata.
            metadata.convention(project.layout.buildDirectory.file("build.json"))

            // Always push to the same location we're pulling from to ensure downstream builds get the right image.
            images.add(image)

            // Manually check if the current digest matches the last built digest.
            outputs.upToDateWhen {
                logger.info("Build Digest: $digest")
                logger.info("Current Digest: $currentDigest")
                digest.isNotEmpty() && digest == currentDigest
            }
        }

        @TaskAction
        fun build() {
            val additionalArguments = mutableListOf<String>()
            if (platforms.get().isNotEmpty()) {
                additionalArguments.addAll(
                    listOf(
                        "--opt", "platform=${platforms.get().joinToString(",")}"
                    )
                )
            }
            additionalArguments.addAll(
                listOf(
                    "--import-cache",
                    "type=registry,ref=${project.buildKitCacheRepository}/${project.name}:${project.buildKitCacheTag}",
                )
            )
            // Use GitHub action cache if available.
            if (System.getenv("GITHUB_ACTIONS") == "true") {
                additionalArguments.addAll(
                    listOf(
                        "--export-cache", "type=gha,mode=max",
                        "--import-cache", "type=gha",
                    )
                )
            }
            // Only update the cache image when building the main branch.
            if (System.getenv("GITHUB_REF_NAME") == "main") {
                additionalArguments.addAll(
                    listOf(
                        "--export-cache",
                        "type=registry,mode=max,compression=estargz,ref=${project.buildKitCacheRepository}/${project.name}:${project.buildKitCacheTag}",
                    )
                )
            }
            images.get().joinToString(",").let {
                additionalArguments.addAll(
                    listOf("--output", """type=image,"name=$it",push=true""")
                )
            }
            project.exec {
                workingDir(project.projectDir)
                environment(hostEnvironmentVariables)
                commandLine(baseArguments + additionalArguments)
            }

        }
    }

    open class BuildCtlLoadImage : BuildCtlBuild() {

        // If we rebuild we should reload.
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val buildMetadata = project.objects.fileProperty()

        @get:Internal
        val digest
            get() = parseMetadata("containerimage.config.digest")

        private fun configDigestExists() = project.exec {
            commandLine("docker", "inspect", digest)
            // Swallow output.
            standardOutput = NullOutputStream()
            errorOutput = NullOutputStream()
            isIgnoreExitValue = true
        }.exitValue == 0

        init {
            // Unique file for load metadata.
            metadata.convention(project.layout.buildDirectory.file("load.json"))

            // Manually check if the current digest matches the last built digest.
            outputs.upToDateWhen {
                logger.info("Config Digest: $digest")
                configDigestExists()
            }
        }

        @TaskAction
        fun load() {
            // Only load the definitive repository/image:tag name, also platform is ignored when loading as we only care
            // about the host platform.
            val additionalArguments = listOf(
                "--output", "type=docker,name=${repository.get()}/${project.name}:${tag.get()}"
            )
            project.exec {
                workingDir(project.projectDir)
                environment(hostEnvironmentVariables)
                commandLine(
                    "sh", "-c", (baseArguments + additionalArguments).joinToString(" ").plus("| docker load")
                )
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {

        val build by tasks.registering(BuildCtlBuildImage::class) {
            group = "Isle"
            description = "Build docker image(s)"
            finalizedBy("load")
        }

        tasks.register<BuildCtlLoadImage>("load") {
            group = "Isle"
            description = "Load docker image(s) from registry"
            buildMetadata.set(build.flatMap { it.metadata })
        }
    }
}

