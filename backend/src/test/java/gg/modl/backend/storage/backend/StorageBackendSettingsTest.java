package gg.modl.backend.storage.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import org.junit.jupiter.api.Test;

class StorageBackendSettingsTest {
    private static final String SECRET_KEY = "s3-secret-value-91b2";
    private static final StorageProperties.S3 CONNECTION = new StorageProperties.S3("https://s3.example.com", "eu-1", "modl", "AKID",
        SECRET_KEY, null);

    @Test
    void aFreshInstallationStoresEverythingLocally() {
        assertThat(StorageBackendSettings.DEFAULTS.holdsEveryUploadInTheLocalFolder()).isTrue();
        assertThat(StorageBackendSettings.DEFAULTS.usesS3()).isFalse();
    }

    @Test
    void aMoveInProgressMeansTheLocalFolderIsIncomplete() {
        StorageBackendSettings moving = StorageBackendSettings.DEFAULTS.withS3(CONNECTION).migrating(StorageType.LOCAL, StorageType.S3);

        assertThat(moving.holdsEveryUploadInTheLocalFolder()).isFalse();
        assertThat(moving.usesLocalFolder()).isTrue();
        assertThat(moving.usesS3()).isTrue();
    }

    @Test
    void finishingAMoveOutOfS3RemembersTheBucketForOldLinks() {
        StorageBackendSettings moving = new StorageBackendSettings(StorageType.S3, StorageType.LOCAL, CONNECTION, null, null);

        StorageBackendSettings moved = moving.migrated(CONNECTION.publicBaseUrl());

        assertThat(moved.active()).isEqualTo(StorageType.LOCAL);
        assertThat(moved.migrationTarget()).isEmpty();
        assertThat(moved.formerPublicBaseUrls()).containsExactly("https://s3.example.com/modl");
    }

    @Test
    void finishingAMoveRemembersWhereTheOldCopiesAre() {
        StorageBackendSettings moved = new StorageBackendSettings(StorageType.S3, StorageType.LOCAL, CONNECTION, null, null).migrated(null);

        assertThat(moved.oldCopies()).containsExactly(new StorageBackendAddress(StorageType.S3, CONNECTION));
        assertThat(moved.migrating(StorageType.LOCAL, StorageType.S3).migrated(null).oldCopies())
            .containsExactly(new StorageBackendAddress(StorageType.LOCAL, null));
    }

    @Test
    void removedOldCopiesAreForgotten() {
        StorageBackendSettings moved = new StorageBackendSettings(StorageType.S3, StorageType.LOCAL, CONNECTION, null, null).migrated(null);

        assertThat(moved.withoutOldCopy(new StorageBackendAddress(StorageType.S3, CONNECTION)).oldCopies()).isEmpty();
    }

    @Test
    void aStoredSecretIsReusedOnlyForTheSameAccountAtTheSameBucket() {
        StorageProperties.S3 sameAccount = new StorageProperties.S3("https://s3.example.com", "eu-2", "modl", "AKID", null, "https://cdn");
        StorageProperties.S3 otherEndpoint = new StorageProperties.S3("https://evil.example.com", "eu-1", "modl", "AKID", null, null);
        StorageProperties.S3 otherBucket = new StorageProperties.S3("https://s3.example.com", "eu-1", "other", "AKID", null, null);

        assertThat(sameAccount.sameAccountAt(CONNECTION)).isTrue();
        assertThat(otherEndpoint.sameAccountAt(CONNECTION)).isFalse();
        assertThat(otherBucket.sameAccountAt(CONNECTION)).isFalse();
    }

    @Test
    void theRememberedBucketsAreBounded() {
        StorageBackendSettings settings = StorageBackendSettings.DEFAULTS;
        for (int bucket = 0; bucket < 20; bucket++) {
            settings = settings.migrating(StorageType.S3, StorageType.LOCAL).migrated("https://cdn" + bucket + ".example.com");
        }

        assertThat(settings.formerPublicBaseUrls()).hasSize(StorageBackendSettings.MAX_FORMER_PUBLIC_BASE_URLS)
            .first().isEqualTo("https://cdn19.example.com");
    }

    @Test
    void theSecretKeyNeverAppearsInLogs() {
        assertThat(CONNECTION.toString()).doesNotContain(SECRET_KEY).doesNotContain("AKID");
    }

    @Test
    void s3SettingsAreValidatedBeforeTheyAreUsed() {
        assertThatThrownBy(() -> S3Addresses.requireValid(new StorageProperties.S3("ftp://s3.example.com", null, "b", "k", "s", null)))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> S3Addresses.requireValid(
            new StorageProperties.S3("https://user:pass@s3.example.com", null, "b", "k", "s", null)))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> S3Addresses.requireValid(new StorageProperties.S3(null, null, "bad bucket", "k", "s", null)))
            .isInstanceOf(ValidationException.class);
        S3Addresses.requireValid(CONNECTION);
    }
}
