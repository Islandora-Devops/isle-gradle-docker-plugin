package utils

import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import kotlin.reflect.full.memberProperties

// Helper functions to clean up argument processing for the various argument types.
@Suppress("UnstableApiUsage")
interface DockerCommandOptions {
    // Annotation for serializing command line options to a string.
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.PROPERTY)
    annotation class Option(val option: String)

    fun toList(exclude: List<String> = listOf()): List<String> {
        fun include(option: Option) = !exclude.contains(option.option)

        fun Property<Boolean>.toOption(option: Option) =
            if (get() && include(option)) listOf(option.option)
            else emptyList()

        fun Property<String>.toOption(option: Option) =
            if (isPresent && include(option)) listOf(option.option, get())
            else emptyList()

        fun RegularFileProperty.toOption(option: Option) =
            if (isPresent && include(option)) listOf(option.option, get().asFile.absolutePath)
            else emptyList()

        fun ListProperty<String>.toOption(option: Option) =
            if (include(option)) get().flatMap { listOf(option.option, it) }
            else emptyList()

        fun MapProperty<String, String>.toOption(option: Option) =
            if (include(option)) get().flatMap { listOf(option.option, "${it.key}=${it.value}") }
            else emptyList()

        fun SetProperty<String>.toOption(option: Option) =
            if (include(option)) get().flatMap { listOf(option.option, it) }
            else emptyList()

        @Suppress("UNCHECKED_CAST")
        return javaClass.kotlin.memberProperties.flatMap { member ->
            member.annotations.filterIsInstance<Option>().flatMap { annotation ->
                when (val value = member.get(this)) {
                    is Property<*> -> when (value.orNull) {
                        is Boolean -> (value as Property<Boolean>).toOption(annotation)
                        is String -> (value as Property<String>).toOption(annotation)
                        is RegularFile -> (value as RegularFileProperty).toOption(annotation)
                        null -> emptyList() // Value was not set.
                        else -> throw RuntimeException("Option Property<T> type ${value.orNull?.javaClass} missing implementation.")
                    }
                    is ListProperty<*> -> (value as ListProperty<String>).toOption(annotation)
                    is MapProperty<*, *> -> (value as MapProperty<String, String>).toOption(annotation)
                    is SetProperty<*> -> (value as SetProperty<String>).toOption(annotation)
                    else -> throw RuntimeException("Option type ${value?.javaClass} missing implementation.")
                }
            }
        }
    }
}
