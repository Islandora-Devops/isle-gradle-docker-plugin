@file:Suppress("UnstableApiUsage")

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import tasks.DockerBuild
import tasks.DockerBuilder
import utils.imageTags
import utils.isDockerProject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

val os = DefaultNativePlatform.getCurrentOperatingSystem()!!
val arch = DefaultNativePlatform.getCurrentArchitecture()!!
val isleBuildkitGroup = "isle-buildkit"

extensions.findByName("buildScan")?.withGroovyBuilder {
    setProperty("termsOfServiceUrl", "https://gradle.com/terms-of-service")
    setProperty("termsOfServiceAgree", "yes")
}

// If set to true resources are freed as soon as they are no longer needed.
val isCI by extra((properties.getOrDefault("isCI", "false") as String).toBoolean())

// The repository to place the images into.
val repository by extra(properties.getOrDefault("docker.repository", "local") as String)
val isLocalRepository by extra(repository == "local")
// It's important to note that we’re using a domain containing a "." here, i.e. localhost.domain.
// If it were missing Docker would believe that localhost is a username, as in localhost/ubuntu.
// It would then try to push to the default Central Registry rather than our local repository.
val localRepository by extra("isle-buildkit.registry")

// The build driver to use.
val buildDriver by extra(properties.getOrDefault("docker.driver", "docker") as String)
val isDockerBuild by extra(buildDriver == "docker")
val isContainerBuild by extra(buildDriver == "docker-container")

// The mode to use when populating the registry cache.
@Suppress("unused")
val cacheToMode by extra(properties.getOrDefault("docker.cacheToMode",
    if (isDockerBuild) "inline" else "max") as String)

// Enable caching from/to repositories.
@Suppress("unused")
val cacheFromEnabled by extra((properties.getOrDefault("docker.cacheFrom", "true") as String).toBoolean())

@Suppress("unused")
val cacheToEnabled by extra((properties.getOrDefault("docker.cacheTo", "false") as String).toBoolean())

// Sources to search for images to use as caches when building.
@Suppress("unused")
val cacheFromRepositories by extra(
    (properties.getOrDefault("docker.cacheFromRepositories", "") as String)
        .split(',')
        .filter { it.isNotEmpty() }
        .map { it.trim() }
        .toSet()
        .let { repositories ->
            if (repositories.isEmpty()) {
                if (cacheToEnabled) {
                    // Can only cache from repositories in which we have cached to.
                    setOf("islandora", if (isLocalRepository) localRepository else repository)
                } else {
                    // Always cache to/from islandora.
                    setOf("islandora")
                }
            } else repositories
        }
)

// Repositories to push cache to (empty by default).
@Suppress("unused")
val cacheToRepositories by extra(
    (properties.getOrDefault("docker.cacheToRepositories", "") as String)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
        .let { repositories ->
            if (repositories.isEmpty()) {
                setOf(if (isLocalRepository) localRepository else repository)
            } else repositories
        }
)

// Optionally disable the build cache as well as the remote cache.
@Suppress("unused")
val noBuildCache by extra((properties.getOrDefault("docker.noCache", false) as String).toBoolean())

// Platforms to built images to target.
@Suppress("unused")
val buildPlatforms by extra(
    (properties.getOrDefault("docker.platforms", "") as String)
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
)

// Never empty if user does not specify it will default to 'latest'.
val tags by extra(
    (properties.getOrDefault("docker.tags", "") as String)
        .split(',')
        .filter { it.isNotEmpty() }
        .toSet()
        .let { tags ->
            if (tags.isEmpty()) {
                setOf("latest")
            } else tags
        }
)

// Communicate with docker using Java client API.
@Suppress("unused")
val dockerClient: DockerClient by extra {
    val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
    val httpClient = ApacheDockerHttpClient.Builder()
        .dockerHost(configBuilder.dockerHost)
        .sslConfig(configBuilder.sslConfig)
        .build()
    val dockerClient = DockerClientBuilder
        .getInstance()
        .withDockerHttpClient(httpClient)
        .build()
    project.gradle.buildFinished {
        dockerClient.close()
    }
    dockerClient
}

