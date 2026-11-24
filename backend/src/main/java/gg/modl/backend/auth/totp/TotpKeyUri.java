package gg.modl.backend.auth.totp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class TotpKeyUri {
    private static final String LABEL_SEPARATOR = ":";

    private TotpKeyUri() {
    }

    public static String of(String issuer, String account, String base32Secret) {
        String cleanIssuer = issuer.replace(LABEL_SEPARATOR, "").strip();
        String label = encode(cleanIssuer) + LABEL_SEPARATOR + encode(account);
        return "otpauth://totp/" + label
            + "?secret=" + base32Secret
            + "&issuer=" + encode(cleanIssuer)
            + "&algorithm=SHA1&digits=" + Totp.DIGITS
            + "&period=" + Totp.PERIOD_SECONDS;
    }

    private static String encode(String component) {
        return URLEncoder.encode(component, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
