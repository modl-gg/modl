package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

abstract class VerifyVersionsEqual : DefaultTask() {
    @get:Input
    abstract val subject: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @get:Input
    abstract val actualVersion: Property<String>

    @get:Input
    abstract val reason: Property<String>

    init {
        group = "verification"
    }

    @TaskAction
    fun verify() {
        if (expectedVersion.get() != actualVersion.get()) {
            throw GradleException(
                "${subject.get()} resolves to ${actualVersion.get()}, but it must equal ${expectedVersion.get()}: ${reason.get()}"
            )
        }
    }
}
