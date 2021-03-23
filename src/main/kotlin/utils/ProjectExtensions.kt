package utils

import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.provideDelegate

// Computes a set of image tags for the given repository.
fun Project.imageTags(repository: String): Set<String> {
    val tags: Set<String> by project.rootProject.extra
    return tags.map { "$repository/$name:$it" }.toSet()
}

// Check if the project should have docker related tasks.
val Project.isDockerProject: Boolean
    get() = projectDir.resolve("Dockerfile").exists()
