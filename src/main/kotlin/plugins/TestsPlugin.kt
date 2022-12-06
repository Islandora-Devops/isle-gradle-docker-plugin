package plugins

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import plugins.BuildCtlPlugin.BuildCtlLoadImage
import plugins.IslePlugin.Companion.isDockerProject
import plugins.TestPlugin.DockerComposeUp


// Generate reports via Syft and Grype.
@Suppress("unused")
class TestsPlugin : Plugin<Project> {

    companion object {
        // Check if the project should have docker related tasks.
        val Project.isDockerComposeProject: Boolean
            get() = projectDir.resolve("docker-compose.yml").exists()
    }

    override fun apply(pluginProject: Project): Unit = pluginProject.run {
        allprojects {
            // Auto-apply plugins to relevant projects in the "tests" folder of docker projects.
            if (isDockerProject) {
                subprojects {
                    if (isDockerComposeProject) {
                        apply<TestPlugin>()

                        // Enforce build order for dependencies.
                        tasks.withType<DockerComposeUp> {
                            val services = dockerCompose.services.keys
                            val metadata = project.rootProject.allprojects
                                .filter { it.isDockerProject && services.contains(it.name) }
                                .map { it.tasks.named<BuildCtlLoadImage>("load").flatMap { task -> task.metadata } }
                            metadataFiles.setFrom(metadata)
                        }
                    }
                }
                tasks.register("test") {
                    description = "Test docker image(s)"
                    dependsOn(project.subprojects.mapNotNull { it.tasks.matching { task -> task.name == "test" } })
                }
            }
        }
    }
}