package gg.modl.backend.email;

import org.jspecify.annotations.Nullable;
import java.util.Locale;
import java.util.Objects;

public record MailConfiguration(
    String host,
    int port,
    @Nullable String username,
    @Nullable String password,
    String from,
    boolean starttls
) {
    private static final int IMPLICIT_TLS_PORT = 465;

    public boolean usesImplicitTls() {
        return port == IMPLICIT_TLS_PORT;
    }

    public boolean usesStartTls() {
        return starttls && !usesImplicitTls();
    }

    public boolean hasCredentials() {
        return username != null;
    }

    public boolean hasPassword() {
        return password != null;
    }

    public boolean encryptsConnection() {
        return usesImplicitTls() || usesStartTls();
    }

    public boolean mayReusePasswordOf(MailConfiguration existing) {
        return host.toLowerCase(Locale.ROOT).equals(existing.host.toLowerCase(Locale.ROOT))
            && port == existing.port
            && Objects.equals(username, existing.username)
            && (encryptsConnection() || !existing.encryptsConnection());
    }

    public MailConfiguration plainProbe() {
        return new MailConfiguration(host, port, null, null, from, false);
    }

    public MailConfiguration withPassword(@Nullable String newPassword) {
        return new MailConfiguration(host, port, username, newPassword, from, starttls);
    }

    @Override
    public String toString() {
        return "MailConfiguration[host=" + host + ", port=" + port + ", username=" + username + ", from=" + from
            + ", starttls=" + starttls + "]";
    }
}
