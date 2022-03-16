package tasks.scan

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*

// Wrapper around a call to `syft`, please refer to the documentation for more information:
// https://github.com/anchore/syft
@CacheableTask
open class Grype : DefaultTask() {

    // Digest file for the image anchore/syft.
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val grypeDigestFile = project.objects.fileProperty()

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
                    "docker", "run",
                    "--rm",
                    "-i",
                    "-e", "GRYPE_DB_CACHE_DIR=/cache",
                    "-e", "GRYPE_DB_AUTO_UPDATE=false",
                    "-v", "grype:/cache:", // Volume created by 'GrypeUpdateDB' task.
                )
                if (config.isPresent) {
                    command.addAll(listOf("-v", "${config.get().asFile.absolutePath}:/grype.yaml"))
                }
                // Docker image
                command.add(grypeDigestFile.get().asFile.readText())
                if (config.isPresent) {
                    command.addAll(listOf("--config", "/grype.yaml"))
                }
                // Arguments to grype.
                if (failOn.isPresent) {
                    command.addAll(listOf("--fail-on", failOn.get()))
                }
                if (onlyFixed.get()) {
                    command.add("--only-fixed")
                }
                command.addAll( listOf("-o", format.get()))
                project.exec {
                    standardInput = input
                    standardOutput = output
                    commandLine = command
                }
            }
        }
    }
}
