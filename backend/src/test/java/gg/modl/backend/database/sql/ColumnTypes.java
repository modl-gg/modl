package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;

import org.jspecify.annotations.Nullable;
import java.sql.Types;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ColumnTypes {
    private static final Set<String> SEQUENCED_LOG_TABLES = Set.of(CHAT_LOGS.getName(), COMMAND_LOGS.getName(), AUDIT_LOGS.getName());
    private static final String SEQUENCED_ID = "id";
    private static final String TIME_SUFFIX = "_at";
    private static final Pattern CAST = Pattern.compile("^(.*?)::[a-z ]+$");
    private static final String QUOTE = "'";

    private ColumnTypes() {
    }

    static Class<?> generatedType(String table, String column, int sqlType) {
        if (SEQUENCED_LOG_TABLES.contains(table) && column.equals(SEQUENCED_ID)) {
            return Long.class;
        }
        if (sqlType == Types.BIGINT && column.endsWith(TIME_SUFFIX)) {
            return Instant.class;
        }
        return baseType(sqlType);
    }

    static boolean isText(Class<?> javaType) {
        return javaType == String.class;
    }

    @Nullable
    static String normalizedDefault(@Nullable String raw, Class<?> javaType) {
        if (raw == null || raw.trim().equalsIgnoreCase("NULL")) {
            return null;
        }
        String value = withoutCast(raw.trim());
        if (value.length() >= 2 && value.startsWith(QUOTE) && value.endsWith(QUOTE)) {
            value = value.substring(1, value.length() - 1).replace("''", QUOTE);
        }
        return javaType == Boolean.class ? booleanLiteral(value) : value;
    }

    private static Class<?> baseType(int sqlType) {
        return switch (sqlType) {
            case Types.BIGINT -> Long.class;
            case Types.INTEGER -> Integer.class;
            case Types.BIT, Types.BOOLEAN -> Boolean.class;
            case Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB, Types.NVARCHAR, Types.LONGNVARCHAR -> String.class;
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> byte[].class;
            default -> throw new IllegalStateException("No Java type is mapped for JDBC type " + sqlType);
        };
    }

    private static String withoutCast(String value) {
        Matcher cast = CAST.matcher(value);
        return cast.matches() ? cast.group(1) : value;
    }

    private static String booleanLiteral(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "0", "false", "b'0'" -> "false";
            case "1", "true", "b'1'" -> "true";
            default -> value;
        };
    }
}
