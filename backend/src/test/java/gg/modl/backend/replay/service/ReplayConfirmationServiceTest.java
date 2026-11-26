package gg.modl.backend.replay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayUploadClaim;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.UploadConfirmation;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayConfirmationServiceTest {
    private static final Instant NOW = Instant.parse("2026-05-18T11:00:00Z");
    private static final String KEY = "db/replays/replay-1.modlreplay";
    private static final ReplayUploadClaim CLAIM = new ReplayUploadClaim("replay-1", NOW.plus(ReplayConfirmationService.VALIDATION_LEASE));

    @Mock
    private ReplayRepository replayRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private StorageMetadataService storageMetadataService;

    @Mock
    private StorageDeletionService storageDeletionService;

    private final Server server = TestServers.server("server", "admin@example.com");
    private final ReplayValidationGate gate = new ReplayValidationGate();
    private ReplayConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new ReplayConfirmationService(replayRepository, objectStorage, storageMetadataService, storageDeletionService,
            new ReplayFileValidator(), gate, new ImmediateUnitOfWork(), new MutableClock(NOW));
        lenient().when(replayRepository.findById("replay-1")).thenReturn(Optional.of(replay(ReplayDocument.STATUS_PENDING)));
        lenient().when(replayRepository.claimUpload(CLAIM, NOW)).thenReturn(true);
        lenient().when(replayRepository.failUpload(CLAIM)).thenReturn(true);
        lenient().when(replayRepository.holdClaim(CLAIM)).thenReturn(true);
        lenient().when(replayRepository.completeUpload(CLAIM, 1500L, 5)).thenReturn(true);
    }

    @Test
    void aValidUploadIsCompletedByItsClaim() {
        stored(ReplayFiles.minimal());
        when(storageMetadataService.confirmUpload(server, KEY, 1500L, "application/octet-stream")).thenReturn(UploadConfirmation.RECORDED);

        assertEquals(ReplayConfirmation.CONFIRMED, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).completeUpload(CLAIM, 1500L, 5);
        verify(storageDeletionService, never()).discard(any(), anyString());
    }

    @Test
    void aRepeatedConfirmOfACompletedReplayIsConfirmedWithoutValidating() {
        when(replayRepository.findById("replay-1")).thenReturn(Optional.of(replay(ReplayDocument.STATUS_COMPLETE)));

        assertEquals(ReplayConfirmation.CONFIRMED, service.confirmUpload(server, "replay-1"));
        verify(replayRepository, never()).claimUpload(any(), any());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void aConfirmOfARefusedUploadStaysRefused() {
        when(replayRepository.findById("replay-1")).thenReturn(Optional.of(replay(ReplayDocument.STATUS_FAILED)));

        assertEquals(ReplayConfirmation.ALREADY_FAILED, service.confirmUpload(server, "replay-1"));
        verifyNoInteractions(objectStorage);
    }

    @Test
    void anUploadAnotherConfirmIsValidatingIsLeftToItAndRetriedLater() {
        when(replayRepository.claimUpload(CLAIM, NOW)).thenReturn(false);

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verifyNoInteractions(objectStorage, storageDeletionService);
    }

    @Test
    void aClaimLostBeforeRecordingLeavesTheUploadUnrecorded() {
        stored(ReplayFiles.minimal());
        when(replayRepository.holdClaim(CLAIM)).thenReturn(false);

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verify(storageMetadataService, never()).confirmUpload(any(), anyString(), anyLong(), anyString());
        verify(replayRepository, never()).completeUpload(any(), anyLong(), anyInt());
        verifyNoInteractions(storageDeletionService);
    }

    @Test
    void aFormatErrorFailsTheReplayAndDiscardsTheObject() {
        stored(new byte[]{1, 2, 3, 4});

        assertEquals(ReplayConfirmation.FILE_INVALID, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).failUpload(CLAIM);
        verify(storageDeletionService).discard(server, KEY);
        verify(storageMetadataService, never()).confirmUpload(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void onlyTheWinnerOfTheMoveToFailedDiscardsTheObject() {
        stored(new byte[]{1, 2, 3, 4});
        when(replayRepository.failUpload(CLAIM)).thenReturn(false);

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verify(storageDeletionService, never()).discard(any(), anyString());
    }

    @Test
    void aReadFailureLeavesTheObjectAndReleasesTheClaim() {
        when(objectStorage.stat(KEY)).thenReturn(Optional.of(new StoredObject(KEY, 1500L, "application/octet-stream", NOW)));
        when(objectStorage.read(KEY)).thenReturn(failingAfter(ReplayFiles.minimal(), 10));

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).releaseUpload(CLAIM);
        verify(replayRepository, never()).failUpload(any());
        verifyNoInteractions(storageDeletionService);
    }

    @Test
    void aStorageOutageLeavesTheObjectAndReleasesTheClaim() {
        when(objectStorage.stat(KEY)).thenThrow(new UncheckedIOException(new IOException("bucket unreachable")));

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).releaseUpload(CLAIM);
        verifyNoInteractions(storageDeletionService);
    }

    @Test
    void aFullValidationGateAnswersTryAgainWithoutClaiming() {
        Optional<ReplayValidationGate.Permit> first = gate.tryEnter();
        Optional<ReplayValidationGate.Permit> second = gate.tryEnter();

        assertEquals(ReplayConfirmation.TRY_AGAIN, service.confirmUpload(server, "replay-1"));
        verify(replayRepository, never()).claimUpload(any(), any());

        first.orElseThrow().close();
        second.orElseThrow().close();
        assertTrue(gate.tryEnter().isPresent());
    }

    @Test
    void aConfirmAfterTheReservationLapsedFailsAndDiscards() {
        stored(ReplayFiles.minimal());
        when(storageMetadataService.confirmUpload(server, KEY, 1500L, "application/octet-stream")).thenReturn(UploadConfirmation.EXPIRED);

        assertEquals(ReplayConfirmation.RESERVATION_LAPSED, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).failUpload(CLAIM);
        verify(storageDeletionService).discard(server, KEY);
    }

    @Test
    void aMissingObjectFailsTheReplay() {
        when(objectStorage.stat(KEY)).thenReturn(Optional.empty());

        assertEquals(ReplayConfirmation.UPLOAD_MISSING, service.confirmUpload(server, "replay-1"));
        verify(replayRepository).failUpload(CLAIM);
        verifyNoInteractions(storageDeletionService);
    }

    @Test
    void anIdBeyondTheColumnMatchesNothing() {
        assertEquals(ReplayConfirmation.NOT_FOUND, service.confirmUpload(server, "x".repeat(65)));
        verify(replayRepository, never()).findById(anyString());
    }

    private void stored(byte[] file) {
        when(objectStorage.stat(KEY)).thenReturn(Optional.of(new StoredObject(KEY, 1500L, "application/octet-stream", NOW)));
        when(objectStorage.read(KEY)).thenReturn(new ByteArrayInputStream(file));
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

    private static ReplayDocument replay(String status) {
        ReplayDocument replay = new ReplayDocument();
        replay.setId("replay-1");
        replay.setStorageKey(KEY);
        replay.setFileSize(2048L);
        replay.setStatus(status);
        return replay;
    }
}
