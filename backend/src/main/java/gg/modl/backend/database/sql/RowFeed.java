package gg.modl.backend.database.sql;

import java.io.IOException;

@FunctionalInterface
public interface RowFeed {
    void feed(SchemaTable table, RowConsumer rows) throws IOException;
}