val installBinFmt by tasks.registering {
    group = isleBuildkitGroup
    description = "Install https://github.com/tonistiigi/binfmt to enable multi-arch builds on Linux."
    // Cross building with Qemu is already installed with Docker Desktop so we only need to install on Linux hosts.
    // Additionally it does not work with non x86_64 hosts.
    onlyIf {
        isContainerBuild && os.isLinux && arch.isAmd64
    }
    doLast {
        exec {
            commandLine = listOf(
                "docker",
                "run",
                "--rm",
                "--privileged",
                "tonistiigi/binfmt:qemu-v5.0.1",
                "--install", "all"
            )
        }
    }
}

// Local registry for use with the 'docker-container' driver.
val createLocalRegistry by tasks.registering {
    group = isleBuildkitGroup
    description = "Creates a local docker docker registry ('docker-container' or 'kubernetes' only)"
    onlyIf { !isDockerBuild }

    val volume by extra(objects.property<String>())
    volume.convention("isle-buildkit-registry")

    val network by extra(objects.property<String>())
    network.convention("isle-buildkit")

    val configFile by extra(objects.fileProperty())
    configFile.convention(project.layout.buildDirectory.file("config.toml"))

    doLast {
        // Create network (allows host DNS name resolution between builder and local registry).
        network.get().let {
            exec {
                commandLine = listOf("docker", "network", "create", it)
                isIgnoreExitValue = true // If it already exists it will return non-zero.
            }
        }

        // Create registry volume.
        exec {
            commandLine = listOf("docker", "volume", "create", volume.get())
        }

        // Check if the container is already running.
        val running = ByteArrayOutputStream().use { output ->
            exec {
                commandLine = listOf("docker", "container", "inspect", "-f", "{{.State.Running}}", localRepository)
                standardOutput = output
                isIgnoreExitValue = true // May not be running.
            }.exitValue == 0 && output.toString().trim().toBoolean()
        }
        // Start the local registry if not already started.
        if (!running) {
            exec {
                commandLine = listOf(
                    "docker",
                    "run",
                    "-d",
                    "--restart=always",
                    "--network=isle-buildkit",
                    "--env", "REGISTRY_HTTP_ADDR=0.0.0.0:80",
                    "--env", "REGISTRY_STORAGE_DELETE_ENABLED=true",
                    "--name=$localRepository",
                    "--volume=${volume.get()}:/var/lib/registry",
                    "registry:2"
                )
            }
        }
        // Allow insecure push / pull.
        configFile.get().asFile.run {
            parentFile.mkdirs()
            writeText(
                """
                [worker.oci]
                  enabled = true
                  gc = false
                [worker.containerd]
                  enabled = false
                  gc = false
                [registry."$localRepository"]
                  http = true
                  insecure = true

                """.trimIndent()
            )
        }
    }
    mustRunAfter("destroyLocalRegistry")
    finalizedBy("updateHostsFile")
}

// Destroys resources created by createLocalRegistry.
val destroyLocalRegistry by tasks.registering {
    group = isleBuildkitGroup
    description = "Destroys the local registry and its backing volume"
    doLast {
        createLocalRegistry.get().let { task ->
            val network: Property<String> by task.extra
            val volume: Property<String> by task.extra
            exec {
                commandLine = listOf("docker", "rm", "-f", localRepository)
                isIgnoreExitValue = true
            }
            exec {
                commandLine = listOf("docker", "network", "rm", network.get())
                isIgnoreExitValue = true
            }
            exec {
                commandLine = listOf("docker", "volume", "rm", volume.get())
                isIgnoreExitValue = true
            }
        }
    }
}

tasks.register("collectGarbageLocalRegistry") {
    group = isleBuildkitGroup
    description = "Deletes layers not referenced by any manifests in the local repository"
    doLast {
        exec {
            commandLine = listOf("docker",
                "exec",
                localRepository,
                "bin/registry",
                "garbage-collect",
                "/etc/docker/registry/config.yml")
        }
    }
    dependsOn(createLocalRegistry)
}

