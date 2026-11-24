package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.DatabaseDialect;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SqlDatabasePropertiesTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://modl:secret@db.example.com:3306/modl",
        "jdbc:mariadb://modl@db.example.com/modl",
        "jdbc:postgresql://modl:secret@db.example.com/modl",
        "jdbc:mysql://modl:secret@db1.example.com:3306,db2.example.com:3306/modl",
        "jdbc:postgresql://db.example.com/modl?user=modl",
        "jdbc:postgresql://db.example.com/modl?password=secret",
        "jdbc:postgresql://db.example.com/modl?ssl=true&password=secret",
        "jdbc:mysql://db.example.com/modl?useSSL=true&User=modl",
        "jdbc:mariadb://db.example.com/modl?PASSWORD=secret",
        "jdbc:mysql://db.example.com/modl;password=secret",
        "jdbc:mariadb://db.example.com/modl?sslMode=verify-full;USER=modl"
    })
    void aUrlCarryingCredentialsIsRejected(String url) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> withUrl(url));

        assertTrue(failure.getMessage().contains("MODL_DATABASE_USERNAME"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://db.example.com/modl?useAffectedRows=true",
        "jdbc:mysql://db.example.com/modl?useSSL=true&USEAFFECTEDROWS=TRUE",
        "jdbc:mariadb://db.example.com/modl?useAffectedRows=1",
        "jdbc:mariadb://db.example.com/modl?useAffectedRows=falsetto"
    })
    void aUrlThatCountsOnlyChangedRowsIsRejected(String url) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> withUrl(url));

        assertTrue(failure.getMessage().contains("useAffectedRows"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://db.example.com/modl?useAffectedRows=false",
        "jdbc:mariadb://db.example.com/modl?useAffectedRows=false&sslMode=trust"
    })
    void aUrlThatStatesTheDefaultIsAccepted(String url) {
        assertEquals(Optional.of(url), withUrl(url).externalUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:sqlite:/data/modl.db",
        "jdbc:sqlserver://db.example.com;databaseName=modl",
        "jdbc:db2://db.example.com/modl",
        "mysql://db.example.com/modl",
        "postgresql://db.example.com/modl"
    })
    void anUnknownSchemeIsRejected(String url) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> withUrl(url));

        assertTrue(failure.getMessage().contains("jdbc:postgresql://"), failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:mysql://db.example.com/modl, MYSQL",
        "jdbc:mariadb://db.example.com/modl, MARIADB",
        "jdbc:postgresql://db.example.com/modl, POSTGRESQL",
        "JDBC:MYSQL://db.example.com/modl, MYSQL",
        "jdbc:postgresql://db.example.com/modl?sslmode=verify-full&sslrootcert=/etc/ca.pem, POSTGRESQL",
        "jdbc:mariadb://db.example.com/modl?sslMode=verify-full, MARIADB"
    })
    void theDialectFollowsTheUrlScheme(String url, DatabaseDialect expected) {
        SqlDatabaseProperties database = withUrl(url);

        assertEquals(expected, database.dialect());
        assertEquals(Optional.of(url), database.externalUrl());
    }

    @Test
    void noUrlSelectsSqlite() {
        SqlDatabaseProperties database = new SqlDatabaseProperties(null, null, null, null, null);

        assertEquals(DatabaseDialect.SQLITE, database.dialect());
        assertEquals(Optional.empty(), database.externalUrl());
    }

    @Test
    void blankValuesBecomeNull() {
        SqlDatabaseProperties database = new SqlDatabaseProperties("  ", " ", "\t", null, new SqlDatabaseProperties.Sqlite(" "));

        assertNull(database.url());
        assertNull(database.username());
        assertNull(database.password());
        assertNull(database.sqlite().nativeDir());
        assertEquals(DatabaseDialect.SQLITE, database.dialect());
    }

    @Test
    void valuesAreTrimmed() {
        SqlDatabaseProperties database = new SqlDatabaseProperties(" jdbc:mysql://db.example.com/modl ", " modl ", " secret ", null,
            new SqlDatabaseProperties.Sqlite(" /var/lib/modl/native "));

        assertEquals("jdbc:mysql://db.example.com/modl", database.url());
        assertEquals("modl", database.username());
        assertEquals("secret", database.password());
        assertEquals(Optional.of("/var/lib/modl/native"), database.sqlite().configuredNativeDir());
    }

    @Test
    void aMissingSqliteSectionHasNoNativeDirectory() {
        assertEquals(Optional.empty(), new SqlDatabaseProperties(null, null, null, null, null).sqlite().configuredNativeDir());
    }

    @Test
    void thePoolSizeDefaultsToTen() {
        assertEquals(SqlDatabaseProperties.DEFAULT_POOL_SIZE, new SqlDatabaseProperties(null, null, null, null, null).poolSize());
        assertEquals(10, SqlDatabaseProperties.DEFAULT_POOL_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, SqlDatabaseProperties.MAX_POOL_SIZE})
    void aPoolSizeWithinTheBoundsIsKept(int poolSize) {
        assertEquals(poolSize, new SqlDatabaseProperties(null, null, null, poolSize, null).poolSize());
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, SqlDatabaseProperties.MAX_POOL_SIZE + 1})
    void aPoolSizeOutsideTheBoundsIsRejected(int poolSize) {
        assertThrows(IllegalArgumentException.class, () -> new SqlDatabaseProperties(null, null, null, poolSize, null));
    }

    private static SqlDatabaseProperties withUrl(String url) {
        return new SqlDatabaseProperties(url, "modl", "secret", null, null);
    }
}
