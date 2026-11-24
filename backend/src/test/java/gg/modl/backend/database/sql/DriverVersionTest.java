package gg.modl.backend.database.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteJDBCLoader;

class DriverVersionTest {
    private static final Path VERSION_CATALOG = Path.of("..", "gradle", "libs.versions.toml");
    private static final Pattern SQLITE_JDBC_PIN = Pattern.compile("(?m)^sqlite-jdbc\\s*=\\s*\"([^\"]+)\"");

    @Test
    void theLoadedSqliteDriverIsTheCatalogPin() throws IOException {
        assertEquals(catalogPin(), SQLiteJDBCLoader.getVersion());
    }

    private static String catalogPin() throws IOException {
        Matcher pin = SQLITE_JDBC_PIN.matcher(Files.readString(VERSION_CATALOG));
        if (!pin.find()) {
            throw new IllegalStateException("No sqlite-jdbc version in " + VERSION_CATALOG.toAbsolutePath());
        }
        return pin.group(1);
    }
}
