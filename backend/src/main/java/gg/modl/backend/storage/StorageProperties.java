package gg.modl.backend.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "modl.storage")
public record StorageProperties(
    @Nullable StorageType type,
    S3 s3
) {
    public StorageProperties {
        s3 = s3 == null ? S3.EMPTY : s3;
    }

    public Optional<StorageType> configuredType() {
        return Optional.ofNullable(type);
    }

    public record S3(
        @Nullable String endpoint,
        String region,
        @Nullable String bucket,
        @Nullable String accessKey,
        @Nullable String secretKey,
        @Nullable String publicUrl
    ) {
        private static final String DEFAULT_REGION = "us-east-1";
        public static final S3 EMPTY = new S3(null, DEFAULT_REGION, null, null, null, null);

        public S3 {
            endpoint = withoutTrailingSlash(blankToNull(endpoint));
            region = Optional.ofNullable(blankToNull(region)).orElse(DEFAULT_REGION);
            bucket = blankToNull(bucket);
            accessKey = blankToNull(accessKey);
            secretKey = blankToNull(secretKey);
            publicUrl = withoutTrailingSlash(blankToNull(publicUrl));
        }

        public Optional<String> endpointOverride() {
            return Optional.ofNullable(endpoint);
        }

        public String publicBaseUrl() {
            if (publicUrl != null) {
                return publicUrl;
            }
            String host = endpoint != null ? endpoint : "https://s3." + region + ".amazonaws.com";
            return host + "/" + bucket;
        }

        public List<String> missingSettings() {
            List<String> missing = new ArrayList<>();
            if (bucket == null) {
                missing.add("bucket");
            }
            if (accessKey == null) {
                missing.add("access key");
            }
            if (secretKey == null) {
                missing.add("secret key");
            }
            return missing;
        }

        public boolean complete() {
            return missingSettings().isEmpty();
        }

        public boolean sameBucket(S3 other) {
            return Objects.equals(endpoint, other.endpoint) && Objects.equals(region, other.region)
                && Objects.equals(bucket, other.bucket);
        }

        public boolean sameAccountAt(S3 other) {
            return accessKey != null && accessKey.equals(other.accessKey) && Objects.equals(endpoint, other.endpoint)
                && Objects.equals(bucket, other.bucket);
        }

        public S3 withSecretKey(@Nullable String replacement) {
            return new S3(endpoint, region, bucket, accessKey, replacement, publicUrl);
        }

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", accessKey="
                + (accessKey == null ? "none" : "set") + ", secretKey=" + (secretKey == null ? "none" : "set")
                + ", publicUrl=" + publicUrl + "]";
        }

        @Nullable
        private static String withoutTrailingSlash(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value;
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
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
