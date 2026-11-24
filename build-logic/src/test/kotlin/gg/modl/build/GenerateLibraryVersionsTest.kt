package gg.modl.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GenerateLibraryVersionsTest {
    @Test
    fun aPlainLibraryIsPinnedByItsCoordinatesFileAndChecksum() {
        val declaration = GenerateLibraryVersions.libraryDeclaration(
            "IMPORT_H2_LEGACY", MavenCoordinates("com.h2database", "h2", "1.4.200"), "h2-1.4.200.jar", "c2hh", false,
        )

        assertEquals(
            "    static final LibraryRecord IMPORT_H2_LEGACY = LibraryRecord.of(\"com{}h2database\", \"h2\", \"1.4.200\", " +
                "\"h2-1.4.200.jar\", \"c2hh\");",
            declaration,
        )
    }

    @Test
    fun aClassifiedLibraryNamesItsClassifierBeforeItsRelocations() {
        val declaration = GenerateLibraryVersions.libraryDeclaration(
            "IMPORT_HSQLDB", MavenCoordinates("org.hsqldb", "hsqldb", "2.7.4", "jdk8"), "hsqldb-2.7.4-jdk8.jar", "c2hh", true,
        )

        assertEquals(
            "    static final LibraryRecord IMPORT_HSQLDB = LibraryRecord.of(\"org{}hsqldb\", \"hsqldb\", \"2.7.4\", " +
                "\"hsqldb-2.7.4-jdk8.jar\", \"c2hh\").classifiedAs(\"jdk8\").relocatedBy(RELOCATIONS);",
            declaration,
        )
    }
}
