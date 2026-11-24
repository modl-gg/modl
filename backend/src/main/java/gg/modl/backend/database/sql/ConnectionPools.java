package gg.modl.backend.database.sql;

import javax.sql.DataSource;

interface ConnectionPools extends AutoCloseable {
    DataSource writer();

    DataSource reader();

    boolean closeIfIdle();

    @Override
    default void close() {
        closeIfIdle();
    }
}
