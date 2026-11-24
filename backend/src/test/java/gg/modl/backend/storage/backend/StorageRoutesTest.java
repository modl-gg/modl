package gg.modl.backend.storage.backend;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageProperties;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.local.LocalObjectStorage;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageRoutesTest {
    private static final StorageProperties.S3 BUCKET = new StorageProperties.S3("https://s3.example.com", "eu-1", "modl", "AKID",
        "secret", null);
    private static final StorageProperties.S3 OTHER_BUCKET = new StorageProperties.S3("https://s3.example.com", "eu-1", "other",
        "AKID", "secret", null);

    @TempDir
    Path root;

    private LocalObjectStorage local;
    private StorageBackends backends;

    @BeforeEach
    void setUp() {
        local = StorageTestBackends.local(root);
        backends = new StorageBackends(local, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        backends.close();
    }

    @Test
    void anEnvironmentChosenStorageKeepsThePanelChosenOneReadable() {
        StorageBackendSettings panelChoseS3 = StorageBackendSettings.DEFAULTS.withS3(BUCKET).migrating(StorageType.LOCAL, StorageType.S3)
            .migrated(null);

        StorageRoute route = StorageRoutes.initial(new StorageProperties(StorageType.LOCAL, null), panelChoseS3, backends);

        assertThat(route.writeTarget()).isSameAs(local);
        assertThat(route.readOrder()).hasSize(2).first().isSameAs(local);
    }

    @Test
    void theSameBucketChosenTwiceIsNotAFallback() {
        StorageBackendSettings panelChoseS3 = StorageBackendSettings.DEFAULTS.withS3(BUCKET).migrating(StorageType.LOCAL, StorageType.S3)
            .migrated(null);

        StorageRoute route = StorageRoutes.initial(new StorageProperties(StorageType.S3, BUCKET), panelChoseS3, backends);

        assertThat(route.readOrder()).hasSize(1);
        assertThat(route.retiredCopies()).containsExactly(local);
    }

    @Test
    void oldCopiesOfEveryFormerBucketReceiveDeletesAfterARestart() {
        StorageBackendSettings settings = new StorageBackendSettings(StorageType.LOCAL, null, OTHER_BUCKET, null,
            List.of(new StorageBackendAddress(StorageType.S3, BUCKET)));

        StorageRoute route = StorageRoutes.initial(new StorageProperties(null, null), settings, backends);

        List<ObjectStorage> order = route.deletionOrder();
        assertThat(order).hasSize(2).last().isSameAs(local);
        assertThat(route.readOrder()).containsExactly(local);
    }
}
