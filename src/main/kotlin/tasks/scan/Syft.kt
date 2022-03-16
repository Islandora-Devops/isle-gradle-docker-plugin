package tasks.scan

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*

// Wrapper around a call to `syft`, please refer to the documentation for more information:
// https://github.com/anchore/syft
@CacheableTask
open class Syft : DefaultTask() {

    // Digest file for the image anchore/syft.
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val syftDigestFile = project.objects.fileProperty()

    // The image to process (assumed to exits locally).
    @Input
    val image = project.objects.property<String>()

    // Ensure that we re-run if the digest changes.
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val imageDigestFile = project.objects.fileProperty()

    // A json file representing the generated Software Bill of Materials.
    @OutputFile
    val sbom = project.objects.fileProperty().convention(project.layout.buildDirectory.file("sbom.json"))

    @TaskAction
    fun exec() {
        sbom.get().asFile.outputStream().use { output ->
            project.exec {
                standardOutput = output
                commandLine = listOf(
                    "docker", "run",
                    "--rm",
                    "-v", "/var/run/docker.sock:/var/run/docker.sock",
                    syftDigestFile.get().asFile.readText(),
                    "-o", "json",
                    image.get()
                )
            }
        }
    }
}
