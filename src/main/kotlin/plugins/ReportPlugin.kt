package plugins

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import plugins.BuildCtlPlugin.BuildCtlLoadImage
import plugins.ReportsPlugin.Companion.grypeConfig
import plugins.ReportsPlugin.Companion.grypeFailOnSeverity
import plugins.ReportsPlugin.Companion.grypeFormat
import plugins.ReportsPlugin.Companion.grypeOnlyFixed
import plugins.ReportsPlugin.UpdateGrypeDB
import tasks.DockerPull

// Generate reports via Syft and Grype.
@Suppress("unused")
class ReportPlugin : Plugin<Project> {

    // Wrapper around a call to `syft`, please refer to the documentation for more information:
    // https://github.com/anchore/syft
    @CacheableTask
    open class Syft : DefaultTask() {

        // anchore/syft image.
        @Input
        val syft = project.objects.property<String>()

        // The image to process (assumed to exist locally).
        @Input
        val image = project.objects.property<String>()

        // A json file representing the generated Software Bill of Materials.
        @OutputFile
        val sbom = project.objects.fileProperty().convention(project.layout.buildDirectory.file("sbom.json"))

        @TaskAction
        fun exec() {
            sbom.get().asFile.outputStream().use { output ->
                project.exec {
                    standardOutput = output
                    commandLine = listOf(
                        "docker", "container", "run", "--rm",
                        "-v", "/var/run/docker.sock:/var/run/docker.sock",
                        syft.get(),
                        "-o", "json",
                        image.get()
                    )
                }
            }
        }
    }

    // Wrapper around a call to `syft`, please refer to the documentation for more information:
    // https://github.com/anchore/syft
    @CacheableTask
    open class Grype : DefaultTask() {
        // anchore/grype image.
        @Input
        val grype = project.objects.property<String>()

        // anchore/grype image.
        @InputDirectory
        @PathSensitive(PathSensitivity.RELATIVE)
        val database = project.objects.directoryProperty()

        // A json file representing the generated Software Bill of Materials.
        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        val sbom = project.objects.fileProperty()

        @Input
        val format = project.objects.property<String>().convention("table")

        @Input
        @Optional
        val failOn = project.objects.property<String>()

        @InputFile
        @Optional
        @PathSensitive(PathSensitivity.RELATIVE)
        val config = project.objects.fileProperty()

        @Input
        val onlyFixed = project.objects.property<Boolean>().convention(false)

        @OutputFile
        val report = project.objects.fileProperty().convention(format.flatMap {
            val dir = project.layout.buildDirectory
            val name = "${project.name}-grype"
            when (it) {
                "json" -> dir.file("${name}.json")
                "table" -> dir.file("${name}.md")
                "cyclonedx" -> dir.file("${name}.xml")
                else -> dir.file("${name}.txt")
            }
        })

        @TaskAction
        fun exec() {
            sbom.get().asFile.inputStream().use { input ->
                report.get().asFile.outputStream().use { output ->
                    // Arguments to docker.
                    val command = mutableListOf(
                        "docker", "container", "run", "--rm", "-i",
                        "-e", "GRYPE_DB_CACHE_DIR=/cache",
                        "-e", "GRYPE_DB_AUTO_UPDATE=false",
                        "-v", "${database.get().asFile.absolutePath}:/cache",
                    )
                    if (config.isPresent) {
                        command.addAll(listOf("-v", "${config.get().asFile.absolutePath}:/grype.yaml"))
                    }
                    // Docker image
                    command.add(grype.get())
                    if (config.isPresent) {
                        command.addAll(listOf("--config", "/grype.yaml"))
                    }
                    // Arguments to grype.
                    if (failOn.get().isNotBlank()) {
                        command.addAll(listOf("--fail-on", failOn.get()))
                    }
                    if (onlyFixed.get()) {
                        command.add("--only-fixed")
                    }
                    command.addAll(listOf("-o", format.get()))
                    project.exec {
                        standardInput = input
                        standardOutput = output
                        commandLine = command
                    }
                }
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        // One off tasks live on the root project.
        val pullSyft = rootProject.tasks.named<DockerPull>("pullSyft")
        val pullGrype = rootProject.tasks.named<DockerPull>("pullGrype")
        val updateGrypeDB = rootProject.tasks.named<UpdateGrypeDB>("updateGrypeDB")

        // Requires the build plugin to be included, or this will fail at configure time.
        val load = tasks.named<BuildCtlLoadImage>("load")

        val syft by tasks.registering(Syft::class) {
            group = "Isle Reports"
            description = "Generate a software bill of material with Syft"
            syft.set(pullSyft.map { it.digest })
            image.set(load.map { it.digest })
        }

        tasks.register<Grype>("grype") {
            group = "Isle Reports"
            description = "Process the software bill of material with Grype"
            grype.set(pullGrype.map { it.digest })
            database.set(updateGrypeDB.flatMap { it.database })
            config.set(grypeConfig)
            failOn.set(grypeFailOnSeverity)
            format.set(grypeFormat)
            onlyFixed.set(grypeOnlyFixed)
            sbom.set(syft.flatMap { it.sbom })
            dependsOn(":updateGrypeDB")
        }

    }
}