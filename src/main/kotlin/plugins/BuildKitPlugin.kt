package plugins

import org.gradle.api.*
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import plugins.BuildKitPlugin.DockerBuildKitExtension.Companion.buildkit
import plugins.CertificateGenerationPlugin.GenerateCerts
import plugins.IslePlugin.Companion.isDockerProject
import plugins.RegistryPlugin.CreateRegistry
import tasks.BuildCtl
import tasks.DockerContainer.*
import tasks.DockerNetwork.DockerRemoveNetwork
import tasks.DockerVolume.DockerCreateVolume
import tasks.DockerVolume.DockerRemoveVolume
import tasks.Download


// Configures BuildKit.
@Suppress("unused")
class BuildKitPlugin : Plugin<Project> {

    companion object {

        fun String.normalizeDockerTag() = this.replace("""[^a-zA-Z0-9._-]""".toRegex(), "-")

        // The name of the container that is running the buildkit daemon.
        val Project.buildKitBuilder: Provider<String>
            get() = rootProject.tasks.named<DockerStartContainer>("startBuilder").map { it.name.get() }

        val Project.buildKitExecutable: Provider<RegularFile>
            get() = rootProject.tasks.named<BuildkitExecutable>("unpackBuildKit").flatMap { it.executable }

        // The registry/repository to use when building/pushing images.
        // It will default to the local registry if not given.
        val Project.buildKitRepository: Provider<String>
            get() = rootProject.tasks.named<CreateRegistry>("createRegistry").map {
                (properties.getOrDefault("isle.buildkit.build-arg.repository", "") as String).ifEmpty {
                    it.registry
                }
            }

        // The tag to use when building/pushing images.
        val Project.buildKitTag: String
            get() = (properties.getOrDefault("isle.buildkit.build-arg.tag", "latest") as String).normalizeDockerTag()

        // Some repositories require authentication.
        val Project.buildKitRepositoryUser: String
            get() = properties.getOrDefault("isle.buildkit.repository.user", "") as String

        // Some repositories require authentication.
        val Project.buildKitRepositoryPassword: String
            get() = properties.getOrDefault("isle.buildkit.repository.password", "") as String

        // The tag to use when building/pushing images.
        val Project.buildKitImages: Set<String>
            get() = (properties.getOrDefault("isle.buildkit.images", "") as String)
                .split(',')
                .map { it.trim().normalizeDockerTag() }
                .filter { it.isNotEmpty() }
                .toSet()

        // Load images after building, if not specified images will be pulled instead by tasks that require them.
        val Project.buildKitLoad: Boolean
            get() = (properties.getOrDefault("isle.buildkit.load", "true") as String).toBoolean()

        // Push images after building, required as buildkit needs to reference images in downstream images.
        val Project.buildKitPush: Boolean
            get() = (properties.getOrDefault("isle.buildkit.push", "true") as String).toBoolean()

        // Builder properties.
        val Project.buildKitContainer: String
            get() = properties.getOrDefault("isle.buildkit.container", "isle-buildkit") as String

        val Project.buildKitVolume: String
            get() = properties.getOrDefault("isle.buildkit.volume", "isle-buildkit") as String

        val Project.buildKitImage: String
            get() = properties.getOrDefault("isle.buildkit.image", "moby/buildkit:v0.11.0-rc1") as String

        // Only applies to linux hosts, Docker Desktop comes bundled with Qemu.
        // Allows us to build cross-platform images by emulating the target platform.
        val Project.buildKitQemuImage: String
            get() = properties.getOrDefault("isle.buildkit.qemu.image", "tonistiigi/binfmt:qemu-v7.0.0-28") as String

        // The platforms to build images for, If unspecified it will target the
        // host platform. It is possible to target multiple platforms as builder
        // will use emulation for architectures that do no match the host.
        val Project.buildKitPlatforms: Set<String>
            get() = (properties.getOrDefault("isle.buildkit.platforms", "") as String)
                .split(',')
                .filter { it.isNotBlank() }
                .toSet()

        // Inline
        // Embed the cache into the image, and push them to the registry together.

        // Enable inline cache import/export.
        val Project.buildKitCacheInlineEnableImport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.inline.enable.import", "false") as String).toBoolean()

        val Project.buildKitCacheInlineEnableExport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.inline.enable.export", "false") as String).toBoolean()

        // Registry
        // Push the image and the cache separately

        // Enable registry cache import/export.
        val Project.buildKitCacheRegistryEnableImport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.registry.enable.import", "false") as String).toBoolean()

        val Project.buildKitCacheRegistryEnableExport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.registry.enable.export", "false") as String).toBoolean()

        // The repository to push/pull image cache to/from.
        val Project.buildKitCacheRegistryRepository: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.repository", "islandora") as String

        val Project.buildKitCacheRegistryTagPrefix: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.tag-prefix", "cache") as String

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.buildKitCacheRegistryMode: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.mode", "max") as String

        // Compression type for layers newly created and cached.
        // estargz should be used with oci-mediatypes=true.
        val Project.buildKitCacheRegistryCompression: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.compression", "estargz") as String

        // Compression level for gzip, estargz (0-9) and zstd (0-22)
        val Project.buildKitCacheRegistryCompressionLevel: Int
            get() = (properties.getOrDefault("isle.buildkit.cache.registry.compression-level", "5") as String).toInt()

        // The repository to push/pull image cache to/from.
        val Project.buildKitCacheRegistryUser: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.user", "") as String

        // The repository to push/pull image cache to/from.
        val Project.buildKitCacheRegistryPassword: String
            get() = properties.getOrDefault("isle.buildkit.cache.registry.password", "") as String

        // Local
        // Export to a local directory

        // Enable local cache import/export.
        val Project.buildKitCacheLocalEnableImport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.local.enable.import", "false") as String).toBoolean()

        val Project.buildKitCacheLocalEnableExport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.local.enable.export", "false") as String).toBoolean()

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.buildKitCacheLocalMode: String
            get() = properties.getOrDefault("isle.buildkit.cache.local.mode", "max") as String

        // Compression type for layers newly created and cached.
        // estargz should be used with oci-mediatypes=true.
        val Project.buildKitCacheLocalCompression: String
            get() = properties.getOrDefault("isle.buildkit.cache.local.compression", "estargz") as String

        // Compression level for gzip, estargz (0-9) and zstd (0-22)
        val Project.buildKitCacheLocalCompressionLevel: Int
            get() = (properties.getOrDefault("isle.buildkit.cache.local.compression-level", "5") as String).toInt()

        // GitHub Actions
        // Export to GitHub Actions cache.
        // @see https://github.com/moby/buildkit#github-actions-cache-experimental
        // Enable registry cache import/export.

        val Project.buildKitCacheGitHubActionsEnableImport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.gha.enable.import", "false") as String).toBoolean()

        val Project.buildKitCacheGitHubActionsEnableExport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.gha.enable.export", "false") as String).toBoolean()

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.buildKitCacheGitHubActionsMode: String
            get() = properties.getOrDefault("isle.buildkit.cache.gha.mode", "max") as String

        // S3 (or compatible)
        // Stores metadata and layers in s3 bucket.
        // Requires Buildkit v0.11.0-rc1 or later

        // Enable s3 cache import/export.
        val Project.buildKitCacheS3EnableImport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.s3.enable.import", "false") as String).toBoolean()

        val Project.buildKitCacheS3EnableExport: Boolean
            get() = (properties.getOrDefault("isle.buildkit.cache.s3.enable.export", "false") as String).toBoolean()

        val Project.buildKitCacheS3Region: String
            get() = properties.getOrDefault("isle.buildkit.cache.s3.region", "us-east-1") as String

        val Project.buildKitCacheS3Bucket: String
            get() = properties.getOrDefault("isle.buildkit.cache.s3.bucket", "isle-build-cache") as String

        val Project.buildKitCacheS3Endpoint: String
            get() = properties.getOrDefault(
                "isle.buildkit.cache.s3.endpoint_url",
                "https://nyc3.digitaloceanspaces.com"
            ) as String

        // Specify cache layers to export
        // - min: only export layers for the resulting image
        // - max: export all the layers of all intermediate steps
        val Project.buildKitCacheS3Mode: String
            get() = properties.getOrDefault("isle.buildkit.cache.s3.mode", "max") as String

        // Required to populate the cache.
        val Project.buildKitCacheS3AccessKey: String
            get() = properties.getOrDefault("isle.buildkit.cache.s3.access_key_id", "") as String

        val Project.buildKitCacheS3Secret: String
            get() = properties.getOrDefault("isle.buildkit.cache.s3.secret_access_key", "") as String
    }

