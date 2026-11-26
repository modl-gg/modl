package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.repository.JooqReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.UploadConfirmation;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.Answer;

class ReplayUploadContractTest {
    private static final long FILE_SIZE = 2048;
    private static final String SUPPLEMENTARY = new String(Character.toChars(0x10400));

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anyAcceptedMinecraftVersionIsStoredWithinItsColumn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            int limit = TextLimits.limit(REPLAYS.MC_VERSION);
            Map<String, String> storedBySubmitted = new LinkedHashMap<>();
            storedBySubmitted.put("1.21.11", "1.21.11");
            storedBySubmitted.put("v".repeat(limit), "v".repeat(limit));
            storedBySubmitted.put("v".repeat(limit + 1), "v".repeat(limit));
            storedBySubmitted.put("v".repeat(10_000), "v".repeat(limit));
            storedBySubmitted.put(SUPPLEMENTARY.repeat(limit), SUPPLEMENTARY.repeat(limit));
            storedBySubmitted.put(SUPPLEMENTARY.repeat(limit + 1), SUPPLEMENTARY.repeat(limit));
            storedBySubmitted.put("1.21\u0000.11", "1.21.11");
            ReplayService service = service(database);
            JooqReplayRepository repository = new JooqReplayRepository(database.dsl(), database.jsonColumns());

