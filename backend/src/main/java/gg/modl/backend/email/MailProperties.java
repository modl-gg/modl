package gg.modl.backend.email;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.mail")
public record MailProperties(
    @Nullable String host,
    Integer port,
    @Nullable String username,
    @Nullable String password,
    @Nullable String from,
    @Nullable Boolean starttls
) {
    public static final int DEFAULT_PORT = 587;

    public MailProperties {
        host = trimToNull(host);
        port = port == null || port <= 0 ? DEFAULT_PORT : port;
        username = trimToNull(username);
        password = password == null || password.isEmpty() ? null : password;
        from = trimToNull(from);
        starttls = starttls == null || starttls;
    }

    public Optional<MailConfiguration> configuration() {
        if (host == null) {
            return Optional.empty();
        }
        String fromAddress = fromAddress().orElseThrow(() -> new IllegalStateException(
            "MODL_SMTP_FROM must be set when MODL_SMTP_HOST is set"));
        return Optional.of(new MailConfiguration(host, port, username, password, fromAddress, Boolean.TRUE.equals(starttls)));
    }

    private Optional<String> fromAddress() {
        return Optional.ofNullable(from).or(() -> Optional.ofNullable(username).filter(value -> value.contains("@")));
    }

    @Override
    public String toString() {
        return "MailProperties[host=" + host + ", port=" + port + ", username=" + username + ", from=" + from
            + ", starttls=" + starttls + "]";
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