val getIpAddressOfLocalRegistry by tasks.registering {
    val ipAddress by extra(objects.property<String>())
    doLast {
        ipAddress.set(
            ByteArrayOutputStream().use { output ->
                exec {
                    commandLine = listOf(
                        "docker",
                        "container",
                        "inspect",
                        "-f",
                        "{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}",
                        localRepository
                    )
                    standardOutput = output
                }
                output.toString().trim()
            }
        )
    }
    dependsOn(createLocalRegistry)
}

// Generally only needed for debugging local repository.
tasks.register("updateHostsFile") {
    group = isleBuildkitGroup
    description = "Modifies /etc/hosts to include reference to local repository on the host"
    onlyIf { os.isLinux || os.isMacOsX }
    doLast {
        val ipAddress = getIpAddressOfLocalRegistry.get().let { task ->
            val ipAddress: Property<String> by task.extra
            ipAddress.get()
        }
        exec {
            // Removes any existing references to the local repository and appends local repository to the bottom.
            standardInput = ByteArrayInputStream(
                """
                sed -n \
                    -e '/^.*${localRepository}/!p' \
                    -e '${'$'}a${ipAddress}\t${localRepository}' \
                    /etc/hosts > /tmp/hosts
                cat /tmp/hosts > /etc/hosts
                """.trimIndent().toByteArray()
            )
            commandLine = listOf(
                "docker", "run", "--rm", "-i",
                "-v", "/etc/hosts:/etc/hosts",
                "alpine:3.11.6",
                "ash", "-s"
            )
        }
    }
    dependsOn(getIpAddressOfLocalRegistry)
}

val clean by tasks.registering {
    group = isleBuildkitGroup
    description = "Destroy absolutely everything"
    doLast {
        exec {
            commandLine = listOf("docker", "system", "prune", "-f")
            isIgnoreExitValue = true
        }
    }
    dependsOn(destroyLocalRegistry)
}

// Often easier to just use the default builder.
val useDefaultBuilder by tasks.registering {
    group = isleBuildkitGroup
    description = "Change the builder to use the Docker Daemon"
    doLast {
        project.exec {
            commandLine = listOf("docker", "buildx", "use", "default")
        }
    }
}

tasks.all {
    // Common settings for top level tasks.
    if (group?.equals(isleBuildkitGroup) == true) {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.INFO)
    }
}

