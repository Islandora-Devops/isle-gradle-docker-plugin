package tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.internal.jvm.Jvm
import utils.execCaptureOutput

open class GenerateCerts : DefaultTask() {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    val executable = project.objects.fileProperty()

    @Internal
    val dest = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("certs"))

    @OutputFile
    val cert = project.objects.fileProperty().convention(dest.map { it.file("cert.pem") })

    @OutputFile
    val key = project.objects.fileProperty().convention(dest.map { it.file("privkey.pem") })

    @OutputFile
    val rootCA = project.objects.fileProperty().convention(dest.map { it.file("rootCA.pem") })

    @OutputFile
    val rootCAKey = project.objects.fileProperty().convention(dest.map { it.file("rootCA-key.pem") })

    private val executablePath: String
        get() = this@GenerateCerts.executable.get().asFile.absolutePath

    private fun execute(vararg arguments: String) {
        project.exec {
            commandLine = listOf(executablePath) + arguments
            // Exclude JAVA_HOME as we only want to check the local certificates for the system.
            environment = Jvm.current().getInheritableEnvironmentVariables(System.getenv()).filterKeys {
                !setOf("JAVA_HOME").contains(it)
            }
            // Note this is allowed to fail on some systems the user may have to manually install the local certificate.
            // See the README.
            isIgnoreExitValue = true
        }
    }

    private fun install() {
        execute("-install")
        val rootStore = project.file(project.execCaptureOutput(listOf(executablePath, "-CAROOT"), "Failed to find CAROOT"))
        listOf(rootCA.get().asFile, rootCAKey.get().asFile).forEach {
            rootStore.resolve(it.name).copyTo(it, true)
        }
    }

    @TaskAction
    fun exec() {
        install()
        execute(
            "-cert-file", cert.get().asFile.absolutePath,
            "-key-file", key.get().asFile.absolutePath,
            "*.islandora.dev",
            "islandora.dev",
            "localhost",
            "127.0.0.1",
            "::1",
        )
    }

}
