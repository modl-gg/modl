package gg.modl.backend.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.dto.request.EvidenceConfirmUploadRequest;
import gg.modl.backend.storage.dto.request.EvidenceItemRequest;
import gg.modl.backend.storage.dto.request.EvidencePresignUploadRequest;
import gg.modl.backend.storage.dto.request.SubmitEvidenceRequest;
import gg.modl.backend.storage.dto.response.UploadResponse;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EvidenceUploadServiceTest {
    private static final String EVIDENCE_KEY = "modl_instance/evidence/PUN-1/file.png";
    private static final String EVIDENCE_URL = "/v1/storage/objects/" + EVIDENCE_KEY;

    @Mock
    private EvidenceUploadTokenService tokenService;

    @Mock
    private PlayerRepository playerRepository;

    @Mock
    private InstanceContext instanceContext;

    @Mock
    private PunishmentEvidenceService punishmentEvidenceService;

    @Mock
    private StorageMetadataService storageMetadataService;

    @Mock
    private UploadOrchestrationService uploadOrchestrationService;

    @Mock
    private StoredObjectUrlResolver storedObjectUrlResolver;

    @Mock
    private StorageFileRepository storageFileRepository;

    @Mock
    private UnitOfWork unitOfWork;

    private final Server server = TestServers.server("server", "admin@example.com");
    private EvidenceUploadService evidenceUploadService;

    @BeforeEach
    void setUp() {
        evidenceUploadService = new EvidenceUploadService(
            tokenService,
            playerRepository,
            instanceContext,
            new MediaValidationService(mock(StorageLimitPolicy.class)),
            punishmentEvidenceService,
            storageMetadataService,
            uploadOrchestrationService,
            storedObjectUrlResolver,
            storageFileRepository,
            unitOfWork
        );
        lenient().when(unitOfWork.write(ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation.<Supplier<Object>>getArgument(0).get());
        lenient().when(tokenService.validateToken("token-1")).thenReturn(uploadToken());
        lenient().when(tokenService.invalidateToken("token-1")).thenReturn(true);
        lenient().when(instanceContext.instance()).thenReturn(server);
    }

    @Test
    void submitEvidenceDelegatesPunishmentMutationAndInvalidatesToken() {
        when(storedObjectUrlResolver.keyOf(EVIDENCE_URL)).thenReturn(Optional.of(EVIDENCE_KEY));
        when(storageMetadataService.findConfirmedFiles(eq(server), any()))
            .thenReturn(Map.of(EVIDENCE_KEY, new StorageFile(EVIDENCE_KEY, "file.png", 42L, "image/png", "evidence", Instant.now())));
        when(punishmentEvidenceService.addUploadedEvidence(eq(server), eq("PUN-1"), eq("Moderator"), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "ok", true, 1));

        when(storageFileRepository.markAttached(Set.of(EVIDENCE_KEY))).thenReturn(1);

        EvidenceUploadService.SubmitEvidenceResult result = evidenceUploadService.submitEvidence(
            "token-1",
            new SubmitEvidenceRequest(List.of(new EvidenceItemRequest(EVIDENCE_URL, "file.png", "image/png", 42L)))
        );

        assertInstanceOf(EvidenceUploadService.SubmitEvidenceResult.Success.class, result);
        verify(punishmentEvidenceService).addUploadedEvidence(eq(server), eq("PUN-1"), eq("Moderator"), any(), any());
        verify(tokenService).invalidateToken("token-1");
    }

    @Test
    void aSubmissionThatLosesTheTokenRaisesAConflictSoItsUnitRollsBack() {
        when(storedObjectUrlResolver.keyOf(EVIDENCE_URL)).thenReturn(Optional.of(EVIDENCE_KEY));
        when(storageMetadataService.findConfirmedFiles(eq(server), any()))
            .thenReturn(Map.of(EVIDENCE_KEY, new StorageFile(EVIDENCE_KEY, "file.png", 42L, "image/png", "evidence", Instant.now())));
        when(punishmentEvidenceService.addUploadedEvidence(eq(server), eq("PUN-1"), eq("Moderator"), any(), any()))
            .thenReturn(new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "ok", true, 1));
        when(storageFileRepository.markAttached(Set.of(EVIDENCE_KEY))).thenReturn(1);
        when(tokenService.invalidateToken("token-1")).thenReturn(false);
        SubmitEvidenceRequest submission =
            new SubmitEvidenceRequest(List.of(new EvidenceItemRequest(EVIDENCE_URL, "file.png", "image/png", 42L)));

        assertThrows(ConcurrentUpdateException.class, () -> evidenceUploadService.submitEvidence("token-1", submission));
    }

    @Test
    void submitEvidenceRejectsUrlsOutsideThePunishmentScope() {
        String foreignUrl = "/v1/storage/objects/modl_instance/evidence/OTHER/file.png";
        when(storedObjectUrlResolver.keyOf(foreignUrl)).thenReturn(Optional.of("modl_instance/evidence/OTHER/file.png"));

        EvidenceUploadService.SubmitEvidenceResult result = evidenceUploadService.submitEvidence(
            "token-1",
            new SubmitEvidenceRequest(List.of(new EvidenceItemRequest(foreignUrl, "file.png", "image/png", 42L)))
        );

        assertInstanceOf(EvidenceUploadService.SubmitEvidenceResult.InvalidUrl.class, result);
        verify(tokenService, never()).invalidateToken(any());
    }

    @Test
    void confirmUploadReturnsSuccessWithDetailsFromOrchestration() {
        UploadResponse details = new UploadResponse(EVIDENCE_KEY, EVIDENCE_URL, "file.png", 42L, "image/png");
        when(uploadOrchestrationService.confirm(server, EVIDENCE_KEY))
            .thenReturn(UploadOrchestrationService.ConfirmOutcome.success(details));

        EvidenceUploadService.ConfirmUploadResult result =
            evidenceUploadService.confirmUpload("token-1", new EvidenceConfirmUploadRequest(EVIDENCE_KEY));

        EvidenceUploadService.ConfirmUploadResult.Orchestrated orchestrated =
            assertInstanceOf(EvidenceUploadService.ConfirmUploadResult.Orchestrated.class, result);
        assertEquals(UploadOrchestrationService.ConfirmStatus.SUCCESS, orchestrated.outcome().status());
        assertEquals(details, orchestrated.outcome().upload());
    }

    @Test
    void confirmUploadRejectsKeyOutsidePunishmentScopeWithoutOrchestrating() {
        EvidenceUploadService.ConfirmUploadResult result =
            evidenceUploadService.confirmUpload("token-1", new EvidenceConfirmUploadRequest("modl_instance/evidence/OTHER-PUN/file.png"));

        assertInstanceOf(EvidenceUploadService.ConfirmUploadResult.InvalidKey.class, result);
        verify(uploadOrchestrationService, never()).confirm(any(), any());
    }

    @Test
    void presignUploadMapsStorageFullOutcomeFromOrchestration() {
        when(uploadOrchestrationService.presign(eq(server), any()))
            .thenReturn(new UploadOrchestrationService.PresignOutcome(
                UploadOrchestrationService.PresignStatus.STORAGE_FULL, "The storage limit of 1 GB has been reached", null));

        EvidenceUploadService.PresignUploadResult result =
            evidenceUploadService.presignUpload("token-1", new EvidencePresignUploadRequest("file.png", "image/png", 42L));

        EvidenceUploadService.PresignUploadResult.Orchestrated orchestrated =
            assertInstanceOf(EvidenceUploadService.PresignUploadResult.Orchestrated.class, result);
        assertEquals(UploadOrchestrationService.PresignStatus.STORAGE_FULL, orchestrated.outcome().status());
    }

    @Test
    void keysOutsideTheStorageNamespaceAreRejected() {
        EvidenceUploadService.ConfirmUploadResult result =
            evidenceUploadService.confirmUpload("token-1", new EvidenceConfirmUploadRequest("other/evidence/PUN-1/file.png"));

        assertInstanceOf(EvidenceUploadService.ConfirmUploadResult.InvalidKey.class, result);
    }

    private static EvidenceUploadTokenService.UploadToken uploadToken() {
        return new EvidenceUploadTokenService.UploadToken("token-1", "PUN-1", "player-1", "Moderator", Instant.now());
    }
}
