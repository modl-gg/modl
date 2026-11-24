package gg.modl.backend.storage.s3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

final class S3Requests {
    private static final String ANY_EXISTING_OBJECT = "*";
    private static final Set<String> TRANSPORT_HEADERS = Set.of("host", "content-length");

    private S3Requests() {
    }

    static PutObjectRequest createOnly(String bucket, String key, String contentType, long contentLength) {
        return PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(contentLength)
            .ifNoneMatch(ANY_EXISTING_OBJECT)
            .build();
    }

    static Map<String, String> clientUploadHeaders(Map<String, List<String>> signedHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        signedHeaders.forEach((name, values) -> {
            if (!TRANSPORT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                headers.put(name, String.join(",", values));
            }
        });
        return headers;
    }
}
