package gg.modl.backend.database.sql;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class RestoreConversionMemoryTest {
    private static final int FILE_MEBIBYTES = 1024;
    private static final long ALLOWED_GROWTH_KIB = 200 * 1024;
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(10);

    @TempDir
    Path directory;

    @Test
    @EnabledOnOs(OS.LINUX)
    void convertingAOneGibibyteFileDoesNotGrowResidentMemoryWithTheFile() throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(TestDialects.SQLITE, directory.resolve("instance"))) {
            Path file = directory.resolve("full.db");
            SQLiteDataSource source = new SQLiteDataSource();
            source.setUrl("jdbc:sqlite:" + file);
            try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA auto_vacuum = NONE");
            }
            BulkPayload.fill(source, FILE_MEBIBYTES);
            SqliteFile extracted = MaintenanceTestAccess.of(database).sqliteFile(file);
            AtomicBoolean converting = new AtomicBoolean(true);
            AtomicLong peak = new AtomicLong();
            long before = residentKibibytes();
            Thread sampler = Thread.ofPlatform().start(() -> sample(converting, peak));
            SqliteFile converted;
            try {
                converted = extracted.convertedToIncrementalAutoVacuum(directory.resolve("converted.db"));
            } finally {
                converting.set(false);
                sampler.join();
            }

            assertThat(converted.usesIncrementalAutoVacuum()).isTrue();
            assertThat(peak.get() - before).isLessThan(ALLOWED_GROWTH_KIB);
        }
    }

    private static void sample(AtomicBoolean converting, AtomicLong peak) {
        while (converting.get()) {
            try {
                peak.accumulateAndGet(residentKibibytes(), Math::max);
                Thread.sleep(SAMPLE_INTERVAL);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long residentKibibytes() throws IOException {
        return Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("VmRSS:"))
            .mapToLong(line -> Long.parseLong(line.replaceAll("\\D", "")))
            .findFirst()
            .orElseThrow();
    }
}
