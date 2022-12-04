package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import plugins.CertificateGenerationPlugin.GenerateCerts
import tasks.DockerContainer.*
import tasks.DockerNetwork.DockerCreateNetwork
import tasks.DockerNetwork.DockerRemoveNetwork
import tasks.DockerVolume.DockerCreateVolume
import tasks.DockerVolume.DockerRemoveVolume

// Creates a docker registry hosted locally with the given parameters that can be used by buildkit.
@Suppress("unused")
class RegistryPlugin : Plugin<Project> {

    companion object {
        // It's important to note that we’re using a domain containing a "." here, i.e. localhost.domain.
        // If it were missing Docker would believe that localhost is a username, as in localhost/ubuntu.
        // It would then try to push to the default Central Registry rather than our local repository.
        // *.islandora.dev makes for a good default as we can generate certificates for it and avoid many problems.
        val Project.registryDomain: String
            get() = properties.getOrDefault("registry.domain", "islandora.dev") as String

        val Project.registryPort: Int
            get() = (properties.getOrDefault("registry.port", "443") as String).toInt()

        val Project.bindPort: Boolean
            get() = (properties.getOrDefault("registry.bind.port", "false") as String).toBoolean()

        // The container should have the same name as the domain so that buildkit builder can find it by name.
        val Project.registryContainer: String
            get() = properties.getOrDefault("registry.container", "isle-registry") as String

        val Project.registryNetwork: String
            get() = properties.getOrDefault("registry.network", "isle-registry") as String

        val Project.registryVolume: String
            get() = properties.getOrDefault("registry.volume", "isle-registry") as String

        val Project.registryImage: String
            get() = properties.getOrDefault("registry.image", "registry:2") as String
    }

    open class CreateRegistry : DockerCreateContainer() {
        @Input
        val domain = project.objects.property<String>().convention(project.registryDomain)

        @Input
        val port = project.objects.property<Int>().convention(project.registryPort)

        @Input
        val bindPort = project.objects.property<Boolean>().convention(project.bindPort)

        @Input
        val network = project.objects.property<String>().convention(project.registryNetwork)

        @Input
        val volume = project.objects.property<String>().convention(project.registryVolume)

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val cert = project.objects.fileProperty()

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val key = project.objects.fileProperty()

        @get:Internal
        val registry: String
            get() = if (port.get() == 443) domain.get() else "${domain.get()}:${port.get()}"

        init {
            options.addAll(project.provider {
                listOf(
                    "--network=${network.get()}",
                    "--network-alias=${domain.get()}",
                    "--env", "REGISTRY_HTTP_ADDR=0.0.0.0:${port.get()}",
                    "--env", "REGISTRY_STORAGE_DELETE_ENABLED=true",
                    "--env", "REGISTRY_HTTP_TLS_CERTIFICATE=/certs/cert.pem",
                    "--env", "REGISTRY_HTTP_TLS_KEY=/certs/privkey.pem",
                    "--volume=${cert.get().asFile.absolutePath}:/certs/cert.pem:ro",
                    "--volume=${key.get().asFile.absolutePath}:/certs/privkey.pem:ro",
                    "--volume=${volume.get()}:/var/lib/registry",
                ) + if (bindPort.get()) listOf("-p", "${port.get()}:${port.get()}") else emptyList()
            })
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        apply<CertificateGenerationPlugin>()

        val stopRegistry by tasks.registering(DockerStopContainer::class) {
            group = "Isle Registry"
            description = "Stops the local registry"
            name.set(registryContainer)
        }

        val destroyRegistry by tasks.registering(DockerRemoveContainer::class) {
            group = "Isle Registry"
            description = "Destroys the local registry"
            name.set(registryContainer)
            dependsOn(stopRegistry)
            finalizedBy("destroyRegistryNetwork")
        }

        val destroyRegistryVolume by tasks.registering(DockerRemoveVolume::class) {
            group = "Isle Registry"
            description = "Destroys the local docker registry volume"
            volume.set(registryVolume)
            dependsOn(destroyRegistry) // Cannot remove volumes of active containers.
        }

        val destroyRegistryNetwork by tasks.registering(DockerRemoveNetwork::class) {
            group = "Isle Registry"
            description = "Destroys the local docker registry network"
            network.set(registryNetwork)
            dependsOn(destroyRegistry) // Cannot remove networks of active containers.
        }

        val createRegistryVolume by tasks.registering(DockerCreateVolume::class) {
            group = "Isle Registry"
            description = "Creates a volume for the local docker registry"
            volume.set(registryVolume)
            mustRunAfter(destroyRegistryVolume)
        }

        val createRegistryNetwork by tasks.registering(DockerCreateNetwork::class) {
            group = "Isle Registry"
            description = "Creates a network for the local docker registry"
            network.set(registryNetwork)
            mustRunAfter(destroyRegistryNetwork)
        }

        val generateCertificates = tasks.named<GenerateCerts>("generateCertificates")

        val createRegistry by tasks.registering(CreateRegistry::class) {
            group = "Isle Registry"
            description = "Starts a the local docker registry if not already running"
            name.set(registryContainer)
            image.set(registryImage)
            network.set(createRegistryNetwork.map { it.network.get() })
            volume.set(createRegistryVolume.map { it.volume.get() })
            cert.set(generateCertificates.flatMap { it.cert })
            key.set(generateCertificates.flatMap { it.key })
            mustRunAfter(destroyRegistry)
        }

        tasks.register<DockerStartContainer>("startRegistry") {
            group = "Isle Registry"
            description = "Starts the local registry"
            name.set(registryContainer)
            dependsOn(createRegistry)
        }

    }
}