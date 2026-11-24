package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;

final class ServerVersionCheck {
    private static final String MARIADB_MARKER = "mariadb";

    private ServerVersionCheck() {
    }

    static String describe(DatabaseMetaData metadata) throws SQLException {
        return metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion();
    }

    static Optional<String> problem(DatabaseDialect dialect, DatabaseMetaData metadata) throws SQLException {
        String version = metadata.getDatabaseProductVersion();
        boolean mariadbServer = metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains(MARIADB_MARKER)
            || version.toLowerCase(Locale.ROOT).contains(MARIADB_MARKER);
        if (dialect == DatabaseDialect.MYSQL && mariadbServer) {
            return Optional.of("This is a MariaDB server; choose MariaDB");
        }
        if (dialect == DatabaseDialect.MARIADB && !mariadbServer) {
            return Optional.of("This is not a MariaDB server; choose MySQL");
        }
        Minimum minimum = Minimum.of(dialect);
        int major = metadata.getDatabaseMajorVersion();
        int minor = metadata.getDatabaseMinorVersion();
        if (major < minimum.major() || (major == minimum.major() && minor < minimum.minor())) {
            return Optional.of(dialect.displayName() + " " + major + "." + minor + " is older than " + minimum.text());
        }
        return Optional.empty();
    }

    private record Minimum(int major, int minor) {
        static Minimum of(DatabaseDialect dialect) {
            return switch (dialect) {
                case MYSQL -> new Minimum(8, 4);
                case MARIADB -> new Minimum(10, 11);
                case POSTGRESQL -> new Minimum(15, 0);
                case SQLITE -> new Minimum(0, 0);
            };
        }

        String text() {
            return major + "." + minor;
        }
    }
}
