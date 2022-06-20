package tasks;

import org.apache.commons.io.FileUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.*
import org.gradle.internal.hash.Hashing
import org.gradle.kotlin.dsl.property
import java.net.URI

@CacheableTask
open class Download : DefaultTask() {
    @Input
    val url = project.objects.property<String>()

    @Input
    val sha256 = project.objects.property<String>()

    @OutputFile
    val dest = project.objects.fileProperty().convention(url.flatMap {
        val uri = URI(it)
        val segments = uri.path.split("/").toTypedArray()
        val filename = segments[segments.size - 1]
        project.layout.buildDirectory.file("downloads/$filename")
    })

    @TaskAction
    fun exec() {
        val uri = URI(url.get())
        dest.get().asFile.delete()
        FileUtils.copyURLToFile(uri.toURL(), dest.get().asFile)
        val calculated = Hashing.sha256().hashFile(dest.get().asFile).toString()
        if (sha256.get() != calculated)
            throw GradleException("Checksum does not match. Expected: ${sha256.get()}, Calculated: $calculated")
    }
}