    open class DockerBuildKitExtension constructor(objects: ObjectFactory, providers: ProviderFactory) {
        open class BuildKitExtension(private val name: String) : Named {
            var sha256: String = ""
            var platform: Boolean = false
            override fun getName(): String = name
        }

        val os = DefaultNativePlatform.getCurrentOperatingSystem()!!
        val arch = DefaultNativePlatform.getCurrentArchitecture()!!

        var version = "v0.10.6"
        var baseUrl = "https://github.com/moby/buildkit/releases/download"

        internal val executables = objects.domainObjectContainer(BuildKitExtension::class.java)

        fun buildkit(name: String, action: Action<BuildKitExtension>) {
            executables.create(name, action)
        }

        val buildkit: BuildKitExtension
            get() = executables.find { it.platform }!!

        val url = objects.property<String>().convention(providers.provider {
            "${baseUrl}/${version}/${buildkit.name}"
        })

        companion object {
            val Project.buildkit: DockerBuildKitExtension
                get() =
                    extensions.findByType() ?: extensions.create("buildkit")

            fun Project.buildkit(action: Action<DockerBuildKitExtension>) {
                action.execute(buildkit)
            }

        }
    }

    @CacheableTask
    open class BuildkitExecutable : DefaultTask() {

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val archive = project.objects.fileProperty()

