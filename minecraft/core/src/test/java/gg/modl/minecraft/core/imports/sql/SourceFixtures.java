package gg.modl.minecraft.core.imports.sql;

import gg.modl.minecraft.api.LibraryRecord;
import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.support.RecordingPluginLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class SourceFixtures {
    public static final long NOW = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
    public static final long HOUR = TimeUnit.HOURS.toMillis(1);
    public static final long DAY = TimeUnit.DAYS.toMillis(1);
    public static final String ALICE = "11111111-1111-4111-8111-111111111111";
    public static final String BOB = "22222222-2222-4222-8222-222222222222";
    public static final String MOD = "33333333-3333-4333-8333-333333333333";

    private SourceFixtures() {
    }

    public static SourceContext context(Path root, Predicate<String> enabled) {
        return context(root, enabled, true);
    }

    public static SourceContext context(Path root, Predicate<String> enabled, boolean offlineUuids) {
        return SourceContext.builder()
                .pluginsFolder(root.resolve("plugins"))
                .serverRoot(root)
                .scratch(root.resolve("plugins").resolve("modl").resolve("import").resolve("copies"))
                .connections(connections(root))
                .pluginEnabled(enabled)
                .offlineUuids(() -> offlineUuids)
                .defaultReason(() -> "No reason")
                .clock(() -> NOW)
                .logger(new RecordingPluginLogger())
                .build();
    }

    public static SourceContext context(Path root) {
        return context(root, name -> false);
    }

    public static JdbcConnections connections(Path root) {
        return new JdbcConnections(new TestClassPathDrivers(), root.resolve("plugins").resolve("modl").resolve("import")
                .resolve("native"));
    }

    public static Connection connect(JdbcDriver driver, String url, String user) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", "");
        return connections(Paths.get(System.getProperty("java.io.tmpdir"))).connect(driver, url, properties);
    }

    public static void runScript(Connection connection, String resource) throws SQLException, IOException {
        String script;
        try (InputStream input = SourceFixtures.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            script = new String(readAll(input), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            for (String part : script.split(";\\s*\\n")) {
                if (!part.trim().isEmpty()) statement.execute(part.trim());
            }
        }
    }

    public static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, SourcePlayer> players(SourceReader reader) throws Exception {
        Map<String, SourcePlayer> players = new LinkedHashMap<>();
        PlayerRecords records = reader.players();
        for (SourcePlayer player = records.next(); player != null; player = records.next()) {
            players.put(player.getUuid(), player);
        }
        return players;
    }

    public static SourcePunishment punishment(SourcePlayer player, String id) {
        for (SourcePunishment punishment : player.getPunishments()) {
            if (punishment.getId().equals(id)) return punishment;
        }
        throw new AssertionError("No punishment " + id + " in " + player.getPunishments());
    }

    public static void assertMinutes(long expectedMillis, long actualMillis) {
        assertEquals(TimeUnit.MILLISECONDS.toMinutes(expectedMillis), TimeUnit.MILLISECONDS.toMinutes(actualMillis));
    }

    private static final class TestClassPathDrivers implements DriverLibraries {
        @Override
        public ClassLoader isolatedLoader(String loaderId, LibraryRecord library) {
            return SourceFixtures.class.getClassLoader();
        }

        @Override
        public void release(String loaderId) {
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) output.write(buffer, 0, read);
        return output.toByteArray();
    }
}
