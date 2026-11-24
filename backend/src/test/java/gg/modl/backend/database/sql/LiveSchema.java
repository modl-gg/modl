package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

record LiveSchema(Map<String, TableShape> tables, Map<String, String> collations, Set<String> withoutRowidTables) {
    static LiveSchema read(Connection connection, DatabaseDialect dialect) throws SQLException {
        return dialect == DatabaseDialect.SQLITE
            ? SqlitePragmaSchema.read(connection)
            : JdbcMetadataSchema.read(connection, dialect);
    }

    static String columnKey(String table, String column) {
        return table + "." + column;
    }
}
