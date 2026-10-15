package io.artio.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyModuleBoundaries : DefaultTask() {
    @get:Input
    abstract val violations: ListProperty<String>

    @TaskAction
    fun verify() {
        val found = violations.get().distinct().sorted()
        if (found.isNotEmpty()) {
            throw GradleException(
                "Artio domain module boundary violations:\n" + found.joinToString("\n") { " - $it" },
            )
        }
        logger.lifecycle("Verified dependency boundaries for Artio domain modules.")
    }
}
