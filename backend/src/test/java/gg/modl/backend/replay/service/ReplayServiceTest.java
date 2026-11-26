package gg.modl.backend.replay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.BeanValidationRunner;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.dto.InitReplayUploadResponse;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-05-18T11:00:00Z");
    private static final long MAX_REPLAY_BYTES = 10L * 1024 * 1024;
    private static final String TARGET_UUID = "3f8c9c5a-6b6e-4f2c-9b7f-1a2b3c4d5e6f";

    @Mock
    private ReplayRepository replayRepository;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private StorageLimitPolicy storageLimitPolicy;

    private static final Instant UPLOAD_DEADLINE = Instant.parse("2026-05-18T12:00:00Z");

    private static Validator validator;
    private final Server server = TestServers.server("server", "admin@example.com");
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        if (validator == null) {
            validator = Validation.buildDefaultValidatorFactory().getValidator();
        }
        replayService = new ReplayService(replayRepository, objectStorage, storageLimitPolicy, new BeanValidationRunner(validator),
            new ImmediateUnitOfWork(), new MutableClock(NOW));
        lenient().when(storageLimitPolicy.maxUploadBytes(MediaCategory.REPLAY)).thenReturn(MAX_REPLAY_BYTES);
        lenient().when(storageLimitPolicy.uploadDeadline()).thenReturn(UPLOAD_DEADLINE);
    }

    @Test
    void initUploadRejectsReplaysWhenStorageIsFull() {
        doThrow(new StorageLimitExceededException("full")).when(storageLimitPolicy)
            .reserve(anyString(), eq(1024L), eq(UPLOAD_DEADLINE));

        assertThrows(StorageLimitExceededException.class, () -> replayService.initUpload(server, "1.21.4", 1024L, null, null));
        verifyNoInteractions(replayRepository, objectStorage);
    }

    @Test
    void initUploadRejectsFilesThatExceedTheReplayLimit() {
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> replayService.initUpload(server, "1.21.4", MAX_REPLAY_BYTES + 1, null, null)
        );

        assertEquals("Replay size must be between 1 byte and 10 MB", exception.getMessage());
        verifyNoInteractions(objectStorage, replayRepository);
    }

    @Test
    void initUploadPersistsTargetMetadataAndReturnsTheUploadTarget() {
        when(objectStorage.presignUpload(anyString(), eq("application/octet-stream"), eq(2048L)))
            .thenReturn(new UploadTarget("/v1/storage/objects/db/replays/x.modlreplay?sig=1", "PUT",
                Map.of("Content-Type", "application/octet-stream"), Instant.parse("2026-05-18T12:00:00Z")));

        InitReplayUploadResponse response = replayService.initUpload(server, "1.21.4", 2048L, " " + TARGET_UUID + " ", " TargetName ");

        ArgumentCaptor<ReplayDocument> replayCaptor = ArgumentCaptor.forClass(ReplayDocument.class);
        verify(replayRepository).insert(replayCaptor.capture());
        ReplayDocument saved = replayCaptor.getValue();
        verify(storageLimitPolicy).reserve(saved.getStorageKey(), 2048L, Instant.parse("2026-05-18T12:00:00Z"));
        assertEquals(TARGET_UUID, saved.getTargetUuid());
        assertEquals("TargetName", saved.getTargetName());
        assertEquals(ReplayDocument.STATUS_PENDING, saved.getStatus());
        assertEquals(NOW, saved.getCreatedAt());
        assertTrue(saved.getStorageKey().contains("/replays/"));
        assertEquals("/v1/storage/objects/db/replays/x.modlreplay?sig=1", response.uploadUrl());
    }

    @Test
    void labelsAreSavedOnlyOnCompleteReplays() {
        when(replayRepository.findById("replay-1")).thenReturn(Optional.of(pendingReplay()));

        assertEquals(ReplayService.SubmitLabelsResult.NOT_FOUND, replayService.submitLabels(server, "replay-1", List.of()));
        verify(replayRepository, never()).replaceLabels(anyString(), any());
    }

    @Test
    void labelsOnACompleteReplayAreStored() {
        ReplayDocument complete = pendingReplay();
        complete.setStatus(ReplayDocument.STATUS_COMPLETE);
        when(replayRepository.findById("replay-1")).thenReturn(Optional.of(complete));
        when(replayRepository.replaceLabels("replay-1", List.of())).thenReturn(Optional.of(complete));

        assertEquals(ReplayService.SubmitLabelsResult.OK, replayService.submitLabels(server, "replay-1", List.of()));
    }

    @Test
    void initUploadRejectsInvalidTargetUuid() {
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> replayService.initUpload(server, "1.21.4", 2048L, "not-a-uuid", "TargetName")
        );

        assertEquals("Target UUID must be a valid UUID", exception.getMessage());
        verifyNoInteractions(objectStorage, replayRepository);
    }

    @Test
    void initUploadRejectsLongTargetName() {
        ValidationException exception = assertThrows(
            ValidationException.class,
            () -> replayService.initUpload(server, "1.21.4", 2048L, TARGET_UUID, "x".repeat(17))
        );

        assertEquals("Target name is too long", exception.getMessage());
        verifyNoInteractions(objectStorage, replayRepository);
    }

    private static ReplayDocument pendingReplay() {
        ReplayDocument replay = new ReplayDocument();
        replay.setId("replay-1");
        replay.setStorageKey("db/replays/replay-1.modlreplay");
        replay.setFileSize(2048L);
        replay.setStatus(ReplayDocument.STATUS_PENDING);
        return replay;
    }
}
