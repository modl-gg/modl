package gg.modl.backend.storage.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.storage.StoredObjectExistsException;
import gg.modl.backend.storage.UploadTarget;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ObjectStorageTest {
    private static final String BUCKET = "modl";

    private S3Client client;
    private S3Presigner presigner;
    private S3ObjectStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(S3Client.class);
        presigner = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
            .endpointOverride(URI.create("http://storage.example.test"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
        storage = new S3ObjectStorage(client, presigner, BUCKET, "http://cdn.example.test/modl", Clock.systemUTC());
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenReturn(GetBucketVersioningResponse.builder().build());
    }

    @Test
    void presignedUploadsAreCreateOnlyAndListEverySignedHeaderTheClientMustSend() {
        UploadTarget target = storage.presignUpload("modl_instance/evidence/a.png", "image/png", 42);

        assertThat(target.method()).isEqualTo("PUT");
        assertThat(target.headers()).containsEntry("if-none-match", "*").containsEntry("content-type", "image/png");
        assertThat(target.headers().keySet()).noneMatch(name -> name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length"));
        assertThat(target.url()).contains("X-Amz-SignedHeaders=").contains("if-none-match");
    }

    @Test
    void directWritesAreCreateOnlyAndAPreconditionFailureMeansTheObjectExists() {
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenThrow(S3Exception.builder().statusCode(412).message("precondition").build());

        assertThatThrownBy(() -> storage.write("modl_instance/icons_panel/a.png", new ByteArrayInputStream(new byte[] {1}), 1, "image/png"))
            .isInstanceOf(StoredObjectExistsException.class);
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
    }

    @Test
    void deletionUsesBatchedKeyDeletesWithoutListingVersionsOnUnversionedBuckets() {
        List<String> keys = IntStream.range(0, 1001).mapToObj(index -> "modl_instance/replays/" + index).toList();

        assertThat(storage.deleteAll(keys)).isEmpty();

        ArgumentCaptor<DeleteObjectsRequest> requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client, times(2)).deleteObjects(requests.capture());
        assertThat(requests.getAllValues().get(0).delete().objects()).hasSize(1000).allMatch(identifier -> identifier.versionId() == null);
        assertThat(requests.getAllValues().get(1).delete().objects()).hasSize(1);
        verify(client, never()).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test
    void keysTheStoreRejectsAreReportedAsFailures() {
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
            .errors(S3Error.builder().key("modl_instance/b").code("AccessDenied").build())
            .build());

        assertThat(storage.deleteAll(List.of("modl_instance/a", "modl_instance/b"))).containsExactly("modl_instance/b");
    }

    @Test
    void aFailedBatchReportsEveryKeyInIt() {
        when(client.deleteObjects(any(DeleteObjectsRequest.class))).thenThrow(SdkClientException.create("network down"));

        assertThat(storage.deleteAll(List.of("modl_instance/a", "modl_instance/b")))
            .containsExactlyInAnyOrder("modl_instance/a", "modl_instance/b");
    }

    @Test
    void bucketsWithoutVersioningSupportAreTreatedAsUnversionedOnce() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class))).thenThrow(S3Exception.builder()
            .statusCode(501)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NotImplemented").build())
            .build());

        storage.deleteAll(List.of("modl_instance/a"));
        storage.deleteAll(List.of("modl_instance/b"));

        verify(client, times(1)).getBucketVersioning(any(GetBucketVersioningRequest.class));
        verify(client, never()).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test
    void versionedBucketsAlsoPurgeEveryVersionOfADeletedKey() {
        when(client.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(ListObjectVersionsResponse.builder()
            .versions(ObjectVersion.builder().key("modl_instance/a").versionId("v1").build(),
                ObjectVersion.builder().key("modl_instance/ab").versionId("v2").build())
            .isTruncated(false)
            .build());

        assertThat(storage.deleteAll(List.of("modl_instance/a"))).isEmpty();

        ArgumentCaptor<DeleteObjectsRequest> requests = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client, times(2)).deleteObjects(requests.capture());
        assertThat(requests.getAllValues().get(1).delete().objects())
            .containsExactly(ObjectIdentifier.builder().key("modl_instance/a").versionId("v1").build());
    }
}
