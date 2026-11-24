package gg.modl.backend.storage.s3;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Slf4j
final class S3BucketVersioning {
    private static final int NOT_IMPLEMENTED_STATUS = 501;
    private static final String NOT_IMPLEMENTED_CODE = "NotImplemented";

    private final S3Client client;
    private final String bucket;
    private final AtomicReference<@Nullable Boolean> retainsVersions = new AtomicReference<>();

    S3BucketVersioning(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    boolean retainsVersions() {
        Boolean known = retainsVersions.get();
        if (known != null) {
            return known;
        }
        try {
            BucketVersioningStatus status = client.getBucketVersioning(GetBucketVersioningRequest.builder().bucket(bucket).build()).status();
            boolean versioned = status == BucketVersioningStatus.ENABLED || status == BucketVersioningStatus.SUSPENDED;
            retainsVersions.compareAndSet(null, versioned);
            return versioned;
        } catch (S3Exception e) {
            if (isNotImplemented(e)) {
                retainsVersions.compareAndSet(null, false);
            } else {
                log.debug("Could not read the versioning state of bucket {}", bucket, e);
            }
            return false;
        } catch (SdkException e) {
            log.debug("Could not read the versioning state of bucket {}", bucket, e);
            return false;
        }
    }

    private static boolean isNotImplemented(S3Exception exception) {
        return exception.statusCode() == NOT_IMPLEMENTED_STATUS
            || (exception.awsErrorDetails() != null && NOT_IMPLEMENTED_CODE.equals(exception.awsErrorDetails().errorCode()));
    }
}
