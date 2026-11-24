package gg.modl.backend.storage.backend;

import static gg.modl.backend.storage.backend.StorageTestBackends.put;
import static gg.modl.backend.storage.backend.StorageTestBackends.read;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.StorageType;
import gg.modl.backend.storage.local.LocalObjectStorage;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwitchableObjectStorageTest {
    private static final String OLD_KEY = StorageNamespace.KEY_PREFIX + "tickets/T-1/old.txt";
    private static final String NEW_KEY = StorageNamespace.KEY_PREFIX + "tickets/T-2/new.txt";

    @TempDir
    Path sourceRoot;
    @TempDir
    Path targetRoot;

    private LocalObjectStorage source;
    private LocalObjectStorage target;
    private SwitchableObjectStorage storage;

    @BeforeEach
    void setUp() {
        source = StorageTestBackends.local(sourceRoot);
        target = StorageTestBackends.local(targetRoot);
        put(source, OLD_KEY, "old");
        storage = new SwitchableObjectStorage(new StorageRoute.Stable(StorageType.LOCAL, source));
    }

    @Test
    void aStableRoutePassesEverythingToItsBackend() {
        put(storage, NEW_KEY, "new");

        assertThat(source.exists(NEW_KEY)).isTrue();
        assertThat(storage.writesToLocalDisk()).isTrue();
    }

    @Test
    void whileMigratingNewFilesGoToTheTargetAndOldOnesAreStillServed() {
        storage.use(new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.S3, target));

        put(storage, NEW_KEY, "new");

        assertThat(target.exists(NEW_KEY)).isTrue();
        assertThat(source.exists(NEW_KEY)).isFalse();
        assertThat(read(storage, OLD_KEY)).isEqualTo("old");
        assertThat(storage.stat(OLD_KEY)).isPresent();
        assertThat(storage.writesToLocalDisk()).isFalse();
    }

    @Test
    void aFileIsServedFromTheTargetOnceItWasCopied() {
        storage.use(new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.LOCAL, target));
        put(target, OLD_KEY, "copied");

        assertThat(read(storage, OLD_KEY)).isEqualTo("copied");
    }

    @Test
    void deletesReachBothBackendsWhileMigrating() {
        storage.use(new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.LOCAL, target));
        put(target, OLD_KEY, "copied");

        assertThat(storage.deleteAll(List.of(OLD_KEY))).isEmpty();

        assertThat(source.exists(OLD_KEY)).isFalse();
        assertThat(target.exists(OLD_KEY)).isFalse();
    }

    @Test
    void listingCoversBothBackendsWhileMigrating() {
        storage.use(new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.LOCAL, target));
        put(storage, NEW_KEY, "new");

        try (Stream<ListedObject> listed = storage.list(StorageNamespace.KEY_PREFIX)) {
            assertThat(listed.map(ListedObject::key).distinct()).containsExactlyInAnyOrder(OLD_KEY, NEW_KEY);
        }
    }

    @Test
    void aDeleteAfterAFinishedMoveAlsoRemovesTheOldCopy() {
        put(target, OLD_KEY, "copied");
        storage.use(new StorageRoute.Stable(StorageType.LOCAL, target, List.of(), List.of(source)));

        assertThat(storage.deleteAll(List.of(OLD_KEY))).isEmpty();

        assertThat(target.exists(OLD_KEY)).isFalse();
        assertThat(source.exists(OLD_KEY)).isFalse();
    }

    @Test
    void oldCopiesAreNeverServed() {
        storage.use(new StorageRoute.Stable(StorageType.LOCAL, target, List.of(), List.of(source)));

        assertThat(storage.stat(OLD_KEY)).isEmpty();
        assertThat(storage.exists(OLD_KEY)).isFalse();
    }

    @Test
    void filesLeftInThePanelChosenStorageStayReadableWhenTheEnvironmentPicksAnother() {
        storage.use(new StorageRoute.Stable(StorageType.LOCAL, target, List.of(source), List.of()));
        put(storage, NEW_KEY, "new");

        assertThat(read(storage, OLD_KEY)).isEqualTo("old");
        assertThat(target.exists(NEW_KEY)).isTrue();
        assertThat(source.exists(NEW_KEY)).isFalse();
    }

    @Test
    void theNewStorageIsDeletedFromLastSoACopyInFlightCannotOutliveTheDelete() {
        StorageRoute.Migrating migrating = new StorageRoute.Migrating(StorageType.LOCAL, source, StorageType.LOCAL, target);

        assertThat(migrating.deletionOrder()).containsExactly(source, target);
        assertThat(new StorageRoute.Stable(StorageType.LOCAL, target, List.of(), List.of(source)).deletionOrder())
            .containsExactly(source, target);
    }

    @Test
    void linksOfAFormerBucketStillResolveToTheirKey() {
        storage.recognizeFormerUrls(List.of(new FormerPublicBase("https://cdn.example.com/media")));

        assertThat(storage.keyFromPublicUrl("https://cdn.example.com/media/" + OLD_KEY)).contains(OLD_KEY);
        assertThat(storage.keyFromPublicUrl("https://cdn.example.com/media/" + OLD_KEY + "?x=1")).isEmpty();
        assertThat(storage.keyFromPublicUrl("https://other.example.com/media/" + OLD_KEY)).isEmpty();
    }
}