subprojects {
    // Make all build directories relative to the root, only supports projects up to a depth of one for now.
    buildDir = rootProject.buildDir.resolve(projectDir.relativeTo(rootDir))
    layout.buildDirectory.set(buildDir)

    // If there is a docker file in the project add the appropriate tasks.
    if (isDockerProject) {
        val tag = tags.first()

        val defaultTags = imageTags(repository)
        val defaultBuildArgs = mapOf(
            "repository" to repository,
            "tag" to tag
        )

        val localRepositoryTags = imageTags(localRepository)
        val localRepositoryBuildArgs = mapOf(
            "repository" to localRepository,
            "tag" to tag
        )

        // Allows building both x86_64 and arm64 using emulation supported in version 19.03 and up as well Docker Desktop.
        val createBuilder by tasks.registering(DockerBuilder::class) {
            group = isleBuildkitGroup
            description = "Creates and starts the builder ('docker-container' or 'kubernetes' only)"
            onlyIf { !isDockerBuild }
            // Make sure the builder can find our local repository.
            options.run {
                append.set(true)
                driver.set(buildDriver)
                name.set("isle-buildkit-${buildDriver}-${project.name}")
                node.set("isle-buildkit-${buildDriver}-${project.name}-node")
                when (buildDriver) {
                    "docker-container" -> {
                        driverOpts.set(createLocalRegistry.map { task ->
                            val network: Property<String> by task.extra
                            "network=${network.get()},image=moby/buildkit:v0.8.2"
                        })
                        config.set(createLocalRegistry.map { task ->
                            val configFile: RegularFileProperty by task.extra
                            configFile.get()
                        })
                    }
                }
                use.set(false)
            }
            dependsOn(installBinFmt, createLocalRegistry)
            mustRunAfter("destroyBuilder")
        }

        val destroyBuilder by tasks.registering {
            group = isleBuildkitGroup
            description = "Destroy the builder and its cache ('docker-container' or 'kubernetes' only)"
            doLast {
                exec {
                    commandLine = listOf("docker", "buildx", "rm", createBuilder.get().options.name.get())
                    isIgnoreExitValue = true
                }
            }
        }

        // Clean up builders as well.
        clean.configure {
            dependsOn(destroyBuilder)
        }

        val setupBuilder by tasks.registering {
            group = isleBuildkitGroup
            description = "Setup the builder according to project properties"
            when (buildDriver) {
                "docker" -> dependsOn(useDefaultBuilder)
                else -> dependsOn(createBuilder)
            }
        }

        tasks.register<DockerBuild>("build") {
            group = isleBuildkitGroup
            description = "Build docker image(s)"
            options.run {
                // If we are building with "docker-container" or "kubernetes" we must push as we need to be able to pull
                // from from the registry when building downstream images.
                push.set(!isDockerBuild)
                // Force the tags / build args to be relative to our local repository.
                if (!isDockerBuild && isLocalRepository) {
                    tags.set(localRepositoryTags)
                    buildArgs.set(localRepositoryBuildArgs)
                }
                mustRunAfter("delete")
            }
        }

        tasks.register<DockerBuild>("push") {
            group = isleBuildkitGroup
            description = "Build and push docker image(s)"
            options.run {
                push.set(true)
                // Force the tags / build args to be relative to our local repository.
                if (isLocalRepository) {
                    tags.set(localRepositoryTags)
                    buildArgs.set(localRepositoryBuildArgs)
                }
            }
            mustRunAfter("delete")
        }

        tasks.register("delete") {
            group = isleBuildkitGroup
            description = "Delete image(s) from local registry"
            doLast {
                val ipAddress = getIpAddressOfLocalRegistry.get().let { task ->
                    val ipAddress: Property<String> by task.extra
                    ipAddress.get()
                }
                tags.plus("cache").map { tag ->
                    val baseUrl = "http://$ipAddress/v2/${project.name}/manifests"
                    val accept = if (tag == "cache")
                        "application/vnd.oci.image.index.v1+json"
                    else
                        "application/vnd.docker.distribution.manifest.v2+json"
                    (URL("$baseUrl/$tag").openConnection() as HttpURLConnection).run {
                        requestMethod = "GET"
                        setRequestProperty("Accept", accept)
                        headerFields["Docker-Content-Digest"]?.first()
                    }?.let { digest ->
                        logger.info("Deleting ${project.name}/$tag:$digest")
                        (URL("$baseUrl/$digest").openConnection() as HttpURLConnection).run {
                            requestMethod = "DELETE"
                            if (responseCode == 200 || responseCode == 202) {
                                logger.info("Successful ($responseCode): $responseMessage")
                            } else {
                                throw RuntimeException("Failed ($responseCode) - $responseMessage")
                            }
                        }
                    }
                }
            }
            dependsOn(getIpAddressOfLocalRegistry)
        }

        // Task groups all sub-project tasks.tests into single task.
        tasks.register("test") {
            group = isleBuildkitGroup
            description = "Test docker image(s)"
            dependsOn(project.subprojects.mapNotNull { it.tasks.matching { task -> task.name == "test" } })
        }

        // All build tasks have a number of shared defaults that can be overridden.
        tasks.withType<DockerBuild> {
            // Default arguments required for building.
            options.run {
                if (!isDockerBuild) {
                    builder.set(createBuilder.map { it.options.name.get() })
                }
                tags.convention(defaultTags)
                buildArgs.convention(defaultBuildArgs)
            }
            // Require builder to build.
            dependsOn(setupBuilder)
            // If destroying resources as well make sure build tasks run after after the destroy tasks.
            mustRunAfter(clean, destroyBuilder, destroyLocalRegistry)
            // We are either building or pushing neither both in a CI environment.
            // This is just to keep us within the ~12 GB of free space that Github Actions gives us.
            doLast {
                if (!isDockerBuild && isCI) {
                    exec {
                        commandLine = listOf("docker", "buildx", "rm", createBuilder.get().options.name.get())
                        isIgnoreExitValue = true
                    }
                }
            }
        }
    }
}
