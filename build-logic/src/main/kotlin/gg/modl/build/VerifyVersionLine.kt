package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyVersionLine : DefaultTask() {
    @get:Input
    abstract val catalogVersion: Property<String>

    @get:Input
    abstract val pinnedVersion: Property<String>

    @get:Input
    abstract val requiredLine: Property<String>

    @get:Input
    abstract val reason: Property<String>

    init {
        group = "verification"
    }

    @TaskAction
    fun verify() {
        val pinned = pinnedVersion.get()
        val line = requiredLine.get()
        if (!pinned.startsWith(line)) {
            throw GradleException(
                "gradle/libs.versions.toml pins ${catalogVersion.get()} = \"$pinned\", but it must start with " +
                    "\"$line\": ${reason.get()}"
            )
        }
    }
}
