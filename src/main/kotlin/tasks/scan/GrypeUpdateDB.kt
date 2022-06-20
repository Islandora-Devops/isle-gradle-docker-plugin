package tasks.scan

import com.github.dockerjava.api.model.*
import org.gradle.api.tasks.*
import tasks.DockerContainer

// Downloads and updates the Grype Database.
// https://github.com/anchore/grype
open class GrypeUpdateDB : DockerContainer() {

    init {
        // Always fetch the latest database.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun exec() {
        dockerClient.createVolumeCmd().withName("grype").exec()
        setUp {
            withEnv("GRYPE_DB_CACHE_DIR=/cache")
            withHostConfig(
                HostConfig()
                    .withBinds(Bind("grype", Volume("/cache")))
            )
            withCmd(
                listOf(
                    "db",
                    "update",
                )
            )
        }
        wait() // Wait for exit or timeout.
        tearDown()
        checkExitCode(0L) // Check if any of the containers exited non-zero.
    }
}
