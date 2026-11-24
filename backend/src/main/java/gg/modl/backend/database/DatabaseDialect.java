package gg.modl.backend.database;

import java.util.Arrays;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.jooq.SQLDialect;

public enum DatabaseDialect {
    SQLITE("SQLite", SQLDialect.SQLITE, "sqlite", null, "org.sqlite.JDBC", 1, 1),
    MYSQL("MySQL", SQLDialect.MYSQL, "mysql", "jdbc:mysql://", "com.mysql.cj.jdbc.Driver", 3, 2),
    MARIADB("MariaDB", SQLDialect.MARIADB, "mysql", "jdbc:mariadb://", "org.mariadb.jdbc.Driver", 3, 2),
    POSTGRESQL("PostgreSQL", SQLDialect.POSTGRES, "postgresql", "jdbc:postgresql://", "org.postgresql.Driver", 3, 2);

    private static final String MIGRATION_ROOT = "db/migration/";

    private final String displayName;
    private final SQLDialect sqlDialect;
    private final String migrationFolder;
    @Nullable
    private final String urlPrefix;
    private final String driverClassName;
    private final int reportLanePermits;
    private final int searchLanePermits;

    DatabaseDialect(String displayName, SQLDialect sqlDialect, String migrationFolder, @Nullable String urlPrefix,
                    String driverClassName, int reportLanePermits, int searchLanePermits) {
        this.displayName = displayName;
        this.sqlDialect = sqlDialect;
        this.migrationFolder = migrationFolder;
        this.urlPrefix = urlPrefix;
        this.driverClassName = driverClassName;
        this.reportLanePermits = reportLanePermits;
        this.searchLanePermits = searchLanePermits;
    }

    public static DatabaseDialect ofUrl(@Nullable String url) {
        if (url == null) {
            return SQLITE;
        }
        String lowerCase = url.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(dialect -> dialect.urlPrefix != null && lowerCase.startsWith(dialect.urlPrefix))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("MODL_DATABASE_URL must start with jdbc:mysql://, "
                + "jdbc:mariadb:// or jdbc:postgresql://; leave it empty to use the built-in SQLite database"));
    }

    public String displayName() {
        return displayName;
    }

    public String urlPrefix() {
        if (urlPrefix == null) {
            throw new IllegalStateException(displayName + " has no JDBC URL");
        }
        return urlPrefix;
    }

    public SQLDialect sqlDialect() {
        return sqlDialect;
    }

    public String migrationLocation() {
        return MIGRATION_ROOT + migrationFolder;
    }

    public String migrationFolder() {
        return migrationFolder;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public int reportLanePermits() {
        return reportLanePermits;
    }

    public int searchLanePermits() {
        return searchLanePermits;
    }

    public boolean isExternal() {
        return this != SQLITE;
    }

    public boolean isMysqlFamily() {
        return this == MYSQL || this == MARIADB;
    }

    public boolean keepsDdlOfAFailedMigration() {
        return isMysqlFamily();
    }
}
