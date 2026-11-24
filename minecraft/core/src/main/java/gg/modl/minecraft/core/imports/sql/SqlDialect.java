package gg.modl.minecraft.core.imports.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

public enum SqlDialect {
    HSQLDB("\""),
    H2("\""),
    SQLITE("\""),
    MYSQL("`"),
    POSTGRESQL("\"");

    private final String quote;

    SqlDialect(String quote) {
        this.quote = quote;
    }

    public String quote(String identifier) {
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    public boolean storesUuidNatively() {
        return this == HSQLDB || this == POSTGRESQL;
    }

    static SqlDialect of(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (product.contains("hsql")) return HSQLDB;
        if (product.contains("h2")) return H2;
        if (product.contains("sqlite")) return SQLITE;
        if (product.contains("mysql") || product.contains("mariadb")) return MYSQL;
        if (product.contains("postgres") || product.contains("cockroach")) return POSTGRESQL;
        throw new SQLException("Unsupported database " + product);
    }
}
