package gg.modl.backend.storage.backend;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.storage.StorageProperties;
import org.jspecify.annotations.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class S3Addresses {
    static final String INVALID_CODE = "S3_SETTINGS_INVALID";
    private static final Set<String> SCHEMES = Set.of("http", "https");
    private static final Pattern REGION = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern BUCKET = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7e]{1,512}");

    private S3Addresses() {
    }

    static void requireValid(StorageProperties.S3 connection) {
        requireUrl("endpoint", connection.endpoint());
        requireUrl("public URL", connection.publicUrl());
        requireMatch("region", connection.region(), REGION);
        requireMatch("bucket", connection.bucket(), BUCKET);
        requireMatch("access key", connection.accessKey(), KEY);
        requireMatch("secret key", connection.secretKey(), KEY);
    }

    private static void requireUrl(String field, @Nullable String value) {
        if (value == null) {
            return;
        }
        try {
            URI uri = new URI(value);
            boolean valid = uri.getScheme() != null && SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
                && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
            if (!valid) {
                throw invalid(field);
            }
        } catch (URISyntaxException e) {
            throw invalid(field);
        }
    }

    private static void requireMatch(String field, @Nullable String value, Pattern pattern) {
        if (value != null && !pattern.matcher(value).matches()) {
            throw invalid(field);
        }
    }

    private static ValidationException invalid(String field) {
        return new ValidationException("The S3 " + field + " is not valid", INVALID_CODE);
    }
}
