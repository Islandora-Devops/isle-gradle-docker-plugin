package tasks

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.command.CreateContainerCmd
import com.github.dockerjava.api.command.InspectContainerResponse
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.exception.NotModifiedException
import com.github.dockerjava.api.model.Frame
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.property
import java.time.Duration.ofMinutes
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

@Suppress("UnstableApiUsage", "MemberVisibilityCanBePrivate")
@CacheableTask
abstract class DockerContainer : DockerClient() {

    // Not marked as input as the tag can change but the image contents may be the same and we do not need to rerun.
    @Internal
    val imageId = project.objects.property<String>()

    // Not actually the image digest but rather an approximation that ignores timestamps, etc.
    // So we do not run test unless the image has actually changed.
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val digest = project.objects.fileProperty()

    // Capture the log output of the command for later inspection.
    @OutputFile
    val log = project.objects.fileProperty().convention(project.layout.buildDirectory.file("$name.log"))

    // Identifier of the container started to run this task.
    @Suppress("ANNOTATION_TARGETS_NON_EXISTENT_ACCESSOR")
    @get:Internal
    val containerId = project.objects.property<String>()

    @Internal
    val info = project.objects.property<InspectContainerResponse>()

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

        // Ensure we do not leave container running if something goes wrong.
        project.gradle.buildFinished {
            // May be called before creation of container if build is cancelled etc.
            if (containerId.isPresent) {
                remove(true)
            }
        }
    }

    // To be able to update the log and view after completion we need it on a separate thread.
    @get:Internal
    val loggingThread by lazy {
        thread(start = false) {
            log.get().asFile.bufferedWriter().use { writer ->
                dockerClient.logContainerCmd(containerId.get())
                    .withFollowStream(true)
                    .withStdOut(true)
                    .withStdErr(true)
                    .exec(object : ResultCallback.Adapter<Frame>() {
                        override fun onNext(frame: Frame) {
                            val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                            val payload = String(frame.payload).trim { it <= ' ' }
                            val line = "[$timestamp] ${frame.streamType}: $payload"
                            logger.info(line)
                            writer.write("$line\n")
                        }
                    }).awaitCompletion()
            }
        }
    }

    fun create() {
        if (!containerId.isPresent) {
            containerId.set(dockerClient.createContainerCmd(imageId.get()).exec().id)
        }
    }

    fun create(action: CreateContainerCmd.() -> CreateContainerCmd) {
        if (!containerId.isPresent) {
            dockerClient.createContainerCmd(imageId.get()).let {
                containerId.set(action(it).exec().id)
            }
        }
    }

    fun start() {
        try {
            dockerClient.startContainerCmd(containerId.get()).exec()
        } catch (e: NotModifiedException) {
            // Ignore if container has already started.
        }
        loggingThread.start()
    }

    fun stop() {
        try {
            dockerClient.stopContainerCmd(containerId.get()).exec()
        } catch (e: NotModifiedException) {
            // Ignore if not modified, as it has already been stopped.
        } catch (e: Exception) {
            // Unrecoverable error, user will have to clean up their environment.
            throw e
        }
        loggingThread.join() // Container has stopped finish logging.
    }

    fun wait() =
        dockerClient.waitContainerCmd(containerId.get()).exec(ResultCallback.Adapter())?.awaitCompletion()

    fun inspect(): InspectContainerResponse = dockerClient.inspectContainerCmd(containerId.get()).exec()

    fun remove(force: Boolean = false) {
        try {
            dockerClient
                .removeContainerCmd(containerId.get())
                .withForce(force)
                .exec()
        } catch (e: NotFoundException) {
            // Ignore if not found, as it has already been removed.
        } catch (e: Exception) {
            // Unrecoverable error, user will have to clean up their environment.
            throw e
        }
    }

    // Executes callback for each line of log output until the stream ends or the callback returns false.
    fun untilOutput(callback: (String) -> Boolean) {
        dockerClient.logContainerCmd(containerId.get())
            .withTailAll()
            .withFollowStream(true)
            .withStdOut(true)
            .withStdErr(true)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(frame: Frame) {
                    val line = String(frame.payload)
                    if (!callback(line)) {
                        close()
                    }
                    super.onNext(frame)
                }
            })?.awaitCompletion()
    }

    fun setUp() {
        create()
        start()
    }

    fun setUp(action: CreateContainerCmd.() -> CreateContainerCmd) {
        create(action)
        start()
    }

    fun tearDown() {
        stop()
        info.set(inspect())
        remove(true)
    }

    fun checkExitCode(expected: Long) {
        val name = info.get().name
        val state = info.get().state
        if (state.exitCodeLong != expected) {
            throw RuntimeException("Container Image: '${imageId.get()}' Name: '$name' ID: '${containerId.get()}' exited with ${state.exitCodeLong} and status ${state.status}.")
        }
    }
}
