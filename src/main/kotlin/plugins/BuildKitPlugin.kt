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

        // The name of the container that is running the buildkit daemon.
        val Project.buildKitBuilder: Provider<String>
            get() = rootProject.tasks.named<DockerStartContainer>("startBuilder").map { it.name.get() }

        val Project.buildKitExecutable: Provider<RegularFile>
            get() = rootProject.tasks.named<BuildkitExecutable>("unpackBuildKit").flatMap { it.executable }

        // The registry/repository to use when building/pushing images.
        // It will default to the local registry if not given.
        val Project.buildKitRepository: Provider<String>
            get() = rootProject.tasks.named<CreateRegistry>("createRegistry").map {
                (properties["buildkit.build-arg.repository"] as String?) ?: it.registry
            }

        // The tag to use when building/pushing images.
        val Project.buildKitTag: String
            get() = properties.getOrDefault("buildkit.build-arg.tag", "latest") as String

        val Project.buildKitContainer: String
            get() = properties.getOrDefault("buildkit.container", "isle-buildkit") as String

        val Project.buildKitVolume: String
            get() = properties.getOrDefault("buildkit.volume", "isle-buildkit") as String

        val Project.buildKitImage: String
            get() = properties.getOrDefault("buildkit.image", "moby/buildkit:v0.10.6") as String

        val Project.buildKitQemuImage: String
            get() = properties.getOrDefault("buildkit.qemu.image", "tonistiigi/binfmt:qemu-v7.0.0-28") as String

        val Project.buildKitPlatforms: Set<String>
            get() = (properties.getOrDefault("buildkit.platforms", "") as String)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
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
            // Keep only up to 5GB of storage.
            if (System.getenv("GITHUB_ACTIONS") == "true") {
                config.get().asFile.writeText(
                    """
                    [worker.containerd]
                      enabled = false
                    [worker.oci]
                      enabled = true
                      gc = true
                      gckeepstorage = 5000
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
                    .map { it.tasks.named<BuildCtlPlugin.BuildCtlBuildImage>("build").flatMap { task -> task.metadata } }
                sourceBuildMetadata.setFrom(buildMetadata)
            }
        }

    }
}