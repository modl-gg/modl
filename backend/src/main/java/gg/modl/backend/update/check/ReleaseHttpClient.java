package gg.modl.backend.update.check;

import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.UpdateProperties;
import gg.modl.update.ArtifactFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class ReleaseHttpClient {
    static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    static final int READ_TIMEOUT_MILLIS = 10_000;
    static final int MAX_REDIRECTS = 5;
    static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 30_000;

    private static final String HTTPS = "https";
    private static final String GITHUB = "github.com";
    private static final String GITHUB_CONTENT_SUFFIX = ".githubusercontent.com";
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    private final Set<String> configuredHosts;
    private final String userAgent;
    private final Clock clock;

    public ReleaseHttpClient(UpdateProperties properties, ReleaseBuild build, Clock clock) {
        this.configuredHosts = Stream.of(properties.baseUri(), properties.apiUri()).map(ReleaseHttpClient::host)
            .collect(Collectors.toUnmodifiableSet());
        this.userAgent = "modl/" + build.versionText();
        this.clock = clock;
    }

    ReleaseResponse get(URI uri, @Nullable String etag, int maxBytes, boolean api) throws IOException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = open(current, etag, api);
            try {
                int status = connection.getResponseCode();
                if (REDIRECTS.contains(status)) {
                    current = redirectTarget(current, connection.getHeaderField("Location"));
                    continue;
                }
                byte[] body = status == ReleaseResponse.OK ? readBody(connection, maxBytes) : new byte[0];
                return new ReleaseResponse(status, body, connection.getHeaderField("ETag"), retryAfter(connection));
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + uri);
    }

    public String download(URI uri, Path target, long expectedBytes, Duration budget, LongConsumer progress) throws IOException {
        long deadline = System.nanoTime() + budget.toNanos();
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = open(current, null, false);
            connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS);
            try {
                int status = connection.getResponseCode();
                if (REDIRECTS.contains(status)) {
                    current = redirectTarget(current, connection.getHeaderField("Location"));
                    continue;
                }
                if (status != ReleaseResponse.OK) {
                    throw new IOException("Downloading " + uri + " failed with HTTP " + status);
                }
                return copy(connection, target, expectedBytes, deadline, progress);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("More than " + MAX_REDIRECTS + " redirects from " + uri);
    }

    private static String copy(HttpURLConnection connection, Path target, long expectedBytes, long deadlineNanos,
                               LongConsumer progress) throws IOException {
        MessageDigest digest = ArtifactFiles.newDigest();
        long written = 0;
        try (InputStream input = connection.getInputStream();
             FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                 StandardOpenOption.WRITE)) {
            OutputStream output = Channels.newOutputStream(channel);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                written += read;
                if (written > expectedBytes) {
                    throw new IOException("The download is larger than the " + expectedBytes + " bytes the release lists");
                }
                if (System.nanoTime() > deadlineNanos) {
                    throw new IOException("The download did not finish in time");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                progress.accept(written);
            }
            output.flush();
            channel.force(true);
        }
        if (written != expectedBytes) {
            throw new IOException("The download has " + written + " bytes, the release lists " + expectedBytes);
        }
        return ArtifactFiles.hex(digest.digest());
    }

    private HttpURLConnection open(URI uri, @Nullable String etag, boolean api) throws IOException {
        requireAllowed(uri);
        URLConnection opened = uri.toURL().openConnection();
        if (!(opened instanceof HttpURLConnection connection)) {
            throw new IOException("Not an HTTP URL: " + uri);
        }
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/json");
        if (etag != null) {
            connection.setRequestProperty("If-None-Match", etag);
        }
        return connection;
    }

    URI redirectTarget(URI from, @Nullable String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new IOException("A redirect from " + from + " has no location");
        }
        URI target = from.resolve(location.trim());
        if (HTTPS.equals(from.getScheme()) && !HTTPS.equals(target.getScheme())) {
            throw new IOException("Refused a redirect from https to " + target.getScheme());
        }
        requireAllowed(target);
        return target;
    }

    private void requireAllowed(URI uri) throws IOException {
        String host = host(uri);
        boolean allowed = configuredHosts.contains(host) || GITHUB.equals(host) || host.endsWith(GITHUB_CONTENT_SUFFIX);
        if (!allowed || !("http".equals(uri.getScheme()) || HTTPS.equals(uri.getScheme())) || uri.getRawUserInfo() != null) {
            throw new IOException("Refused to contact " + uri.getScheme() + "://" + host);
        }
        if (!HTTPS.equals(uri.getScheme()) && !configuredHosts.contains(host)) {
            throw new IOException("Refused plain http to " + host);
        }
    }

    private static byte[] readBody(HttpURLConnection connection, int maxBytes) throws IOException {
        long declared = connection.getContentLengthLong();
        if (declared > maxBytes) {
            throw new IOException("The response is larger than " + maxBytes + " bytes");
        }
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                body.write(buffer, 0, read);
                if (body.size() > maxBytes) {
                    throw new IOException("The response is larger than " + maxBytes + " bytes");
                }
            }
            return body.toByteArray();
        }
    }

    @Nullable
    private Duration retryAfter(HttpURLConnection connection) {
        Duration retryAfter = parseRetryAfter(connection.getHeaderField("Retry-After"), clock.instant());
        if (retryAfter != null) {
            return retryAfter;
        }
        if ("0".equals(connection.getHeaderField("X-RateLimit-Remaining"))) {
            return parseReset(connection.getHeaderField("X-RateLimit-Reset"), clock.instant());
        }
        return null;
    }

    @Nullable
    static Duration parseRetryAfter(@Nullable String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            try {
                Instant at = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    @Nullable
    static Duration parseReset(@Nullable String value, Instant now) {
        if (value == null) {
            return null;
        }
        try {
            Instant at = Instant.ofEpochSecond(Long.parseLong(value.trim()));
            return at.isAfter(now) ? Duration.between(now, at) : Duration.ZERO;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }
}