        @OutputFile
        val executable = project.objects.fileProperty().convention(project.layout.buildDirectory.file("buildctl"))

        @TaskAction
        fun exec() {
            project.copy {
                from(project.tarTree(archive.get().asFile)) {
                    include("bin/buildctl")
                    eachFile {
                        path = name
                    }
                }
                into(executable.get().asFile.parent)
            }
        }
    }

    // https://github.com/moby/buildkit/blob/v0.10.6/docs/buildkitd.toml.md
    @CacheableTask
    open class BuildkitConfiguration : DefaultTask() {
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

        @Internal
        val dest = project.objects.directoryProperty().convention(project.layout.buildDirectory)

        @OutputFile
        val config = project.objects.fileProperty().convention(dest.map { it.file("buildkitd.toml") })

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
                // Also, a local registry is required to push / pull form.
                config.get().asFile.writeText(
                    """
                    [worker.containerd]
                      enabled = false
                    [worker.oci]
                      enabled = true
                      gc = false
                    [registry."${registry.get()}"]
                      insecure=false
                      ca=["/certs/${rootCA.get().asFile.name}"]
                      [[registry."${registry.get()}".keypair]]
                        key="/certs/${key.get().asFile.name}"
                        cert="/certs/${cert.get().asFile.name}"
                """.trimIndent()
                )
            }
        }
    }

    open class BuildkitDaemon : DockerCreateContainer() {
        @Input
        val network = project.objects.property<String>()

        @Input
        val volume = project.objects.property<String>()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val cert = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val key = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val rootCA = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val config = project.objects.fileProperty()

        init {
            options.addAll(project.provider {
                listOf(
                    "--privileged",
                    "--network", network.get(),
                    "--volume", "${cert.get().asFile.absolutePath}:/certs/${cert.get().asFile.name}",
                    "--volume", "${key.get().asFile.absolutePath}:/certs/${key.get().asFile.name}",
                    "--volume", "${rootCA.get().asFile.absolutePath}:/certs/${rootCA.get().asFile.name}",
                    "--volume", "${config.get().asFile.absolutePath}:/etc/buildkit/buildkitd.toml",
                    "--volume", "${volume.get()}:/var/lib/buildkit",
                )
            })
        }
    }

    open class BuildCtlDiskUsage : BuildCtl() {
        @TaskAction
        fun diskUsage() {
            project.exec {
                workingDir(project.projectDir)
                environment(hostEnvironmentVariables)
                commandLine(executablePath, "du", "-v")
            }
        }
    }

    open class BuildCtlPrune : BuildCtl() {
        @TaskAction
        fun prune() {
            project.exec {
                workingDir(project.projectDir)
                environment(hostEnvironmentVariables)
                commandLine(executablePath, "prune")
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        apply<CertificateGenerationPlugin>()
        apply<RegistryPlugin>()

        afterEvaluate {
            buildkit {
                // Apply defaults if not provided.
                if (executables.isEmpty()) {
                    buildkit("buildkit-${version}.linux-amd64.tar.gz") {
                        sha256 = "9a21a41298c4a2a7a2b57cb90d37463d3a9057aedfe97a04b0e4fd6f622549d8"
                        platform = os.isLinux
                    }
                    buildkit("buildkit-${version}.darwin-amd64.tar.gz") {
                        sha256 = "3ffcb3910b337ce74868c36f1fef6ef4c0b32f7f16f54e9acd004ce2f3ae5bd2"
                        platform = os.isMacOsX && arch.isAmd64
                    }
                    buildkit("buildkit-${version}.darwin-arm64.tar.gz") {
                        sha256 = "eaad6698b4013e67290fe3515888916b8ea99aa86d296af8d7bcb61da8e95ec5"
                        platform = os.isMacOsX && arch.isArm
                    }
                    buildkit("buildkit-${version}-windows-amd64.tar.gz") {
                        sha256 = "6095f8f8fab13f3c9cb1df4c63e4160cd092041d70358a9ee2b56db95bd7d1ef"
                        platform = os.isWindows
                    }
                }
            }
        }

        val generateCertificates = tasks.named<GenerateCerts>("generateCertificates")
        val createRegistry = tasks.named<CreateRegistry>("createRegistry")
        val startRegistry = tasks.named<DockerStartContainer>("startRegistry")
        val destroyRegistryNetwork = tasks.named<DockerRemoveNetwork>("destroyRegistryNetwork")

        val downloadBuildKit by tasks.registering(Download::class) {
            group = "Isle Buildkit"
            description = "Downloads buildctl for interacting with buildkit"
            url.set(buildkit.url)
            sha256.set(buildkit.buildkit.sha256)
        }

        val unpackBuildKit by tasks.registering(BuildkitExecutable::class) {
            group = "Isle Buildkit"
            description = "Unpacks buildctl from the downloaded archive"
            archive.set(downloadBuildKit.flatMap { it.dest })
        }

        val installBinFmt by tasks.registering(Exec::class) {
            group = "Isle Buildkit"
            description = "Install https://github.com/tonistiigi/binfmt to enable multi-arch builds on Linux."
            commandLine = listOf(
                "docker",
                "container",
                "run",
                "--rm",
                "--privileged",
                buildKitQemuImage,
                "--install", "all"
            )
            // Cross building with Qemu is already installed with Docker Desktop, so we only need to install on Linux.
            // Additionally, it does not work with non x86_64 hosts.
            onlyIf {
                buildkit.os.isLinux && buildkit.arch.isAmd64
            }
        }

        val generateBuildkitConfig by tasks.registering(BuildkitConfiguration::class) {
            group = "Isle Buildkit"
            description =
                "Generate https://github.com/moby/buildkit/blob/master/docs/buildkitd.toml.md to configure buildkit."
            registry.set(createRegistry.map { it.registry })
            cert.set(generateCertificates.flatMap { it.cert })
            key.set(generateCertificates.flatMap { it.key })
            rootCA.set(generateCertificates.flatMap { it.rootCA })
        }

        val stopBuilder by tasks.registering(DockerStopContainer::class) {
            group = "Isle Registry"
            description = "Stops the buildkit container"
            name.set(buildKitContainer)
        }

        val destroyBuilder by tasks.registering(DockerRemoveContainer::class) {
            group = "Isle Buildkit"
            description = "Removes the buildkit container"
            name.set(buildKitContainer)
            dependsOn(stopBuilder)
        }

        val destroyBuilderVolume by tasks.registering(DockerRemoveVolume::class) {
            group = "Isle Buildkit"
            description = "Destroys the buildkit cache volume"
            volume.set(buildKitVolume)
            dependsOn(destroyBuilder) // Cannot remove volumes of active containers.
        }

        val createBuilderVolume by tasks.registering(DockerCreateVolume::class) {
            group = "Isle Buildkit"
            description = "Creates a volume for the buildkit cache"
            volume.set(buildKitVolume)
            mustRunAfter(destroyBuilderVolume)
        }

        val createBuilder by tasks.registering(BuildkitDaemon::class) {
            group = "Isle Buildkit"
            description = "Creates a container for the buildkit daemon"
            name.set(buildKitContainer)
            image.set(buildKitImage)
            volume.set(createBuilderVolume.map { it.volume.get() })
            network.set(createRegistry.map { it.network.get() })
            cert.set(generateCertificates.flatMap { it.cert })
            key.set(generateCertificates.flatMap { it.key })
            rootCA.set(generateCertificates.flatMap { it.rootCA })
            config.set(generateBuildkitConfig.flatMap { it.config })
            dependsOn(installBinFmt, unpackBuildKit)
            mustRunAfter(destroyBuilder)
        }

        tasks.register<DockerStartContainer>("startBuilder") {
            group = "Isle Buildkit"
            description = "Starts the buildkit container"
            name.set(buildKitContainer)
            dependsOn(createBuilder, startRegistry) // Requires connection with the local registry to push.
            doLast {
                // We rely on CuRL in the builder to interact with registry to download manifests, etc.
                project.exec {
                    commandLine("docker", "exec", this@register.name.get(), "apk", "add", "curl")
                }
            }
        }

        destroyRegistryNetwork.configure {
            dependsOn(destroyBuilder) // Cannot remove networks of active containers as they share the same network.
        }

        tasks.register<BuildCtlDiskUsage>("diskUsage") {
            group = "Isle Buildkit"
            description = "Display BuildKit disk usage"
        }

        tasks.register<BuildCtlPrune>("prune") {
            group = "Isle Buildkit"
            description = "Clean BuildKit build cache"
        }

        allprojects {
            // Auto-apply plugins to relevant projects.
            if (isDockerProject) {
                apply<BuildCtlPlugin>()
            }
        }

        subprojects {
            // Enforce build order for dependencies.
            tasks.withType<BuildCtlPlugin.BuildCtlBuildImage> {
                val buildMetadata = project
                    .rootProject
                    .allprojects
                    .filter { it.isDockerProject && requiredImages.contains(it.name) }
                    .map {
                        it.tasks.named<BuildCtlPlugin.BuildCtlBuildImage>("build").flatMap { task -> task.metadata }
                    }
                sourceBuildMetadata.setFrom(buildMetadata)
            }
        }

    }
}
