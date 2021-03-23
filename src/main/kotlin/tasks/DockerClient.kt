package tasks

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.gradle.api.DefaultTask
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.*

abstract class DockerClient : DefaultTask() {

    @get:Internal
    val dockerClient: DockerClient by lazy {
        val configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(configBuilder.dockerHost)
            .sslConfig(configBuilder.sslConfig)
            .build()
        val dockerClient = DockerClientBuilder
            .getInstance()
            .withDockerHttpClient(httpClient)
            .build()
        project.gradle.buildFinished {
            dockerClient.close()
        }
        dockerClient
    }

    private val parents by lazy {
        project.run {
            generateSequence(this) { it.parent }
        }
    }

    @get:Internal
    protected val buildTask by lazy {
        parents.forEach {
            try {
                return@lazy it.tasks.named<DockerBuild>("build")
            } catch (e: UnknownTaskException) {
            }
        }
        return@lazy null
    }

}