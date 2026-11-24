package gg.modl.build.sql

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationParityTest {
    private val baseline = "CREATE TABLE players (id VARCHAR(36) NOT NULL PRIMARY KEY);\n"

    @Test
    fun foldersWithTheSameVersionsAreInParity() {
        val folders = listOf("sqlite", "mysql", "postgresql").map { folder(it, "V1__baseline.sql" to baseline, "V2__players_index.sql" to baseline) }

        assertTrue(MigrationParity.problems(folders).isEmpty())
    }

    @Test
    fun aVersionMissingFromOneDialectIsReported() {
        val folders = listOf(
            folder("sqlite", "V1__baseline.sql" to baseline, "V2__players_index.sql" to baseline),
            folder("mysql", "V1__baseline.sql" to baseline),
        )

        assertEquals(listOf("mysql: V2__players_index.sql is missing"), MigrationParity.problems(folders))
    }

    @Test
    fun commentsAndEmDashesAreReported() {
        val folders = listOf(folder("sqlite", "V1__baseline.sql" to "-- players\n# players\n/* players */\nCREATE TABLE a\u2014b (id INTEGER);\n"))

        assertEquals(
            listOf(
                "sqlite/V1__baseline.sql:1: migrations carry no comments",
                "sqlite/V1__baseline.sql:2: migrations carry no comments",
                "sqlite/V1__baseline.sql:3: migrations carry no comments",
                "sqlite/V1__baseline.sql:4: migrations carry no em-dashes",
            ),
            MigrationParity.problems(folders),
        )
    }

    @Test
    fun malformedNamesDuplicateVersionsAndEmptyFoldersAreReported() {
        val folders = listOf(
            folder("sqlite", "V1__baseline.sql" to baseline, "V1__again.sql" to baseline, "v3-Bad.sql" to baseline),
            folder("postgresql"),
        )

        val problems = MigrationParity.problems(folders)

        assertTrue("sqlite/v3-Bad.sql: the name must match V<version>__<lower_snake_description>.sql" in problems)
        assertTrue("sqlite: version 1 is defined more than once" in problems)
        assertTrue("postgresql: holds no migration" in problems)
    }

    private fun folder(dialect: String, vararg files: Pair<String, String>) = MigrationFolder(dialect, files.toMap())
}
