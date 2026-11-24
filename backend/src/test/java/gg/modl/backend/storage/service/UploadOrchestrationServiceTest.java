package gg.modl.backend.storage.service;

import org.mockito.ArgumentCaptor;
import gg.modl.backend.storage.PublicUpload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StorageLimitExceededException;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.TemporaryUploadLimitExceededException;
import gg.modl.backend.storage.UploadTarget;
import gg.modl.backend.storage.local.LocalObjectStorage;
import gg.modl.backend.storage.local.UploadSigner;
import gg.modl.backend.support.TestServers;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UploadOrchestrationServiceTest {
    private static final String KEY = "modl_instance/evidence/PUN-1/file.png";
    private static final Instant EXPIRES_AT = Instant.parse("2030-01-01T00:00:00Z");

    @Mock
    private MediaValidationService validationService;

    @Mock
    private ObjectStorage objectStorage;

    @Mock
    private StorageMetadataService storageMetadataService;

    @Mock
    private StorageLimitPolicy storageLimitPolicy;

    private final Server server = TestServers.server("server", "admin@example.com");
    private UploadOrchestrationService uploadOrchestrationService;

    @BeforeEach
    void setUp() {
        uploadOrchestrationService = new UploadOrchestrationService(
            validationService, objectStorage, storageMetadataService, storageLimitPolicy);
        lenient().when(storageLimitPolicy.uploadDeadline()).thenReturn(EXPIRES_AT);
    }

    @Test
    void presignReservesTheDeclaredSizeAndReturnsTheStorageUploadTarget() {
        allowMetadata("evidence");
        stubPresign();

        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(server, request());

        assertEquals(UploadOrchestrationService.PresignStatus.SUCCESS, outcome.status());
        assertEquals("/v1/storage/objects/x?sig=1", outcome.upload().presignedUrl());
        assertEquals("PUT", outcome.upload().method());
        assertEquals(EXPIRES_AT, outcome.upload().expiresAt());
        assertTrue(outcome.upload().key().startsWith("modl_instance/evidence/PUN-1/"));
        verify(storageLimitPolicy).reserve(outcome.upload().key(), 42L, EXPIRES_AT);
    }

    @Test
    void presignRejectsWhenMetadataValidationFails() {
        when(validationService.rejectionOf("file.png", "image/png", 42L, "evidence"))
            .thenReturn(Optional.of("File type not allowed"));

        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(server, request());

        assertEquals(UploadOrchestrationService.PresignStatus.VALIDATION_FAILED, outcome.status());
        verify(objectStorage, never()).presignUpload(anyString(), anyString(), anyLong());
    }

    @Test
    void presignReportsAFullStorageWithoutReturningATarget() {
        allowMetadata("evidence");
        doThrow(new StorageLimitExceededException("full")).when(storageLimitPolicy).reserve(anyString(), eq(42L), eq(EXPIRES_AT));

        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(server, request());

        assertEquals(UploadOrchestrationService.PresignStatus.STORAGE_FULL, outcome.status());
        assertEquals("full", outcome.message());
        assertNull(outcome.upload());
        verify(objectStorage, never()).presignUpload(anyString(), anyString(), anyLong());
    }

    @Test
    void aPresignThatFailsReleasesItsReservation() {
        allowMetadata("evidence");
        when(objectStorage.presignUpload(anyString(), anyString(), anyLong())).thenThrow(new IllegalStateException("signer down"));

        assertThrows(IllegalStateException.class, () -> uploadOrchestrationService.presign(server, request()));

        ArgumentCaptor<String> reserved = ArgumentCaptor.forClass(String.class);
        verify(storageLimitPolicy).reserve(reserved.capture(), eq(42L), eq(EXPIRES_AT));
        verify(storageLimitPolicy).cancel(reserved.getValue());
    }

    @Test
    void everyPublicPresignUsesThePublicBudgetOfItsRecordAndClient() {
        allowMetadata("ticket");
        doThrow(new TemporaryUploadLimitExceededException()).when(storageLimitPolicy)
            .reservePublic(anyString(), eq(42L), eq(EXPIRES_AT), any(PublicUpload.class));

        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(server,
            new UploadOrchestrationService.UploadPresignRequest("ticket", "file.png", "image/png", 42L, "SUPPORT-1", "203.0.113.7"));

        assertEquals(UploadOrchestrationService.PresignStatus.TEMP_LIMIT_EXCEEDED, outcome.status());
        assertNull(outcome.upload());
        verify(storageLimitPolicy, never()).reserve(anyString(), anyLong(), any());
        ArgumentCaptor<PublicUpload> upload = ArgumentCaptor.forClass(PublicUpload.class);
        verify(storageLimitPolicy).reservePublic(anyString(), eq(42L), eq(EXPIRES_AT), upload.capture());
        assertEquals("203.0.113.7", upload.getValue().clientNetwork());
        assertEquals("modl_instance/tickets/SUPPORT-1/", upload.getValue().recordPrefix());
    }

    @Test
    void confirmRecordsTheStoredObjectAndReturnsItsPublicUrl() {
        when(objectStorage.stat(KEY)).thenReturn(Optional.of(new StoredObject(KEY, 42L, "image/png", Instant.now())));
        when(storageMetadataService.confirmUpload(server, KEY, 42L, "image/png")).thenReturn(UploadConfirmation.RECORDED);
        when(objectStorage.publicUrl(KEY)).thenReturn("/v1/storage/objects/" + KEY);

        UploadOrchestrationService.ConfirmOutcome outcome = uploadOrchestrationService.confirm(server, KEY);

        assertEquals(UploadOrchestrationService.ConfirmStatus.SUCCESS, outcome.status());
        assertEquals("/v1/storage/objects/" + KEY, outcome.upload().url());
        assertEquals(42L, outcome.upload().size());
        assertEquals("file.png", outcome.upload().fileName());
    }

    @Test
    void confirmReturnsUploadNotFoundWhenObjectMissing() {
        when(objectStorage.stat(KEY)).thenReturn(Optional.empty());

        UploadOrchestrationService.ConfirmOutcome outcome = uploadOrchestrationService.confirm(server, KEY);

        assertEquals(UploadOrchestrationService.ConfirmStatus.UPLOAD_NOT_FOUND, outcome.status());
        verify(storageMetadataService, never()).confirmUpload(any(), anyString(), anyLong(), anyString());
    }

    @Test
    void anExpiredOrUnmatchedConfirmationLeavesTheObjectToReclamation() {
        when(objectStorage.stat(KEY)).thenReturn(Optional.of(new StoredObject(KEY, 42L, "image/png", Instant.now())));
        when(storageMetadataService.confirmUpload(server, KEY, 42L, "image/png")).thenReturn(UploadConfirmation.EXPIRED);

        UploadOrchestrationService.ConfirmOutcome outcome = uploadOrchestrationService.confirm(server, KEY);

        assertEquals(UploadOrchestrationService.ConfirmStatus.UPLOAD_NOT_FOUND, outcome.status());
        verify(objectStorage, never()).delete(anyString());
        verify(objectStorage, never()).deleteAll(anyCollection());
    }

    @Test
    void confirmingAnotherCaseOfARecordedKeyFindsNothingAndDeletesNothing(@TempDir Path root) {
        LocalObjectStorage localStorage = new LocalObjectStorage(root, new UploadSigner(() -> "signing-key"), Clock.systemUTC());
        String recorded = "modl_instance/tickets/new/Shot.PNG";
        localStorage.write(recorded, new ByteArrayInputStream(new byte[] {1}), 1, "image/png");
        UploadOrchestrationService service =
            new UploadOrchestrationService(validationService, localStorage, storageMetadataService, storageLimitPolicy);

        UploadOrchestrationService.ConfirmOutcome outcome = service.confirm(server, "modl_instance/tickets/new/shot.png");

        assertEquals(UploadOrchestrationService.ConfirmStatus.UPLOAD_NOT_FOUND, outcome.status());
        assertTrue(localStorage.exists(recorded));
        verify(storageMetadataService, never()).confirmUpload(any(), anyString(), anyLong(), anyString());
    }

    private void allowMetadata(String uploadType) {
        when(validationService.rejectionOf("file.png", "image/png", 42L, uploadType))
            .thenReturn(Optional.empty());
    }

    private void stubPresign() {
        when(objectStorage.presignUpload(anyString(), eq("image/png"), eq(42L)))
            .thenReturn(new UploadTarget("/v1/storage/objects/x?sig=1", "PUT", Map.of("Content-Type", "image/png"), EXPIRES_AT));
    }

    private static UploadOrchestrationService.UploadPresignRequest request() {
        return new UploadOrchestrationService.UploadPresignRequest("evidence", "file.png", "image/png", 42L, "PUN-1", null);
    }
}
