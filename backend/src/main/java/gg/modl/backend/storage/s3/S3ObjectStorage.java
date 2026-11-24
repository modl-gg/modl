package gg.modl.backend.storage.s3;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.OwnershipMarker;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadTarget;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
public class S3ObjectStorage implements ObjectStorage, AutoCloseable {
    private static final int NOT_FOUND_STATUS = 404;
    private static final int PRECONDITION_FAILED_STATUS = 412;
    private static final int DELETE_BATCH_SIZE = 1000;
    private static final String MARKER_CONTENT_TYPE = "text/plain";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBaseUrl;
    private final Clock clock;
    private final S3BucketVersioning versioning;

    public S3ObjectStorage(S3Client client, S3Presigner presigner, String bucket, String publicBaseUrl, Clock clock) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
        this.clock = clock;
        this.versioning = new S3BucketVersioning(client, bucket);
    }

    @Override
    public UploadTarget presignUpload(String key, String contentType, long contentLength) {
        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(UploadTarget.VALIDITY)
            .putObjectRequest(S3Requests.createOnly(bucket, key, contentType, contentLength))
            .build());
        return new UploadTarget(
            presigned.url().toString(),
            presigned.httpRequest().method().name(),
            S3Requests.clientUploadHeaders(presigned.signedHeaders()),
            clock.instant().plus(UploadTarget.VALIDITY)
        );
    }

    @Override
    public String publicUrl(String key) {
        return publicBaseUrl + "/" + key;
    }

    @Override
    public Optional<String> keyFromPublicUrl(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String prefix = publicBaseUrl + "/";
        if (!url.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            String key = URI.create(url).getPath().substring(URI.create(prefix).getPath().length());
            return key.isEmpty() ? Optional.empty() : Optional.of(key);
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    @Override
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    @Override
    public boolean exists(String key) {
        return stat(key).isPresent();
    }

    @Override
    public Optional<StoredObject> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new StoredObject(key, head.contentLength(), head.contentType(), head.lastModified()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == NOT_FOUND_STATUS) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public InputStream read(String key) {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new ResourceNotFoundException("Object not found", e);
        }
    }

    @Override
    public void write(String key, InputStream data, long contentLength, String contentType) {
        try {
            client.putObject(S3Requests.createOnly(bucket, key, contentType, contentLength), RequestBody.fromInputStream(data, contentLength));
        } catch (S3Exception e) {
            if (e.statusCode() == PRECONDITION_FAILED_STATUS) {
                throw new StoredObjectExistsException();
            }
            throw e;
        }
    }

    @Override
    public Set<String> deleteAll(Collection<String> keys) {
        List<String> distinctKeys = List.copyOf(new LinkedHashSet<>(keys));
        Set<String> failed = new LinkedHashSet<>();
        for (int start = 0; start < distinctKeys.size(); start += DELETE_BATCH_SIZE) {
            List<String> batch = distinctKeys.subList(start, Math.min(start + DELETE_BATCH_SIZE, distinctKeys.size()));
            failed.addAll(deleteIdentifiers(batch.stream().map(key -> ObjectIdentifier.builder().key(key).build()).toList()));
        }
        if (versioning.retainsVersions()) {
            distinctKeys.stream().filter(key -> !failed.contains(key)).forEach(key -> {
                if (!purgeVersions(key)) {
                    failed.add(key);
                }
            });
        }
        return failed;
    }

    @Override
    public Stream<ListedObject> list(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        return client.listObjectsV2Paginator(request).contents().stream()
            .filter(object -> !OwnershipMarker.isMarkerKey(object.key()))
            .map(object -> new ListedObject(object.key(), object.size(), object.lastModified()));
    }

    @Override
    public boolean hasCapacityFor(long bytes) {
        return true;
    }

    @Override
    public Optional<String> readOwnershipMarker(String namespace) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(OwnershipMarker.key(namespace)).build())
                .asString(StandardCharsets.UTF_8).trim());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == NOT_FOUND_STATUS) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void writeOwnershipMarker(String namespace, String owner) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(OwnershipMarker.key(namespace)).contentType(MARKER_CONTENT_TYPE).build(),
            RequestBody.fromString(owner, StandardCharsets.UTF_8)
        );
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }

    private Set<String> deleteIdentifiers(List<ObjectIdentifier> identifiers) {
        Set<String> failed = new LinkedHashSet<>();
        if (identifiers.isEmpty()) {
            return failed;
        }
        try {
            DeleteObjectsResponse response = client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(identifiers).quiet(true).build())
                .build());
            if (response.hasErrors()) {
                log.warn("Failed to delete one or more stored objects: {}", describe(response.errors()));
                response.errors().forEach(error -> failed.add(error.key()));
            }
        } catch (SdkException e) {
            log.warn("Failed to delete a batch of {} stored object(s)", identifiers.size(), e);
            identifiers.forEach(identifier -> failed.add(identifier.key()));
        }
        return failed;
    }

    private boolean purgeVersions(String key) {
        try {
            List<ObjectIdentifier> versions = allVersions(key);
            for (int start = 0; start < versions.size(); start += DELETE_BATCH_SIZE) {
                if (!deleteIdentifiers(versions.subList(start, Math.min(start + DELETE_BATCH_SIZE, versions.size()))).isEmpty()) {
                    return false;
                }
            }
            return true;
        } catch (SdkException e) {
            log.warn("Failed to purge the stored versions of {}", key, e);
            return false;
        }
    }

    private List<ObjectIdentifier> allVersions(String key) {
        List<ObjectIdentifier> identifiers = new ArrayList<>();
        String keyMarker = null;
        String versionIdMarker = null;
        ListObjectVersionsResponse response;
        do {
            response = client.listObjectVersions(ListObjectVersionsRequest.builder()
                .bucket(bucket)
                .prefix(key)
                .keyMarker(keyMarker)
                .versionIdMarker(versionIdMarker)
                .build());
            for (ObjectVersion version : response.versions()) {
                if (version.key().equals(key)) {
                    identifiers.add(ObjectIdentifier.builder().key(key).versionId(version.versionId()).build());
                }
            }
            for (DeleteMarkerEntry marker : response.deleteMarkers()) {
                if (marker.key().equals(key)) {
                    identifiers.add(ObjectIdentifier.builder().key(key).versionId(marker.versionId()).build());
                }
            }
            keyMarker = response.nextKeyMarker();
            versionIdMarker = response.nextVersionIdMarker();
        } while (Boolean.TRUE.equals(response.isTruncated()));
        return identifiers;
    }

    private static String describe(List<S3Error> errors) {
        return errors.stream().map(error -> error.key() + ":" + error.code()).collect(Collectors.joining(", "));
    }
}
