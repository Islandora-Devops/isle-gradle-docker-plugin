package plugins

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.io.output.ByteArrayOutputStream
import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import plugins.BuildKitPlugin.Companion.buildKitCacheGitHubActionsEnableExport
import plugins.BuildKitPlugin.Companion.buildKitCacheGitHubActionsEnableImport
import plugins.BuildKitPlugin.Companion.buildKitCacheInlineEnableExport
import plugins.BuildKitPlugin.Companion.buildKitCacheInlineEnableImport
import plugins.BuildKitPlugin.Companion.buildKitCacheLocalCompression
import plugins.BuildKitPlugin.Companion.buildKitCacheLocalCompressionLevel
import plugins.BuildKitPlugin.Companion.buildKitCacheLocalEnableExport
import plugins.BuildKitPlugin.Companion.buildKitCacheLocalEnableImport
import plugins.BuildKitPlugin.Companion.buildKitCacheLocalMode
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryCompression
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryCompressionLevel
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryEnableExport
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryEnableImport
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryMode
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryRepository
import plugins.BuildKitPlugin.Companion.buildKitCacheRegistryTagPrefix
import plugins.BuildKitPlugin.Companion.buildKitCacheS3AccessKey
import plugins.BuildKitPlugin.Companion.buildKitCacheS3Bucket
import plugins.BuildKitPlugin.Companion.buildKitCacheS3EnableExport
import plugins.BuildKitPlugin.Companion.buildKitCacheS3EnableImport
import plugins.BuildKitPlugin.Companion.buildKitCacheS3Endpoint
import plugins.BuildKitPlugin.Companion.buildKitCacheS3Mode
import plugins.BuildKitPlugin.Companion.buildKitCacheS3Region
import plugins.BuildKitPlugin.Companion.buildKitCacheS3Secret
import plugins.BuildKitPlugin.Companion.buildKitImages
import plugins.BuildKitPlugin.Companion.buildKitLoad
import plugins.BuildKitPlugin.Companion.buildKitPlatforms
import plugins.BuildKitPlugin.Companion.buildKitRepository
import plugins.BuildKitPlugin.Companion.buildKitTag
import plugins.IslePlugin.Companion.branch
import plugins.IslePlugin.Companion.commit
import plugins.IslePlugin.Companion.sourceDateEpoch
import tasks.BuildCtl


// Configures BuildKit.
@Suppress("unused")
class BuildCtlPlugin : Plugin<Project> {

    abstract class BuildCtlBuild : BuildCtl() {
        // PATH (i.e. Docker build context), trigger re-run if changed.
        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        val context: ConfigurableFileTree = project.objects.fileTree().setDir(project.layout.projectDirectory)

        @Input
        val repository = project.objects.property<String>().convention(project.buildKitRepository)

        @Input
        val tag = project.objects.property<String>().convention(project.buildKitTag)

        @Input
        val platforms = project.objects.listProperty<String>().convention(project.buildKitPlatforms)

        @OutputFile
        val metadata: RegularFileProperty = project.objects.fileProperty()

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
                // Doesn't have an effect on versions of Buildkit prior to 0.11.0-rc1
                "--opt", "build-arg:SOURCE_DATE_EPOCH=${project.sourceDateEpoch.get()}",
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
        val sourceBuildMetadata: ConfigurableFileCollection = project.objects.fileCollection()

