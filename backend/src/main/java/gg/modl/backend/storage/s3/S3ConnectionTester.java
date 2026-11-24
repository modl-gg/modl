package gg.modl.backend.storage.s3;

import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.service.StorageOwnerMarker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@Slf4j
@RequiredArgsConstructor
public class S3ConnectionTester {
    private static final String PROBE_FOLDER = StorageNamespace.KEY_PREFIX + ".modl-connection-test/";
    private static final String PROBE_CONTENT_TYPE = "text/plain";
    private static final byte[] PROBE_CONTENT = "modl connection test".getBytes(StandardCharsets.UTF_8);
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final Set<String> REGION_ERRORS = Set.of("AuthorizationHeaderMalformed", "PermanentRedirect", "IllegalLocationConstraintException");

    private final StorageOwnerMarker ownership;
    private final IdGenerator idGenerator;
    private final Clock clock;

    public S3ConnectionResult test(StorageProperties.S3 settings) {
        if (!settings.complete()) {
            return S3ConnectionResult.failed(S3ConnectionResult.INCOMPLETE, "Enter " + String.join(", ", settings.missingSettings()));
        }
        try (S3ObjectStorage storage = S3StorageFactory.create(settings, clock)) {
            return exercise(storage);
        } catch (S3Exception e) {
            return classify(e);
        } catch (SdkClientException e) {
            log.debug("The S3 endpoint could not be reached", e);
            return S3ConnectionResult.failed(S3ConnectionResult.UNREACHABLE,
                "The S3 endpoint could not be reached; check the endpoint address and that this server can connect to it");
        } catch (IllegalArgumentException e) {
            return S3ConnectionResult.failed(S3ConnectionResult.INVALID_ADDRESS, "The endpoint or region is not valid");
        } catch (SdkException | UncheckedIOException e) {
            log.debug("The S3 connection test failed", e);
            return S3ConnectionResult.failed(S3ConnectionResult.FAILED, "The S3 connection test failed");
        }
    }

    private S3ConnectionResult exercise(ObjectStorage storage) {
        StorageOwnerMarker.Verdict owner = ownership.inspect(storage);
        if (!owner.isOwned() && !owner.isUnclaimed()) {
            return S3ConnectionResult.failed(S3ConnectionResult.NOT_OWNED, owner.requireRefusalReason());
        }
        String key = PROBE_FOLDER + idGenerator.generateToken();
        storage.write(key, new ByteArrayInputStream(PROBE_CONTENT), PROBE_CONTENT.length, PROBE_CONTENT_TYPE);
        try {
            Optional<StoredObject> stored = storage.stat(key);
            if (stored.isEmpty() || stored.get().size() != PROBE_CONTENT.length || !Arrays.equals(read(storage, key), PROBE_CONTENT)) {
                return S3ConnectionResult.failed(S3ConnectionResult.FAILED, "The bucket did not return the test file that was written");
            }
        } finally {
            storage.delete(key);
        }
        return S3ConnectionResult.passed();
    }

    private static byte[] read(ObjectStorage storage, String key) {
        try (InputStream input = storage.read(key)) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static S3ConnectionResult classify(S3Exception e) {
        String errorCode = e.awsErrorDetails() == null ? "" : String.valueOf(e.awsErrorDetails().errorCode());
        if (REGION_ERRORS.contains(errorCode)) {
            return S3ConnectionResult.failed(S3ConnectionResult.WRONG_REGION, "The bucket is in another region; set the bucket's region");
        }
        if (e.statusCode() == FORBIDDEN) {
            return S3ConnectionResult.failed(S3ConnectionResult.ACCESS_DENIED,
                "The keys were refused, or they may not read, write and delete in this bucket");
        }
        if (e.statusCode() == NOT_FOUND || "NoSuchBucket".equals(errorCode)) {
            return S3ConnectionResult.failed(S3ConnectionResult.BUCKET_NOT_FOUND, "The bucket does not exist at this endpoint");
        }
        return S3ConnectionResult.failed(S3ConnectionResult.FAILED, "The S3 service refused the test (" + safeCode(errorCode) + ")");
    }

    private static String safeCode(String errorCode) {
        return errorCode.matches("[A-Za-z0-9.]{1,64}") ? errorCode : "unrecognized error";
    }
}
