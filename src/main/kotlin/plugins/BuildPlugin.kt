package plugins

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.LogLevel.*
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import plugins.BuildPlugin.BakeOptions.BakeOptionsFile
import plugins.IslePlugin.Companion.branch
import plugins.IslePlugin.Companion.commit
import plugins.IslePlugin.Companion.latest
import plugins.IslePlugin.Companion.normalizeDockerTag
import plugins.IslePlugin.Companion.sourceDateEpoch
import plugins.IslePlugin.Companion.tag
import tasks.DockerContainer
import tasks.DockerNetwork
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable

@Suppress("unused")
class BuildPlugin : Plugin<Project> {

    companion object {
        // The driver to use for the build, either "docker", "docker-container", or
        // "kubernetes". Note that "docker" only supports "inline" cache mode and does
        // *not* support multi-arch builds.
        private val Project.isleBuilderDriver: String
            get() = properties.getOrDefault("isle.build.driver", "docker") as String

        private val Project.isDefaultDriver: Boolean
            get() = isleBuilderDriver == "docker"

        private val Project.isContainerDriver: Boolean
            get() = isleBuilderDriver == "docker-container"

        // Not yet supported.
        private val Project.isKubernetesDriver: Boolean
            get() = isleBuilderDriver == "kubernetes"

        // The name of the builder
        val Project.isleBuilder: String
            get() = properties.getOrDefault("isle.build.driver.docker-container.name", "isle-buildkit") as String

        // The image to use for the "docker-container" builder.
        val Project.isleBuilderImage: String
            get() = properties.getOrDefault("isle.build.driver.docker-container.image", "moby/buildkit:v0.11.0-rc1") as String

        // Only applies to linux hosts, Docker Desktop comes bundled with Qemu.
        // Allows us to build cross-platform images by emulating the target platform.
        val Project.isleBuilderQemuImage: String
            get() = properties.getOrDefault("isle.build.qemu.image", "tonistiigi/binfmt:qemu-v7.0.0-28") as String

        // The registry to use when building/pushing images.
        val Project.isleBuildRegistry: Provider<String>
            get() = rootProject.tasks.named<RegistryPlugin.CreateRegistry>("createRegistry").map { createRegistryTask ->
                val repository = properties.getOrDefault("isle.build.registry", "islandora") as String
                if (createRegistryTask.name.get() == repository) {
                    createRegistryTask.registry
                } else repository
            }

        // Pushing may require logging in to the repository, if so these need to be populated.
        // The local registry does not require credentials.
        val Project.isleBuildRegistryUser: String
            get() = properties.getOrDefault("isle.build.registry.user", "") as String

        val Project.isleBuildRegistryPassword: String
            get() = properties.getOrDefault("isle.build.registry.password", "") as String

        // The target(s) or group(s) to build from the docker-bake.hcl file.
        val Project.isleBuildTargets: Set<String>
            get() = (properties.getOrDefault("isle.build.targets", "default") as String)
                .split(',')
                .map { it.trim().normalizeDockerTag() }
                .filter { it.isNotEmpty() }
                .toSet()

        // The tag to use when building/pushing images.
        val Project.isleBuildTags: Set<String>
            get() {
                val default = if (tag.get().matches("""[0-9]+\.[0-9]+\.[0-9]+""".toRegex())) {
                    val tags = mutableListOf(tag.get())
                    val components = tag.get().split(".")
                    val major = components[0]
                    val minor = components[1]
                    tags.add("$major.$minor")
                    tags.add(major)
                    if (latest.get()) {
                        tags.add("latest")
                    }
                    tags.joinToString(",")
                }
                else {
                    branch.get()
                }
                return (properties.getOrDefault("isle.build.tags", "") as String).let {
                    it.ifBlank {
                        default
                    }
                }.split(',')
                    .map { it.trim().normalizeDockerTag() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }

        // Load images after building, if not specified images will be pulled instead by tasks that require them.
        val Project.isleBuildLoad: Boolean
            get() = (properties.getOrDefault("isle.build.load", "true") as String).toBoolean()

        // Push images after building (required when using "docker-container" driver).
        val Project.isleBuildPush: Boolean
            get() = (properties.getOrDefault("isle.build.push", "false") as String).toBoolean()

        // The platforms to build image(s) for, If unspecified it will target the
        // host platform. It is possible to target multiple platforms as builder
        // will use emulation for architectures that do not match the host.
        val Project.isleBuildPlatforms: Set<String>
            get() = (properties.getOrDefault("isle.build.platforms", "") as String)
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()

        // Inline
        // Embed the cache into the image, and push them to the registry together.

        // Enable inline cache import/export.
        val Project.isleBuildCacheInlineEnableImport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.inline.enable.import", "false") as String).toBoolean()

        val Project.isleBuildCacheInlineEnableExport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.inline.enable.export", "false") as String).toBoolean()

        // Registry
        // Push the image and the cache separately

        // Enable registry cache import/export.
        val Project.isleBuildCacheRegistryEnableImport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.registry.enable.import", "false") as String).toBoolean()

        val Project.isleBuildCacheRegistryEnableExport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.registry.enable.export", "false") as String).toBoolean()

        // The repository to push/pull image cache to/from.
        val Project.isleBuildCacheRegistryRepository: String
            get() = properties.getOrDefault("isle.build.cache.registry.repository", isleBuildRegistry) as String

        val Project.isleBuildCacheRegistryTagPrefix: String
            get() = properties.getOrDefault("isle.build.cache.registry.tag-prefix", "cache") as String

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.isleBuildCacheRegistryMode: String
            get() = properties.getOrDefault("isle.build.cache.registry.mode", "max") as String

        // Compression type for layers newly created and cached.
        // estargz should be used with oci-mediatypes=true.
        val Project.isleBuildCacheRegistryCompression: String
            get() = properties.getOrDefault("isle.build.cache.registry.compression", "estargz") as String

        // Compression level for gzip, estargz (0-9) and zstd (0-22)
        val Project.isleBuildCacheRegistryCompressionLevel: Int
            get() = (properties.getOrDefault("isle.build.cache.registry.compression-level", "5") as String).toInt()

        // The repository to push/pull image cache to/from.
        val Project.isleBuildCacheRegistryUser: String
            get() = properties.getOrDefault("isle.build.cache.registry.user", "") as String

        // The repository to push/pull image cache to/from.
        val Project.isleBuildCacheRegistryPassword: String
            get() = properties.getOrDefault("isle.build.cache.registry.password", "") as String

        // Local
        // Export to a local directory

        // Enable local cache import/export.
        val Project.isleBuildCacheLocalEnableImport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.local.enable.import", "false") as String).toBoolean()

        val Project.isleBuildCacheLocalEnableExport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.local.enable.export", "false") as String).toBoolean()

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.isleBuildCacheLocalMode: String
            get() = properties.getOrDefault("isle.build.cache.local.mode", "max") as String

        // Compression type for layers newly created and cached.
        // estargz should be used with oci-mediatypes=true.
        val Project.isleBuildCacheLocalCompression: String
            get() = properties.getOrDefault("isle.build.cache.local.compression", "estargz") as String

        // Compression level for gzip, estargz (0-9) and zstd (0-22)
        val Project.isleBuildCacheLocalCompressionLevel: Int
            get() = (properties.getOrDefault("isle.build.cache.local.compression-level", "5") as String).toInt()

        // GitHub Actions
        // Export to GitHub Actions cache.
        // @see https://github.com/moby/buildkit#github-actions-cache-experimental
        // Enable registry cache import/export.

        val Project.isleBuildCacheGitHubActionsEnableImport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.gha.enable.import", "false") as String).toBoolean()

        val Project.isleBuildCacheGitHubActionsEnableExport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.gha.enable.export", "false") as String).toBoolean()

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.isleBuildCacheGitHubActionsMode: String
            get() = properties.getOrDefault("isle.build.cache.gha.mode", "max") as String

        // S3 (or compatible)
        // Stores metadata and layers in s3 bucket.
        // Requires Buildkit v0.11.0-rc1 or later

        // Enable s3 cache import/export.
        val Project.isleBuildCacheS3EnableImport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.s3.enable.import", "false") as String).toBoolean()

        val Project.isleBuildCacheS3EnableExport: Boolean
            get() = (properties.getOrDefault("isle.build.cache.s3.enable.export", "false") as String).toBoolean()

        val Project.isleBuildCacheS3Region: String
            get() = properties.getOrDefault("isle.build.cache.s3.region", "us-east-1") as String

        val Project.isleBuildCacheS3Bucket: String
            get() = properties.getOrDefault("isle.build.cache.s3.bucket", "isle-build-cache") as String

        val Project.isleBuildCacheS3Endpoint: String
            get() = properties.getOrDefault(
                "isle.build.cache.s3.endpoint_url",
                "https://nyc3.digitaloceanspaces.com"
            ) as String

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.isleBuildCacheS3Mode: String
            get() = properties.getOrDefault("isle.build.cache.s3.mode", "max") as String

        // Required to populate the cache.
        val Project.isleBuildCacheS3AccessKey: String
            get() = properties.getOrDefault("isle.build.cache.s3.access_key_id", "") as String

        val Project.isleBuildCacheS3Secret: String
            get() = properties.getOrDefault("isle.build.cache.s3.secret_access_key", "") as String
    }

    // https://github.com/moby/buildkit/blob/v0.10.6/docs/buildkitd.toml.md
    @CacheableTask
    open class BuilderConfiguration : DefaultTask() {
        @Input
        val registry = project.objects.property<String>()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val cert = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val key = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val rootCA = project.objects.fileProperty()

        @OutputFile
        val config =
            project.objects.fileProperty().convention(project.layout.buildDirectory.map { it.file("buildkitd.toml") })

        init {
            logging.captureStandardOutput(INFO)
        }

        @TaskAction
        fun exec() {
            // GitHub Actions has limited disk space, so we must clean up as we go.
            // Additionally, when using CI we do not push to the local registry, but use a remote instead.
            // Keep only up to 8GB of storage.
            if (System.getenv("GITHUB_ACTIONS") == "true") {
                config.get().asFile.writeText(
                    """
                    [worker.containerd]
                      enabled = false
                    [worker.oci]
                      enabled = true
                      gc = true
                      gckeepstorage = 8000
                """.trimIndent()
                )
            } else {
                // Locally developers can run prune when needed, disable GC for speed!!!
                // Also, a local registry is required to push / pull form, unless you have suitable remote setup.
                config.get().asFile.writeText(
                    """
                    [worker.containerd]
                      enabled = false
                    [worker.oci]
                      enabled = true
                      gc = false
                    [registry."${registry.get()}"]
                      insecure=false
                      ca=["${rootCA.get().asFile.absolutePath}"]
                      [[registry."${registry.get()}".keypair]]
                        key="${key.get().asFile.absolutePath}"
                        cert="${cert.get().asFile.absolutePath}"
                """.trimIndent()
                )
            }
        }
    }

    abstract class AbstractBuilder : DefaultTask() {
        @Input
        val name = project.objects.property<String>().convention(project.isleBuilder)

        @Internal
        private val inspect = project.memoizedProperty {
            // Make sure output is empty in-case execution fails, as we do not want to use a value from a previous run.
            ByteArrayOutputStream().use { output ->
                val exists = project.exec {
                    commandLine(
                        "docker",
                        "buildx",
                        "inspect",
                        "--builder", name.get()
                    )
                    standardOutput = output
                    isIgnoreExitValue = true
                }.exitValue == 0
                val running = output.toString().lines().any { it.matches("""Status:\s+running""".toRegex()) }
                Pair(exists, running)
            }
        }

        @get:Internal
        protected val exists: Boolean
            get() = inspect.get().first

        @get:Internal
        protected val running: Boolean
            get() = inspect.get().second

        init {
            logging.captureStandardOutput(INFO)
            logging.captureStandardError(INFO)
        }
    }

    open class CreateBuilder : AbstractBuilder() {
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val config = project.objects.fileProperty()

        @Input
        val network = project.objects.property<String>()

        @Input
        val image = project.objects.property<String>().convention(project.isleBuilderImage)

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val cert = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val key = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val rootCA = project.objects.fileProperty()

        init {
            @Suppress("LeakingThis") onlyIf {
                !exists && project.isContainerDriver
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine(
                    "docker",
                    "buildx",
                    "create",
                    "--bootstrap",
                    "--config", config.get().asFile.absolutePath,
                    "--driver-opt",
                    "image=${image.get()},network=${network.get()}",
                    "--name",
                    name.get()
                )
            }
        }
    }

    open class DestroyBuilder : AbstractBuilder() {

        init {
            @Suppress("LeakingThis") onlyIf {
                exists
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine("docker", "buildx", "rm", name.get())
            }
        }
    }

    open class StopBuilder : AbstractBuilder() {

        init {
            @Suppress("LeakingThis") onlyIf {
                exists && running
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine("docker", "buildx", "stop", name.get())
            }
        }
    }

    open class StartBuilder : AbstractBuilder() {

        init {
            @Suppress("LeakingThis") onlyIf {
                exists && !running
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine("docker", "buildx", "inspect", name.get(), "--bootstrap")
            }
        }
    }

    open class BuilderDiskUsage : AbstractBuilder() {
        init {
            // Works with both docker driver as well so change the default name.
            if (project.isDefaultDriver) {
                name.convention("default")
            }
            // Display at a higher level so the user can see without --info.
            logging.captureStandardOutput(QUIET)
            logging.captureStandardError(ERROR)
            @Suppress("LeakingThis") onlyIf {
                exists && running
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine("docker", "buildx", "du", "--builder", name.get())
            }
        }
    }

    open class PruneBuildCache : AbstractBuilder() {

        init {
            // Works with both docker driver as well so change the default name.
            if (project.isDefaultDriver) {
                name.convention("default")
            }
            @Suppress("LeakingThis") onlyIf {
                exists && running
            }
        }

        @TaskAction
        fun create() {
            project.exec {
                commandLine("docker", "buildx", "prune", "--builder", name.get(), "--force")
            }
        }
    }


    @CacheableTask
    open class BakeOptions : DefaultTask() {
        data class BakeOptionsTargetProperties(val context: String) : Serializable
        data class BakeOptionsFile(val target: Map<String, BakeOptionsTargetProperties>) : Serializable {
            companion object {
                fun deserialize(file: File): BakeOptionsFile =
                    ObjectMapper(YAMLFactory())
                        .registerModule(KotlinModule.Builder().build())
                        .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        .readValue(file)
            }
        }

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val bakefile = project.objects
            .fileProperty()
            .convention(project.rootProject.layout.projectDirectory.file("docker-bake.hcl"))

        @Input
        val targets = project.objects.setProperty<String>().convention(project.isleBuildTargets)

        @OutputFile
        val optionsFile = project.objects.fileProperty()
            .convention(project.layout.buildDirectory.file("options.json"))

        @get:Internal
        val options by lazy {
            BakeOptionsFile.deserialize(optionsFile.get().asFile)
        }

        init {
            logging.captureStandardOutput(INFO)
            logging.captureStandardError(INFO)
        }

        @TaskAction
        fun exec() {
            project.exec {
                commandLine = listOf("docker", "buildx", "bake", "--print") + targets.get()
                workingDir(bakefile.get().asFile.parentFile.absolutePath)
                standardOutput = optionsFile.get().asFile.outputStream()
            }
            options
        }
    }

    open class Bake : DefaultTask() {
        @Input
        val builder = project.objects.property<String>()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val bakefile = project.objects.fileProperty()
            .convention(project.rootProject.layout.projectDirectory.file("docker-bake.hcl"))

        @Input
        val options = project.objects.property<BakeOptionsFile>()

        @Input
        val targets = project.objects.setProperty<String>().convention(options.map {
            it.target.keys
        })

        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        val contexts = options.zip(bakefile.map { it.asFile.parentFile }) { options, root ->
            project.fileTree(".") {
                // Exclude files excluded by Docker.
                options.target.map { it.value.context }.forEach { context ->
                    include("$context/**")
                    val ignore = root.resolve("${context}/.dockerignore")
                    if (ignore.exists()) {
                        exclude(ignore.readLines().map { "$context/$it" })
                    }
                }
            }
        }

        @Input
        val registry = project.objects.property<String>().convention(project.isleBuildRegistry)

        @Input
        val tags = project.objects.setProperty<String>().convention(project.isleBuildTags)

        @Input
        val load = project.objects.property<Boolean>().convention(project.isleBuildLoad)

        @Input
        val push = project.objects.property<Boolean>().convention(project.isleBuildPush)

        @Input
        val platforms = project.objects.listProperty<String>().convention(project.isleBuildPlatforms)

        @Input
        protected val arguments = project.provider {
            mutableListOf(
                "docker",
                "buildx",
                "bake",
                "-f",
                bakefile.get().asFile.absolutePath,
                "--builder",
                builder.get(),
                "--metadata-file",
                metadata.get().asFile.absolutePath
            ).apply {
                if (platforms.get().isNotEmpty()) {
                    addAll(listOf("--set", "*.platform=${platforms.get().joinToString(",")}"))
                }
                if (load.get()) {
                    add("--load")
                }
                if (push.get()) {
                    add("--push")
                }
                // Cache arguments.
                addAll(cacheInlineArguments())
                addAll(cacheRegistryArguments())
                addAll(cacheLocalArguments())
                addAll(cacheGitHubActionsArguments())
                addAll(cacheS3Arguments())
                // Targets to build (load or push)
                addAll(targets.get())
            }
        }

        @Input
        protected val environment = project.provider {
            mapOf(
                // Doesn't have an effect on versions of Buildkit prior to 0.11.0-rc1
                "SOURCE_DATE_EPOCH" to project.sourceDateEpoch.get(),
                "REPOSITORY" to registry.get(),
                "TAGS" to tags.get().joinToString(","),
            )
        }

        @OutputFile
        val metadata: RegularFileProperty =
            project.objects.fileProperty().convention(project.layout.buildDirectory.file("build.json"))

        // Used for inputs into tests.
        @Internal
        val digests = metadata.map { file ->
            val json = file.asFile.readText()
            val node: JsonNode = ObjectMapper().readTree(json)
            // The default builder is the only builder capable of loading and pulling, use the loaded digested as this
            // is used for test inputs.
            val field =
                if (builder.get() == "default" && push.get()) "containerimage.config.digest" else "containerimage.digest"
            (targets.get() as Set<String>).associateWith { target ->
                node.get(target)!!.get(field)!!.asText().trim()
            }.toMap()
        }

        init {
            logging.captureStandardOutput(INFO)
            logging.captureStandardError(INFO)
        }

        private fun cacheInlineArguments() = targets.get().map { target ->
            val arguments = mutableListOf<String>()
            val image = "${registry.get()}/${target}"
            // Import inline cache(s)?
            if (project.isleBuildCacheInlineEnableImport) {
                val importArgs = mutableSetOf<List<String>>()
                // Always look for cache hits in latest, for when a new branch is created from 'main' i.e. latest.
                importArgs.add(
                    listOf("--set", "${target}.cache-from=type=registry,ref=${image}:latest")
                )
                // Check other tags as well.
                importArgs.addAll(tags.get().map { tag ->
                    listOf("--set", "${target}.cache-from=type=registry,ref=${image}:${tag}")
                })
                arguments.addAll(importArgs.flatten())
            }
            // Export inline cache?
            if (project.isleBuildCacheInlineEnableExport) {
                arguments.addAll(
                    listOf("--set", "${target}.cache-to=type=inline")
                )
            }
            arguments
        }.flatten()

        private fun cacheRegistryArguments() = targets.get().map { target ->
            val arguments = mutableListOf<String>()
            val image = "${project.isleBuildCacheRegistryRepository}/${target}"
            val cacheTagPrefix = project.isleBuildCacheRegistryTagPrefix
            val cacheTag = { tag: String -> "${cacheTagPrefix}-${tag}" }
            // Import registry cache(s)?
            // Associated with the git branch that produced the images rather than the tag.
            if (project.isleBuildCacheRegistryEnableImport) {
                val importArgs = mutableSetOf<List<String>>()
                // Always look for cache hits in 'main', for when a new branch is created from 'main'.
                importArgs.add(
                    listOf(
                        "--set", "${target}.cache-from=type=registry,ref=${image}:${cacheTag("main")}"
                    )
                )
                importArgs.add(
                    listOf(
                        "--set", "${target}.cache-from=type=registry,ref=${image}:${cacheTag(project.branch.get())}"
                    )
                )
                arguments.addAll(importArgs.flatten())
            }
            // Export registry cache?
            if (project.isleBuildCacheRegistryEnableExport) {
                arguments.addAll(
                    listOf(
                        "--set", listOf(
                            "${target}.cache-to=type=registry",
                            "mode=${project.isleBuildCacheRegistryMode}",
                            "compression=${project.isleBuildCacheRegistryCompression}",
                            "compression-level=${project.isleBuildCacheRegistryCompressionLevel}",
                            "ref=${image}:${cacheTag(project.branch.get())}",
                        ).joinToString(",")
                    )
                )
            }
            arguments
        }.flatten()

        private fun cacheLocalArguments() = targets.get().map { target ->
            val arguments = mutableListOf<String>()
            val cacheDir = project.rootProject.project(target).buildDir.resolve("cache").absolutePath
            // Import local cache?
            if (project.isleBuildCacheLocalEnableImport) {
                arguments.addAll(listOf("--set", "${target}.cache-from=type=local,src=${cacheDir}"))
            }
            // Export local cache?
            if (project.isleBuildCacheLocalEnableExport) {
                arguments.addAll(
                    listOf(
                        "--set", listOf(
                            "${target}.cache-to=type=local",
                            "dest=${cacheDir}",
                            "mode=${project.isleBuildCacheLocalMode}",
                            "compression=${project.isleBuildCacheLocalCompression}",
                            "compression-level=${project.isleBuildCacheLocalCompressionLevel}",
                        ).joinToString(",")
                    )
                )
            }
            arguments
        }.flatten()

        private fun cacheGitHubActionsArguments() = targets.get().map { target ->
            val arguments = mutableListOf<String>()
            // Github Action https://github.com/crazy-max/ghaction-github-runtime is required for the environment vars.
            val cacheUrl = System.getenv("ACTIONS_CACHE_URL") ?: ""
            val runtimeToken = System.getenv("ACTIONS_RUNTIME_TOKEN") ?: ""
            // Import GitHub Actions cache?
            if (project.isleBuildCacheGitHubActionsEnableImport) {
                arguments.addAll(
                    listOf(
                        "--set", listOf(
                            "${target}.cache-from=type=gha",
                            "url=${cacheUrl}",
                            "token=${runtimeToken}",
                        ).joinToString(",")
                    )
                )
            }
            // Export GitHub Actions cache?
            if (project.isleBuildCacheGitHubActionsEnableExport) {
                arguments.addAll(
                    listOf(
                        "--set", listOf(
                            "${target}.cache-to=type=gha",
                            "url=${cacheUrl}",
                            "token=${runtimeToken}",
                        ).joinToString(",")
                    )
                )
            }
            arguments
        }.flatten()

        private fun cacheS3Arguments() = targets.get().map { target ->
            val arguments = mutableListOf<String>()
            val commonAttributes = listOf(
                "region=${project.isleBuildCacheS3Region}",
                "bucket=${project.isleBuildCacheS3Bucket}",
                "endpoint_url=${project.isleBuildCacheS3Endpoint}",
                "\"access_key_id=${project.isleBuildCacheS3AccessKey}\"",
                "\"secret_access_key=${project.isleBuildCacheS3Secret}\""
            )
            // Import S3 cache(s)?
            if (project.isleBuildCacheS3EnableImport) {
                // Branch is used as fallback if commit hash is not found.
                listOf(project.commit, project.branch).map { it.get() }.forEach { name ->
                    arguments.addAll(
                        listOf(
                            "--set",
                            listOf(
                                "${target}.cache-from=type=s3",
                                "name=${name}"
                            ).plus(commonAttributes).joinToString(",")
                        )
                    )
                }
            }
            // Export S3 cache?
            if (project.isleBuildCacheS3EnableExport) {
                val names = listOf(project.commit, project.branch).map { it.get() }.joinToString(";")
                arguments.addAll(
                    listOf(
                        "--set", listOf(
                            "${target}.cache-to=type=s3",
                            "name=${names}",
                            "mode=${project.isleBuildCacheS3Mode}"
                        ).plus(commonAttributes).joinToString(",")
                    )
                )
            }
            arguments
        }.flatten()

        @TaskAction
        fun build() {
            project.exec {
                workingDir(bakefile.get().asFile.parentFile.absolutePath)
                environment(this@Bake.environment.get())
                commandLine(this@Bake.arguments.get())
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        apply<CertificateGenerationPlugin>()
        apply<RegistryPlugin>()

        val generateCertificates = tasks.named<CertificateGenerationPlugin.GenerateCerts>("generateCertificates")
        val createRegistry = tasks.named<RegistryPlugin.CreateRegistry>("createRegistry")
        val startRegistry = tasks.named<DockerContainer.DockerStartContainer>("startRegistry")
        val destroyRegistryNetwork = tasks.named<DockerNetwork.DockerRemoveNetwork>("destroyRegistryNetwork")

        val installBinFmt by tasks.registering(Exec::class) {
            group = "Isle Build"
            description = "Install https://github.com/tonistiigi/binfmt to enable multi-arch builds on Linux."
            commandLine = listOf(
                "docker",
                "container",
                "run",
                "--rm",
                "--privileged",
                isleBuilderQemuImage,
                "--install", "all"
            )
            // Cross building with Qemu is already installed with Docker Desktop, so we only need to install on Linux.
            // Additionally, it does not work with non x86_64 hosts.
            onlyIf {
                val os = DefaultNativePlatform.getCurrentOperatingSystem()!!
                val arch = DefaultNativePlatform.getCurrentArchitecture()!!
                os.isLinux && arch.isAmd64
            }
        }

        val createBuilderConfiguration by tasks.registering(BuilderConfiguration::class) {
            group = "Isle Build"
            description = "Generate buildkitd.toml.md to configure buildkit."
            registry.set(createRegistry.map { it.registry })
            cert.set(generateCertificates.flatMap { it.cert })
            key.set(generateCertificates.flatMap { it.key })
            rootCA.set(generateCertificates.flatMap { it.rootCA })
        }

        val stopBuilder by tasks.registering(StopBuilder::class) {
            group = "Isle Build"
            description = "Stops the builder a container if running"
        }

        val destroyBuilder by tasks.registering(DestroyBuilder::class) {
            group = "Isle Build"
            description = "Creates a container for the buildkit daemon"
            dependsOn(stopBuilder)
        }

        destroyRegistryNetwork.configure {
            dependsOn(destroyBuilder) // Cannot remove networks of active containers as they share the same network.
        }

        val createBuilder by tasks.registering(CreateBuilder::class) {
            group = "Isle Build"
            description = "Creates the 'docker-container' driver if applicable"
            config.set(createBuilderConfiguration.flatMap { it.config })
            image.set(isleBuilderImage)
            network.set(createRegistry.map { it.network.get() })
            cert.set(generateCertificates.flatMap { it.cert })
            key.set(generateCertificates.flatMap { it.key })
            rootCA.set(generateCertificates.flatMap { it.rootCA })
            dependsOn(installBinFmt, startRegistry)
            mustRunAfter(destroyBuilder)
        }

        val startBuilder by tasks.registering(StartBuilder::class) {
            group = "Isle Build"
            description = "Starts the `docker-container builder if applicable"
            dependsOn(createBuilder)
        }

        val login by tasks.registering(Exec::class) {
            group = "Isle Build"
            description = "Starts the `docker-container builder if applicable"
            standardInput = ByteArrayInputStream(project.isleBuildRegistryPassword.toByteArray())
            commandLine = listOf("docker", "login", "--username", project.isleBuildRegistryUser, "--password-stdin", project.isleBuildRegistry.get())
            onlyIf {
                project.isleBuildRegistryUser.isNotBlank() && project.isleBuildRegistryPassword.isNotBlank()
            }
        }

        tasks.register<BuilderDiskUsage>("displayBuilderDiskUsage") {
            group = "Isle Build"
            description = "Displays disk usage information for the docker-container builder."
        }

        tasks.register<PruneBuildCache>("pruneBuildCache") {
            group = "Isle Build"
            description = "Prunes build cache of the driver."
        }

        val resolveTargets by tasks.registering(BakeOptions::class) {
            group = "Isle Build"
            description = "Determine dependencies for building docker image(s)"
        }

        tasks.register<Bake>("build") {
            group = "Isle Build"
            description = "Build docker image(s)"
            options.set(resolveTargets.map { it.options })
            builder.set(createBuilder.flatMap { it.name })
            dependsOn(login, startBuilder)
        }
    }
}