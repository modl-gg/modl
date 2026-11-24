package gg.modl.build.sql

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

@CacheableTask
abstract class PrepareJooqCodegenDatabase : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val migrations: DirectoryProperty

    @get:Input
    abstract val historyTable: Property<String>

    @get:Classpath
    abstract val migratorClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val database: RegularFileProperty

    @get:Inject
    abstract val workers: WorkerExecutor

    init {
        group = "build"
        description = "Migrates an empty SQLite database with Flyway so jOOQ can generate the schema model from it"
    }

    @TaskAction
    fun prepare() {
        val queue = workers.classLoaderIsolation { classpath.from(migratorClasspath) }
        queue.submit(MigrateCodegenDatabase::class.java) {
            migrations.set(this@PrepareJooqCodegenDatabase.migrations)
            historyTable.set(this@PrepareJooqCodegenDatabase.historyTable)
            database.set(this@PrepareJooqCodegenDatabase.database)
        }
    }
}
