package gg.modl.backend.database.sql;

import java.nio.file.Path;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

final class SqliteConnections {
    public static final String DATABASE_FILE_NAME = "modl.db";
    public static final int BUSY_TIMEOUT_MILLIS = 10_000;
    public static final int JOURNAL_SIZE_LIMIT_BYTES = 64 * 1024 * 1024;
    public static final String QUERY_ONLY = "PRAGMA query_only = ON";

    private static final int AUTOMATIC_CHECKPOINT_OFF = 0;
    private static final int WRITER_CACHE_KIB = -16_000;
    private static final int READER_CACHE_KIB = -8_000;
    private static final String URL_PREFIX = "jdbc:sqlite:";

    private final Path databaseFile;
    private final Path temporaryDirectory;

    SqliteConnections(Path databaseFile, Path temporaryDirectory) {
        this.databaseFile = databaseFile;
        this.temporaryDirectory = temporaryDirectory;
    }

    Path databaseFile() {
        return databaseFile;
    }

    Path databaseDirectory() {
        Path directory = databaseFile.toAbsolutePath().getParent();
        if (directory == null) {
            throw new IllegalStateException("The database file " + databaseFile + " has no parent directory");
        }
        return directory;
    }

    Path temporaryDirectory() {
        return temporaryDirectory;
    }

    SQLiteDataSource bootstrap() {
        return dataSource(new SQLiteConfig());
    }

    SQLiteDataSource migration() {
        SQLiteConfig config = durable();
        config.enforceForeignKeys(false);
        return dataSource(config);
    }

    SQLiteDataSource writer() {
        SQLiteConfig config = pooled();
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        config.setCacheSize(WRITER_CACHE_KIB);
        return dataSource(config);
    }

    SQLiteDataSource reader() {
        SQLiteConfig config = pooled();
        config.setCacheSize(READER_CACHE_KIB);
        return dataSource(config);
    }

    SQLiteDataSource snapshot() {
        SQLiteConfig config = durable();
        config.enforceForeignKeys(true);
        config.setTempStore(SQLiteConfig.TempStore.FILE);
        config.setTempStoreDirectory(temporaryDirectory.toString());
        config.setCacheSize(READER_CACHE_KIB);
        return dataSource(config);
    }

    SQLiteDataSource inspection() {
        return dataSource(durable());
    }

    SQLiteDataSource checkpoint() {
        return dataSource(durable());
    }

    private static SQLiteConfig pooled() {
        SQLiteConfig config = durable();
        config.enforceForeignKeys(true);
        config.setWalAutocheckpoint(AUTOMATIC_CHECKPOINT_OFF);
        config.setTempStore(SQLiteConfig.TempStore.MEMORY);
        config.setJournalSizeLimit(JOURNAL_SIZE_LIMIT_BYTES);
        return config;
    }

    private static SQLiteConfig durable() {
        SQLiteConfig config = new SQLiteConfig();
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        return config;
    }

    private SQLiteDataSource dataSource(SQLiteConfig config) {
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl(URL_PREFIX + databaseFile);
        return dataSource;
    }
}
