package gg.modl.backend.database.selection;

import gg.modl.backend.database.DatabaseDialect;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DatabaseLocation(DatabaseDialect dialect, String host, int port, String database) {
    private static final Pattern URL = Pattern.compile("(?i)jdbc:[a-z]+://([^/?#;]+?)(?::(\\d{1,5}))?/([^?#;]*).*");
    private static final String IPV6_OPEN = "[";
    private static final String IPV6_CLOSE = "]";

    public static final DatabaseLocation BUILT_IN = new DatabaseLocation(DatabaseDialect.SQLITE, "", 0, "");

    public DatabaseLocation {
        host = host.toLowerCase(Locale.ROOT);
    }

    public static DatabaseLocation ofUrl(DatabaseDialect dialect, String url) {
        Matcher matcher = URL.matcher(url);
        if (!matcher.matches()) {
            return new DatabaseLocation(dialect, "", 0, "");
        }
        String host = matcher.group(1);
        if (host.startsWith(IPV6_OPEN) && host.endsWith(IPV6_CLOSE)) {
            host = host.substring(1, host.length() - 1);
        }
        int port = Optional.ofNullable(matcher.group(2)).map(Integer::parseInt).orElse(0);
        return new DatabaseLocation(dialect, host, port, matcher.group(3));
    }

    public boolean isBuiltIn() {
        return dialect == DatabaseDialect.SQLITE;
    }

    public String describe() {
        if (isBuiltIn()) {
            return "the built-in SQLite database";
        }
        return dialect.displayName() + " at " + address() + "/" + database;
    }

    private String address() {
        String shown = host.contains(":") ? IPV6_OPEN + host + IPV6_CLOSE : host;
        return port == 0 ? shown : shown + ":" + port;
    }
}
