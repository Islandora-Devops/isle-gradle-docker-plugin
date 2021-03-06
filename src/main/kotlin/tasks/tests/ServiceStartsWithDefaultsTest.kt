package tasks.tests

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import tasks.DockerContainer

// Checks that it can bring up the image with default settings.
// Then waits for its services to start, after a set period if no errors occur it will stop the container and check the
// exit code.
@Suppress("UnstableApiUsage")
@CacheableTask
open class ServiceStartsWithDefaultsTest : DockerContainer() {

    // Maximum amount of time to wait for an error after services have successfully started (milliseconds).
    @Input
    val maxWaitForFailure = project.objects.property<Long>().convention(10000)

    @Input
    val waitForMessage = project.objects.property<String>().convention("[services.d] done.")

    @TaskAction
    fun exec() {
        setUp()
        untilOutput { line ->
            if (line.contains(waitForMessage.get())) {
                logger.info("Services have successfully started")
                // Services have started, wait for a fixed interval for the container to exited with an error.
                Thread.sleep(maxWaitForFailure.get())
                false
            } else {
                true
            }
        }
        tearDown()
        checkExitCode(0L) // Check if any of the containers exited non-zero.
    }
}