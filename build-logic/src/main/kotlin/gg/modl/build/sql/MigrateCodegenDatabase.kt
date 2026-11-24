package gg.modl.build.sql

import org.flywaydb.core.Flyway
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.nio.file.Files

abstract class MigrateCodegenDatabase : WorkAction<MigrateCodegenDatabase.Parameters> {
    interface Parameters : WorkParameters {
        val migrations: DirectoryProperty
        val historyTable: Property<String>
        val database: RegularFileProperty
    }

    override fun execute() {
        val databaseFile = parameters.database.get().asFile.toPath()
        Files.createDirectories(databaseFile.parent)
        Files.deleteIfExists(databaseFile)
        val result = Flyway.configure(javaClass.classLoader)
            .dataSource("jdbc:sqlite:$databaseFile", null, null)
            .locations("filesystem:" + parameters.migrations.get().asFile.absolutePath)
            .table(parameters.historyTable.get())
            .failOnMissingLocations(true)
            .cleanDisabled(true)
            .load()
            .migrate()
        if (result.migrationsExecuted == 0) {
            throw GradleException("No migration was applied from ${parameters.migrations.get().asFile}")
        }
    }
}
