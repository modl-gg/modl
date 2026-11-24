package gg.modl.build.sql

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyMigrationParity : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dialectFolders: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        group = "verification"
        description = "Fails when a migration version is missing from a dialect folder or a migration file is malformed"
    }

    @TaskAction
    fun verify() {
        val folders = dialectFolders.files.sortedBy { it.name }
        val problems = MigrationParity.problems(folders.map { MigrationFolder.read(it) })
        if (problems.isNotEmpty()) {
            throw GradleException("Migration folders are out of parity:\n" + problems.joinToString("\n") { "  $it" })
        }
        report.get().asFile.writeText(folders.joinToString("\n", postfix = "\n") { "${it.name}: in parity" })
    }
}
