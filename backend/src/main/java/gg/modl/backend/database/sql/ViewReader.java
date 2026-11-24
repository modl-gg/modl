package gg.modl.backend.database.sql;

import java.io.IOException;

@FunctionalInterface
public interface ViewReader<T> {
    T read(DatabaseView view) throws IOException;
}
