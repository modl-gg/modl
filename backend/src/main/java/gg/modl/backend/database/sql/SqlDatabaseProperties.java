package gg.modl.backend.database.sql;

import gg.modl.backend.database.DatabaseDialect;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record SqlDatabaseProperties(
    @Nullable String url,
    @Nullable String username,
    @Nullable String password,
    Integer poolSize,
    Sqlite sqlite
) {
    public static final int DEFAULT_POOL_SIZE = 10;
    public static final int MAX_POOL_SIZE = 100;

    private static final Pattern CREDENTIAL_PARAMETER = Pattern.compile("(?i)[?&;(,](user|password[1-3]?)=");
    private static final Pattern AFFECTED_ROWS = Pattern.compile("(?i)[?&;(,]useAffectedRows=(?!false(?:[&;),]|$))");
    private static final Pattern USER_INFO = Pattern.compile("(?is)[a-z]+://[^/?#]*@.*");
    private static final String JDBC_PREFIX = "jdbc:";

    public SqlDatabaseProperties {
        url = blankToNull(url);
        username = blankToNull(username);
        password = blankToNull(password);
        poolSize = poolSize == null ? DEFAULT_POOL_SIZE : poolSize;
        sqlite = sqlite == null ? new Sqlite(null) : sqlite;
        requireNoCredentialsInUrl(url);
        requireMatchedRowCounts(url);
        requireValidPoolSize(poolSize);
        DatabaseDialect.ofUrl(url);
    }

    public static SqlDatabaseProperties builtIn() {
        return new SqlDatabaseProperties(null, null, null, DEFAULT_POOL_SIZE, new Sqlite(null));
    }

    public DatabaseDialect dialect() {
        return DatabaseDialect.ofUrl(url);
    }

    public Optional<String> externalUrl() {
        return Optional.ofNullable(url);
    }

    @Override
    public String toString() {
        return "SqlDatabaseProperties[dialect=" + dialect() + ", poolSize=" + poolSize + ", credentials="
            + (username == null && password == null ? "none" : "redacted") + "]";
    }

    public record Sqlite(@Nullable String nativeDir) {
        public Sqlite {
            nativeDir = blankToNull(nativeDir);
        }

        public Optional<String> configuredNativeDir() {
            return Optional.ofNullable(nativeDir);
        }
    }

    private static void requireNoCredentialsInUrl(@Nullable String url) {
        if (url == null) {
            return;
        }
        if (CREDENTIAL_PARAMETER.matcher(url).find() || hasUserInfo(url)) {
            throw new IllegalArgumentException("MODL_DATABASE_URL must not contain a user name or password; "
                + "set MODL_DATABASE_USERNAME and MODL_DATABASE_PASSWORD instead");
        }
    }

    private static void requireMatchedRowCounts(@Nullable String url) {
        if (url == null) {
            return;
        }
        if (AFFECTED_ROWS.matcher(url).find()) {
            throw new IllegalArgumentException("MODL_DATABASE_URL must not set useAffectedRows; "
                + "modl needs the number of matched rows, which is the driver default");
        }
    }

    private static boolean hasUserInfo(String url) {
        return url.toLowerCase(Locale.ROOT).startsWith(JDBC_PREFIX)
            && USER_INFO.matcher(url.substring(JDBC_PREFIX.length())).matches();
    }

    private static void requireValidPoolSize(int poolSize) {
        if (poolSize < 1 || poolSize > MAX_POOL_SIZE) {
            throw new IllegalArgumentException("MODL_DATABASE_POOL_SIZE must be between 1 and " + MAX_POOL_SIZE);
        }
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