        @Input
        val images = project.objects.setProperty<String>().convention(project.buildKitImages)

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
                digest.isNotBlank() && digest == currentDigest
            }
        }

        private fun cacheInlineArguments(): List<String> {
            val arguments = mutableListOf<String>()
            val image = "${repository.get()}/${project.name}"
            // Import inline cache(s)?
            if (project.buildKitCacheInlineEnableImport) {
                // Always look for cache hits in latest, for when a new branch is created from 'main' i.e. latest.
                arguments.addAll(
                    listOf("--import-cache", "type=inline,ref=${image}:latest")
                )
                // If building something other than latest also add the cache tag for that.
                if (tag.get() != "latest") {
                    arguments.addAll(
                        listOf("--import-cache", "type=inline,ref=${image}:${tag.get()}")
                    )
                }
            }
            // Export inline cache?
            if (project.buildKitCacheInlineEnableExport) {
                arguments.addAll(
                    listOf("--export-cache", "type=inline")
                )
            }
            return arguments
        }

        private fun cacheRegistryArguments(): List<String> {
            val arguments = mutableListOf<String>()
            val commonAttributes = listOf(
                "type=registry",
            )
            val image = "${project.buildKitCacheRegistryRepository}/${project.name}"
            val cacheTagPrefix = project.buildKitCacheRegistryTagPrefix
            val cacheTag = "${cacheTagPrefix}-${tag.get()}"
            // Import registry cache(s)?
            if (project.buildKitCacheRegistryEnableImport) {
                // Always look for cache hits in latest, for when a new branch is created from 'main' i.e. latest.
                arguments.addAll(
                    listOf(
                        "--import-cache", commonAttributes
                            .plus("ref=${image}:${cacheTagPrefix}-latest")
                            .joinToString(",")
                    )
                )
                // If building something other than latest also add the cache tag for that.
                if (tag.get() != "latest") {
                    arguments.addAll(
                        listOf(
                            "--import-cache", commonAttributes
                                .plus("ref=${image}:${cacheTag}")
                                .joinToString(",")
                        )
                    )
                }
            }
            // Export registry cache?
            if (project.buildKitCacheRegistryEnableExport) {
                arguments.addAll(
                    listOf(
                        "--export-cache", commonAttributes.plus(listOf(
                            "mode=${project.buildKitCacheRegistryMode}",
                            "compression=${project.buildKitCacheRegistryCompression}",
                            "compression-level=${project.buildKitCacheRegistryCompressionLevel}",
                            "ref=${image}:${cacheTag}",
                        )).joinToString(",")
                    )
                )
            }
            return arguments
        }

        private fun cacheLocalArguments(): List<String> {
            val arguments = mutableListOf<String>()
            val commonAttributes = listOf(
                "type=local",
                "tag=${tag.get()}"
            )
            val cacheDir = project.buildDir.resolve("cache").absolutePath
            // Import local cache?
            if (project.buildKitCacheLocalEnableImport) {
                val attributes = commonAttributes.plus("src=${cacheDir}").joinToString(",")
                arguments.addAll(listOf("--import-cache", attributes))
            }
            // Export local cache?
            if (project.buildKitCacheLocalEnableExport) {
                val attributes = commonAttributes.plus(
                    listOf(
                        "dest=${cacheDir}",
                        "mode=${project.buildKitCacheLocalMode}",
                        "compression=${project.buildKitCacheLocalCompression}",
                        "compression-level=${project.buildKitCacheLocalCompressionLevel}",
                    )
                ).joinToString(",")
                arguments.addAll(listOf("--export-cache", attributes))
            }
            return arguments
        }

        private fun cacheGitHubActionsArguments(): List<String> {
            val arguments = mutableListOf<String>()
            // Github Action https://github.com/crazy-max/ghaction-github-runtime is required for the environment vars.
            val cacheUrl = System.getenv("ACTIONS_CACHE_URL") ?: ""
            val runtimeToken = System.getenv("ACTIONS_RUNTIME_TOKEN") ?: ""
            val commonAttributes = listOf(
                "type=gha",
                "url=${cacheUrl}",
                "token=${runtimeToken}",
            )
            // Import GitHub Actions cache?
            if (project.buildKitCacheGitHubActionsEnableImport) {
                arguments.addAll(listOf("--import-cache", commonAttributes.joinToString(",")))
            }
            // Export GitHub Actions cache?
            if (project.buildKitCacheGitHubActionsEnableExport) {
                arguments.addAll(listOf("--export-cache", commonAttributes.joinToString(",")))
            }
            return arguments
        }

        private fun cacheS3Arguments(): List<String> {
            val arguments = mutableListOf<String>()
            val commonAttributes = listOf(
                "type=s3",
                "region=${project.buildKitCacheS3Region}",
                "bucket=${project.buildKitCacheS3Bucket}",
                "endpoint_url=${project.buildKitCacheS3Endpoint}",
                "\"access_key_id=${project.buildKitCacheS3AccessKey}\"",
                "\"secret_access_key=${project.buildKitCacheS3Secret}\""
            )
            // Import S3 cache(s)?
            if (project.buildKitCacheS3EnableImport) {
                // Branch is used as fallback if commit hash is not found.
                listOf(project.commit, project.branch).forEach { name ->
                    val attributes = commonAttributes
                        .plus("name=${name.get()}")
                        .joinToString(",")
                    arguments.addAll(listOf("--import-cache", attributes))
                }
            }
            // Export S3 cache?
            if (project.buildKitCacheS3EnableExport) {
                val name = listOf(project.commit, project.branch).joinToString(";")
                val attributes = commonAttributes.plus(
                    listOf(
                        "name=${name}",
                        "mode=${project.buildKitCacheS3Mode}",
                    )
                ).joinToString(",")
                arguments.addAll(listOf("--export-cache", attributes))
            }
            return arguments
        }

        @TaskAction
        fun build() {
            val additionalArguments = mutableListOf<String>()

            // Multi-arch build?
            if (platforms.get().isNotEmpty()) {
                additionalArguments.addAll(
                    listOf(
                        "--opt", "platform=${platforms.get().joinToString(",")}"
                    )
                )
            }

            // Cache arguments.
            additionalArguments.addAll(cacheInlineArguments())
            additionalArguments.addAll(cacheRegistryArguments())
            additionalArguments.addAll(cacheLocalArguments())
            additionalArguments.addAll(cacheGitHubActionsArguments())
            additionalArguments.addAll(cacheS3Arguments())

            // @todo Support multiple outputs.
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
            if (project.buildKitLoad) {
                finalizedBy("load")
            }
        }

        tasks.register<BuildCtlLoadImage>("load") {
            group = "Isle"
            description = "Load docker image(s) from registry"
            buildMetadata.set(build.flatMap { it.metadata })
        }
    }
}

