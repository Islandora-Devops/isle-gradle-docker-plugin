@file:Suppress("unused")

package tasks.tests

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.TaskAction
import tasks.DockerCompose

@CacheableTask
open class DockerComposeTest : DockerCompose() {

    @TaskAction
    fun exec() {
        setUp()
        up("--abort-on-container-exit") // Wait for exit or timeout.
        tearDown()
        checkExitCodes(0L) // Check if any of the containers exited non-zero.
    }
}
