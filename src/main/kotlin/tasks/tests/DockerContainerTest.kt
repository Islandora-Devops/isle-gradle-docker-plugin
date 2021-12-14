package tasks.tests

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.TaskAction
import tasks.DockerContainer

@CacheableTask
open class DockerContainerTest : DockerContainer() {

    @TaskAction
    fun exec() {
        setUp()
        wait() // Wait for exit or timeout.
        tearDown()
        checkExitCode(0L) // Check if any of the containers exited non-zero.
    }
}
