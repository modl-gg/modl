package gg.modl.backend.support.sql;

import gg.modl.backend.database.DatabaseDialect;
import org.jspecify.annotations.Nullable;

public record DialectTarget(String name, DatabaseDialect dialect, @Nullable String image) {
    public boolean isSqlite() {
        return dialect == DatabaseDialect.SQLITE;
    }

    @Override
    public String toString() {
        return name;
    }
}
