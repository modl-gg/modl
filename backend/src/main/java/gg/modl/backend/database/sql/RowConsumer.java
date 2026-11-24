package gg.modl.backend.database.sql;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface RowConsumer {
    void accept(List<Object> values) throws IOException;
}