            storedBySubmitted.forEach((submitted, stored) -> {
                String replayId = service.initUpload(server, submitted, FILE_SIZE, null, null).replayId();

                assertThat(repository.findById(replayId)).get().extracting(ReplayDocument::getMcVersion).isEqualTo(stored);
            });
        }
    }

    @SqlContractTest
    void twoConcurrentConfirmsValidateOnceAndBothEndConfirmed(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            ObjectStorage storage = storageServing(call -> {
                reads.incrementAndGet();
                reading.countDown();
                assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
                return new ByteArrayInputStream(ReplayFiles.minimal());
            });
            ReplayConfirmationService confirmations = confirmations(database, storage, mock(StorageDeletionService.class));
            String replayId = pendingReplay(database);
            ExecutorService first = Executors.newSingleThreadExecutor();
            try {
                Future<ReplayConfirmation> validating = first.submit(() -> confirmations.confirmUpload(server, replayId));
                assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();

                assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.TRY_AGAIN);

                proceed.countDown();
                assertThat(validating.get(10, TimeUnit.SECONDS)).isEqualTo(ReplayConfirmation.CONFIRMED);
            } finally {
                first.shutdownNow();
            }
            assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.CONFIRMED);
            assertThat(reads.get()).isEqualTo(1);
            assertThat(new JooqReplayRepository(database.dsl(), database.jsonColumns()).findById(replayId)).get()
                .extracting(ReplayDocument::getStatus).isEqualTo(ReplayDocument.STATUS_COMPLETE);
        }
    }

    @SqlContractTest
    void aValidatorWhoseLeaseRanOutRecordsNothing(DialectTarget target) throws Exception {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            ObjectStorage storage = storageServing(call -> {
                if (reads.incrementAndGet() == 1) {
                    reading.countDown();
                    assertThat(proceed.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return new ByteArrayInputStream(ReplayFiles.minimal());
            });
            StorageMetadataService metadata = recordingMetadata();
            ReplayConfirmationService confirmations = confirmations(database, storage, mock(StorageDeletionService.class), metadata);
            String replayId = pendingReplay(database);
            ExecutorService slow = Executors.newSingleThreadExecutor();
            try {
                Future<ReplayConfirmation> stale = slow.submit(() -> confirmations.confirmUpload(server, replayId));
                assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
                database.clock().advance(ReplayConfirmationService.VALIDATION_LEASE.plusSeconds(1));

                assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.CONFIRMED);

                proceed.countDown();
                assertThat(stale.get(10, TimeUnit.SECONDS)).isEqualTo(ReplayConfirmation.TRY_AGAIN);
            } finally {
                slow.shutdownNow();
            }
            verify(metadata, times(1)).confirmUpload(any(), anyString(), anyLong(), anyString());
        }
    }

    @SqlContractTest
    void aReadFailureKeepsTheObjectAndTheRowSoTheNextConfirmSucceeds(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AtomicInteger reads = new AtomicInteger();
            ObjectStorage storage = storageServing(call -> reads.incrementAndGet() == 1
                ? failingAfter(ReplayFiles.minimal(), 10)
                : new ByteArrayInputStream(ReplayFiles.minimal()));
            StorageDeletionService deletions = mock(StorageDeletionService.class);
            ReplayConfirmationService confirmations = confirmations(database, storage, deletions);
            String replayId = pendingReplay(database);

            assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.TRY_AGAIN);
            assertThat(new JooqReplayRepository(database.dsl(), database.jsonColumns()).findById(replayId)).get()
                .extracting(ReplayDocument::getStatus).isEqualTo(ReplayDocument.STATUS_PENDING);
            verifyNoInteractions(deletions);

            assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.CONFIRMED);
        }
    }

    @SqlContractTest
    void aFormatErrorFailsTheRowAndDiscardsTheObjectOnce(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            ObjectStorage storage = storageServing(call -> new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
            StorageDeletionService deletions = mock(StorageDeletionService.class);
            ReplayConfirmationService confirmations = confirmations(database, storage, deletions);
            String replayId = pendingReplay(database);

            assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.FILE_INVALID);
            assertThat(confirmations.confirmUpload(server, replayId)).isEqualTo(ReplayConfirmation.ALREADY_FAILED);

            assertThat(new JooqReplayRepository(database.dsl(), database.jsonColumns()).findById(replayId)).get()
                .extracting(ReplayDocument::getStatus).isEqualTo(ReplayDocument.STATUS_FAILED);
            verify(deletions, times(1)).discard(eq(server), anyString());
        }
    }

    private static ReplayService service(SqlTestDatabase database) {
        StorageLimitPolicy limits = mock(StorageLimitPolicy.class);
        when(limits.maxUploadBytes(MediaCategory.REPLAY)).thenReturn(FILE_SIZE);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.presignUpload(anyString(), anyString(), anyLong()))
            .thenReturn(new UploadTarget("/upload", "PUT", Map.of(), SqlTestDatabase.START.plusSeconds(600)));
        return new ReplayService(new JooqReplayRepository(database.dsl(), database.jsonColumns()), storage, limits,
            mock(BeanValidationRunner.class), database.unitOfWork(), database.clock());
    }

    private static ReplayConfirmationService confirmations(SqlTestDatabase database, ObjectStorage storage,
                                                           StorageDeletionService deletions) {
        return confirmations(database, storage, deletions, recordingMetadata());
    }

    private static StorageMetadataService recordingMetadata() {
        StorageMetadataService metadata = mock(StorageMetadataService.class);
        when(metadata.confirmUpload(any(), anyString(), anyLong(), anyString())).thenReturn(UploadConfirmation.RECORDED);
        return metadata;
    }

    private static ReplayConfirmationService confirmations(SqlTestDatabase database, ObjectStorage storage,
                                                           StorageDeletionService deletions, StorageMetadataService metadata) {
        return new ReplayConfirmationService(new JooqReplayRepository(database.dsl(), database.jsonColumns()), storage, metadata,
            deletions, new ReplayFileValidator(), new ReplayValidationGate(), database.unitOfWork(), database.clock());
    }

    private static ObjectStorage storageServing(Answer<InputStream> read) {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.stat(anyString())).thenAnswer(call -> Optional.of(
            new StoredObject(call.getArgument(0), FILE_SIZE, StoredObject.DEFAULT_CONTENT_TYPE, SqlTestDatabase.START)));
        when(storage.read(anyString())).thenAnswer(read);
        return storage;
    }

    private String pendingReplay(SqlTestDatabase database) {
        return service(database).initUpload(server, "1.21.11", FILE_SIZE, null, null).replayId();
    }

    private static InputStream failingAfter(byte[] file, int bytes) {
        return new FilterInputStream(new ByteArrayInputStream(file, 0, bytes)) {
            @Override
            public int read() throws IOException {
                return failAtEnd(super.read());
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return failAtEnd(super.read(buffer, offset, length));
            }

            private int failAtEnd(int read) throws IOException {
                if (read < 0) {
                    throw new IOException("connection reset");
                }
                return read;
            }
        };
    }
}
