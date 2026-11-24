package gg.modl.backend.database.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import org.jspecify.annotations.Nullable;
import javax.sql.DataSource;

final class SqliteDataSources implements ConnectionPools {
    static final int WRITER_POOL_SIZE = 1;
    static final int READER_POOL_SIZE = 3;

    private final HikariDataSource writer;
    private final HikariDataSource reader;

    private SqliteDataSources(HikariDataSource writer, HikariDataSource reader) {
        this.writer = writer;
        this.reader = reader;
    }

    static SqliteDataSources open(SqliteConnections connections, ConnectionTracker tracker, @Nullable MetricsTrackerFactory metrics) {
        HikariDataSource writer = new HikariDataSource(HikariPools.config("modl-sqlite-writer",
            tracker.track(connections.writer(), ConnectionRole.POOL), WRITER_POOL_SIZE, HikariPools.NO_MAX_LIFETIME, metrics));
        try {
            HikariConfig readerConfig = HikariPools.config("modl-sqlite-reader",
                tracker.track(connections.reader(), ConnectionRole.POOL), READER_POOL_SIZE, HikariPools.NO_MAX_LIFETIME, metrics);
            readerConfig.setConnectionInitSql(SqliteConnections.QUERY_ONLY);
            return new SqliteDataSources(writer, new HikariDataSource(readerConfig));
        } catch (RuntimeException e) {
            writer.close();
            throw e;
        }
    }

    @Override
    public DataSource writer() {
        return writer;
    }

    @Override
    public DataSource reader() {
        return reader;
    }

    @Override
    public boolean closeIfIdle() {
        boolean readerClosed = HikariPools.closeIfIdle(reader);
        boolean writerClosed = HikariPools.closeIfIdle(writer);
        return readerClosed && writerClosed;
    }
}
