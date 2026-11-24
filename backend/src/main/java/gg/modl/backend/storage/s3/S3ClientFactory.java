package gg.modl.backend.storage.s3;

import gg.modl.backend.storage.StorageProperties;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.LegacyMd5Plugin;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

final class S3ClientFactory {

    private S3ClientFactory() {
    }

    static S3Client createClient(StorageProperties.S3 settings) {
        S3ClientBuilder builder = S3Client.builder()
            .credentialsProvider(credentials(settings))
            .region(Region.of(settings.region()))
            .forcePathStyle(true)
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .addPlugin(LegacyMd5Plugin.create());
        settings.endpointOverride().map(URI::create).ifPresent(builder::endpointOverride);
        return builder.build();
    }

    static S3Presigner createPresigner(StorageProperties.S3 settings) {
        S3Presigner.Builder builder = S3Presigner.builder()
            .credentialsProvider(credentials(settings))
            .region(Region.of(settings.region()))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        settings.endpointOverride().map(URI::create).ifPresent(builder::endpointOverride);
        return builder.build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties.S3 settings) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(), settings.secretKey()));
    }
}
