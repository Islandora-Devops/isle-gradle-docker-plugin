package tasks.tests

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.TaskAction
import tasks.DockerContainer
import java.time.Duration.ofMinutes

@CacheableTask
open class DockerContainerTest : DockerContainer() {

    init {
        // Rerun test if any of the files in the directory changes, as likely they are
        // bind mounted or secrets, etc. The could affect the outcome of the test.
        inputs.dir(project.projectDir)

        // By default limit max execution time to a minute.
        timeout.convention(ofMinutes(5))

        // If there is a parent project with a build task assume that docker image is the one we want to use unless
        // specified otherwise.
        buildTask?.let {
            val task = it.get()
            imageId.convention(task.options.tags.get().first())
            digest.convention(task.digest)
        }
    }

    @TaskAction
    open fun exec() {
        setUp()
        wait() // Wait for exit or timeout.
        tearDown()
        checkExitCode(0L) // Check if any of the containers exited non-zero.
    }
}
