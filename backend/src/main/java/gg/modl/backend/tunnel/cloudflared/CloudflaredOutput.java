package gg.modl.backend.tunnel.cloudflared;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CloudflaredOutput {
    static final String REGISTERED = "Registered tunnel connection";
    static final int MAX_LINE_LENGTH = 4096;
    static final String REDACTED = "[redacted]";

    private static final Pattern METRICS_SERVER = Pattern.compile("Starting metrics server on (127\\.0\\.0\\.1:\\d{1,5})/metrics");

    private CloudflaredOutput() {
    }

    static boolean registersConnection(String line) {
        return line.contains(REGISTERED) && !line.contains("Unregistered");
    }

    static Optional<URI> readinessEndpoint(String line) {
        Matcher matcher = METRICS_SERVER.matcher(line);
        return matcher.find() ? Optional.of(URI.create("http://" + matcher.group(1) + "/ready")) : Optional.empty();
    }

    static String forLog(String line, String token) {
        String redacted = line.replace(token, REDACTED);
        return redacted.length() > MAX_LINE_LENGTH ? redacted.substring(0, MAX_LINE_LENGTH) : redacted;
    }
}
