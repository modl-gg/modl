package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Locale

@CacheableTask
abstract class VerifyArchiveSize : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val maximumBytes: Property<Long>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = archive.get().asFile
        val size = file.length()
        val budget = maximumBytes.get()
        if (size > budget) {
            throw GradleException(
                "${file.name} is ${megabytes(size)}, over its ${megabytes(budget)} budget. Keep runtime-downloadable " +
                    "libraries out of the jar, or raise the budget deliberately in the build script."
            )
        }
        report.get().asFile.writeText("${file.name}: ${megabytes(size)} of ${megabytes(budget)} ($size of $budget bytes)\n")
    }

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.2f MB", bytes / BYTES_PER_MEGABYTE)

    private companion object {
        const val BYTES_PER_MEGABYTE = 1_000_000.0
    }
}
