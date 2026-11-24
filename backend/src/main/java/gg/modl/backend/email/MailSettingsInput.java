package gg.modl.backend.email;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;

public record MailSettingsInput(String host, int port, @Nullable String username, String from, boolean starttls) {
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-:\\[\\]]+");

    public MailSettingsInput {
        host = host == null ? "" : host.trim();
        username = username == null || username.isBlank() ? null : username.trim();
        from = EmailAddressUtil.normalizeIfValid(from);
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH || !HOST.matcher(host).matches()) {
            throw new ValidationException("The SMTP host must be a host name or IP address");
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new ValidationException("The SMTP port must be between 1 and 65535");
        }
        if (from == null) {
            throw new ValidationException("The sender address must be a valid email address");
        }
    }

    public MailConfiguration withPassword(@Nullable String password) {
        return new MailConfiguration(host, port, username, password, from, starttls);
    }
}
