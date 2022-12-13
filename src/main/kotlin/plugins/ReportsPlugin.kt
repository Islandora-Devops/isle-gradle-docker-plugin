package plugins

import org.apache.commons.io.output.NullOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import plugins.IslePlugin.Companion.execCaptureOutput
import plugins.IslePlugin.Companion.isDockerProject
import tasks.DockerPull

// Generate reports via Syft and Grype.
@Suppress("unused")
class ReportsPlugin : Plugin<Project> {

    companion object {
        // Configuration for grype.
        val Project.grypeConfig: RegularFile?
            get() = (properties.getOrDefault("isle.grype.config", "") as String).let { path: String ->
                if (path.isNotBlank()) {
                    project.layout.projectDirectory.file(path).let { file ->
                        if (file.asFile.exists())
                            file
                        else
                            null
                    }
                }
                null
            }

        // Only reports issues that have fixes.
        val Project.grypeOnlyFixed: Boolean
            get() = (properties.getOrDefault("isle.grype.only-fixed", "false") as String).toBoolean()

        // Triggers build to fail if security vulnerability is discovered.
        // If unspecified the build will continue regardless.
        // Possible values: negligible, low, medium, high, critical.
        // Only reports issues that have fixes.
        val Project.grypeFailOnSeverity: String
            get() = properties.getOrDefault("isle.grype.fail-on-severity", "") as String

        // The format of reports generated by grype.
        // Possible values: table, cyclonedx, json, template.
        val Project.grypeFormat: String
            get() = properties.getOrDefault("isle.grype.format", "table") as String
    }

    // Updates Grype Database.
    @CacheableTask
    open class UpdateGrypeDB : DefaultTask() {
        @Input
        val image = project.objects.property<String>()

        @OutputDirectory
        val database = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("grype"))

        @Internal
        val uid = project.objects.property<Int>()

        @Internal
        val gid = project.objects.property<Int>()

        private val baseArguments: List<String>
            get() = listOf(
                "docker",
                "run",
                "--rm",
                "-u", "${uid.get()}:${gid.get()}",
                "-e", "GRYPE_DB_CACHE_DIR=/cache",
                "-v", "${database.get().asFile.absolutePath}:/cache",
                "-v", "/tmp:/tmp",
                image.get(),
            )

        private fun upToDate() = project.exec {
            commandLine(
                baseArguments + listOf(
                    "db",
                    "status"
                )
            )
            standardOutput = NullOutputStream()
            errorOutput = NullOutputStream()
            isIgnoreExitValue = true
        }.exitValue == 0

        init {
            outputs.upToDateWhen {
                // If the database is missing the task will re-run or be restored from cache.
                // If the database is present check to make sure it is up-to-date, if not run again.
                !database.get().asFile.exists() || upToDate()
            }
            uid.set(project.execCaptureOutput(listOf("id", "-u"), "Failed to get UID").toInt())
            gid.set(project.execCaptureOutput(listOf("id", "-g"), "Failed to get GID").toInt())
        }

        @TaskAction
        fun pull() {
            project.exec {
                commandLine(
                    baseArguments + listOf(
                        "db",
                        "update"
                    )
                )
            }
        }
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {

        tasks.register<DockerPull>("pullSyft") {
            group = "Isle Reports"
            description = "Pull anchore/syft docker image"
            image.set("anchore/syft")
        }

        val pullGrype by tasks.registering(DockerPull::class) {
            group = "Isle Reports"
            description = "Pull anchore/grype docker image"
            image.set("anchore/grype")
        }

        tasks.register<UpdateGrypeDB>("updateGrypeDB") {
            group = "Isle Reports"
            description = "Update the Grype Database"
            image.set(pullGrype.map { it.digestFile.get().asFile.readText().trim() })
        }

        allprojects {
            // Auto-apply plugins to relevant projects.
            if (isDockerProject) {
                apply<ReportPlugin>()
            }
        }
    }
}