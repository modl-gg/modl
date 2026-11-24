package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.scheduling.ScheduledDatabaseJobs;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ConfirmedStorageTotal;
import gg.modl.backend.storage.ListedObject;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.storage.repository.StorageReservationRepository;
import gg.modl.backend.support.TestServers;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageReclamationServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Duration ORPHAN_TTL = Duration.ofHours(6);
    private static final Instant STALE = NOW.minus(ORPHAN_TTL).minusSeconds(60);

    @TempDir
    Path root;

    private final Server server = TestServers.server("server", "admin@example.com");
    private StorageFileRepository storageFileRepository;
    private StorageReservationRepository reservationRepository;
    private StorageDeletionService storageDeletionService;
    private StorageOwnership storageOwnership;
    private InstanceContext instanceContext;
    private Clock clock;

    @BeforeEach
    void setUp() {
        storageFileRepository = mock(StorageFileRepository.class);
        reservationRepository = mock(StorageReservationRepository.class);
        storageDeletionService = mock(StorageDeletionService.class);
        storageOwnership = mock(StorageOwnership.class);
        instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(server);
        when(storageOwnership.verify()).thenReturn(new StorageOwnerMarker.Verdict(true, false, null));
        when(storageFileRepository.hasAnyFile()).thenReturn(true);
        when(storageFileRepository.findByKeys(anyCollection())).thenReturn(List.of());
        when(reservationRepository.findReservedKeys(anyCollection())).thenReturn(Set.of());
        when(reservationRepository.findSettleableKeys(any(Instant.class), anyInt())).thenReturn(List.of());
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void staleObjectsWithoutAFileRecordOrReservationAreDeletedUnderEveryPrefix() throws IOException {
        LocalObjectStorage storage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), clock);
        List<String> recorded = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            recorded.add(write(storage, "modl_instance/evidence/PUN-" + index + "/recorded.png", STALE));
        }
        String reserved = write(storage, "modl_instance/replays/reserved.modlreplay", STALE);
        String orphanReplay = write(storage, "modl_instance/replays/orphan.modlreplay", STALE);
        String orphanIcon = write(storage, "modl_instance/icons_panel/orphan.png", STALE);
        String fresh = write(storage, "modl_instance/tickets/T-1/fresh.png", NOW.minusSeconds(60));
        String foreign = write(storage, "elsewhere/orphan.png", STALE);
        when(storageFileRepository.findByKeys(anyCollection()))
            .thenReturn(recorded.stream().map(StorageReclamationServiceTest::file).toList());
        when(reservationRepository.findReservedKeys(anyCollection())).thenReturn(Set.of(reserved));

        int reclaimed = service(storage).sweepUnrecordedObjects(server);

        assertThat(reclaimed).isEqualTo(2);
        assertThat(storage.exists(orphanReplay)).isFalse();
        assertThat(storage.exists(orphanIcon)).isFalse();
        assertThat(recorded).allMatch(storage::exists);
        assertThat(storage.exists(reserved)).isTrue();
        assertThat(storage.exists(fresh)).isTrue();
        assertThat(storage.exists(foreign)).isTrue();
        verify(storageDeletionService).notifyDeleted(eq(server), anyCollection());
    }

    @Test
    void aRecordedObjectReachedThroughADifferentlyCasedPathIsNotAnOrphan() throws IOException {
        LocalObjectStorage storage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), clock);
        String recorded = write(storage, "modl_instance/tickets/T-1/Clip.png", STALE);
        String pathOnDisk = recorded.toLowerCase(Locale.ROOT);
        for (Path tree : List.of(root, root.resolve(".meta"))) {
            Path parked = root.resolve("parked");
            Files.move(tree.resolve(recorded), parked);
            Files.createDirectories(tree.resolve(pathOnDisk).getParent());
            Files.move(parked, tree.resolve(pathOnDisk));
        }
        when(storageFileRepository.findByKeys(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            return keys.stream().filter(recorded::equals).map(StorageReclamationServiceTest::file).toList();
        });

        assertThat(service(storage).sweepUnrecordedObjects(server)).isZero();
        assertThat(root.resolve(pathOnDisk)).hasContent("x");
    }

    @Test
    void theSweepRefusesStorageThisInstallationDoesNotOwn() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storageOwnership.verify()).thenReturn(new StorageOwnerMarker.Verdict(false, false, "not ours"));

        assertThat(service(storage).sweepUnrecordedObjects(server)).isZero();
        verify(storage, never()).list(any());
        verify(storage, never()).deleteAll(anyCollection());
    }

    @Test
    void theSweepRefusesWhenTheDatabaseHasNoFileRecordsButTheStorageHoldsObjects() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storageFileRepository.hasAnyFile()).thenReturn(false);
        when(storage.list("modl_instance/")).thenReturn(Stream.of(new ListedObject("modl_instance/replays/a.modlreplay", 1L, STALE)));

        assertThat(service(storage).sweepUnrecordedObjects(server)).isZero();
        verify(storage, never()).deleteAll(anyCollection());
    }

    @Test
    void theSweepStopsWhenMostExaminedObjectsLookOrphaned() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.list("modl_instance/")).thenReturn(staleObjects(StorageReclamationService.RATIO_GUARD_MIN_ORPHANS + 5).stream());

        assertThat(service(storage).sweepUnrecordedObjects(server)).isZero();
        verify(storage, never()).deleteAll(anyCollection());
    }

    @Test
    void aFewOrphansAmongManyRecordedObjectsAreReclaimedInBoundedBatchesAndTheListingIsClosed() {
        ObjectStorage storage = mock(ObjectStorage.class);
        int total = StorageReclamationService.BATCH_SIZE * 2 + 1;
        List<ListedObject> objects = staleObjects(total);
        AtomicBoolean closed = new AtomicBoolean();
        when(storage.list("modl_instance/")).thenReturn(objects.stream().onClose(() -> closed.set(true)));
        Set<String> orphanKeys = Set.of(objects.get(3).key(), objects.get(StorageReclamationService.BATCH_SIZE + 7).key());
        when(storageFileRepository.findByKeys(anyCollection())).thenAnswer(invocation -> {
            Collection<String> keys = invocation.getArgument(0);
            return keys.stream().filter(key -> !orphanKeys.contains(key)).map(StorageReclamationServiceTest::file).toList();
        });
        List<Collection<String>> deletedBatches = new ArrayList<>();
        doAnswer(invocation -> {
            deletedBatches.add(List.copyOf(invocation.<Collection<String>>getArgument(0)));
            return Set.of();
        }).when(storage).deleteAll(anyCollection());

        int reclaimed = service(storage).sweepUnrecordedObjects(server);

        assertThat(reclaimed).isEqualTo(2);
        assertThat(deletedBatches).extracting(Collection::size).containsExactly(1, 1);
        assertThat(closed).isTrue();
    }

    @Test
    void objectsThatCouldNotBeDeletedAreNotReportedAsReclaimed() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.list("modl_instance/")).thenReturn(Stream.of(new ListedObject("modl_instance/replays/a.modlreplay", 1L, STALE)));
        when(storage.deleteAll(List.of("modl_instance/replays/a.modlreplay"))).thenReturn(Set.of("modl_instance/replays/a.modlreplay"));

        assertThat(service(storage).sweepUnrecordedObjects(server)).isZero();
        verify(storageDeletionService, never()).notifyDeleted(any(), anyCollection());
    }

    @Test
    void lapsedReservationsAreSettledOneClaimAtATime() {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(reservationRepository.findSettleableKeys(eq(NOW), eq(StorageReclamationService.BATCH_SIZE)))
            .thenReturn(List.of("modl_instance/a.png", "modl_instance/b.png"));
        when(storageDeletionService.settle(server, "modl_instance/a.png")).thenReturn(StorageDeletionService.SettleOutcome.ABANDONED);
        when(storageDeletionService.settle(server, "modl_instance/b.png")).thenReturn(StorageDeletionService.SettleOutcome.CONFIRMED);

        assertThat(service(storage).settleLapsedReservations(server)).isEqualTo(1);
        verify(storageDeletionService, times(2)).settle(eq(server), any());
    }

    @Test
    void settlingStopsWhenEveryLapsedReservationMustBeRetained() {
        ObjectStorage storage = mock(ObjectStorage.class);
        List<String> keys = IntStream.range(0, StorageReclamationService.BATCH_SIZE).mapToObj(index -> "modl_instance/" + index).toList();
        when(reservationRepository.findSettleableKeys(any(Instant.class), anyInt())).thenReturn(keys);
        when(storageDeletionService.settle(eq(server), any())).thenReturn(StorageDeletionService.SettleOutcome.RETAINED);

        assertThat(service(storage).settleLapsedReservations(server)).isZero();
        verify(reservationRepository, times(1)).findSettleableKeys(any(Instant.class), anyInt());
    }

    private StorageReclamationService service(ObjectStorage storage) {
        return new StorageReclamationService(storage, storageFileRepository, reservationRepository, storageDeletionService,
            storageOwnership, mock(ConfirmedStorageTotal.class), instanceContext,
            mock(ScheduledDatabaseJobs.class), clock, ORPHAN_TTL);
    }

    private static List<ListedObject> staleObjects(int count) {
        return IntStream.range(0, count)
            .mapToObj(index -> new ListedObject("modl_instance/replays/" + index + ".modlreplay", 1L, STALE))
            .toList();
    }

    private static StorageFile file(String key) {
        return new StorageFile(key, "file", 1L, "image/png", "other", NOW);
    }

    private String write(LocalObjectStorage storage, String key, Instant lastModified) throws IOException {
        storage.write(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "image/png");
        Files.setLastModifiedTime(root.resolve(key), FileTime.from(lastModified));
        return key;
    }
}
