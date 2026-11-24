package gg.modl.backend.storage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.storage.StorageContractFixture;
import gg.modl.backend.storage.StorageLimitPolicy;
import gg.modl.backend.storage.dto.request.EvidenceItemRequest;
import gg.modl.backend.storage.dto.request.SubmitEvidenceRequest;
import gg.modl.backend.storage.repository.JooqEvidenceUploadTokenRepository;
import gg.modl.backend.storage.service.EvidenceUploadService.SubmitEvidenceResult;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.io.TempDir;

class EvidenceSubmissionContractTest {
    private static final String KEY = "modl_instance/evidence/PUN12345/file.png";
    private static final String URL = "/v1/storage/objects/" + KEY;
    private static final SubmitEvidenceRequest SUBMISSION =
        new SubmitEvidenceRequest(List.of(new EvidenceItemRequest(URL, "file.png", "image/png", 42L)));

    @TempDir
    Path directory;

    private final PunishmentEvidenceService punishmentEvidence = mock(PunishmentEvidenceService.class);
    private EvidenceUploadTokenService tokens;

    @SqlContractTest
    void aSubmissionAttachesTheFileAndInvalidatesTheToken(DialectTarget target) {
        try (StorageContractFixture storage = start(target, new RetryInjection())) {
            EvidenceUploadService service = service(storage);
            String token = tokens.createToken(storage.server(), "PUN12345", "11111111-2222-3333-4444-555555555555", "Moderator");
            whenAdded(PunishmentOperationStatus.SUCCESS);

            assertThat(service.submitEvidence(token, SUBMISSION)).isInstanceOf(SubmitEvidenceResult.Success.class);

            assertThat(storage.unattachedExpiryOf(KEY)).isNull();
            assertThat(tokens.validateToken(token)).isNull();
        }
    }

    @SqlContractTest
    void aMissingPunishmentLeavesTheFileUnattachedAndTheTokenValid(DialectTarget target) {
        try (StorageContractFixture storage = start(target, WriteAttemptHook.NONE)) {
            EvidenceUploadService service = service(storage);
            String token = tokens.createToken(storage.server(), "PUN12345", "11111111-2222-3333-4444-555555555555", "Moderator");
            whenAdded(PunishmentOperationStatus.NOT_FOUND);

            assertThat(service.submitEvidence(token, SUBMISSION)).isInstanceOf(SubmitEvidenceResult.PunishmentNotFound.class);

            assertThat(storage.unattachedExpiryOf(KEY)).isNotNull();
            assertThat(tokens.validateToken(token)).isNotNull();
        }
    }

    @SqlContractTest
    void aFileRemovedDuringTheSubmissionRollsTheWholeSubmissionBack(DialectTarget target) {
        try (StorageContractFixture storage = start(target, WriteAttemptHook.NONE)) {
            EvidenceUploadService service = service(storage);
            String token = tokens.createToken(storage.server(), "PUN12345", "11111111-2222-3333-4444-555555555555", "Moderator");
            when(punishmentEvidence.addUploadedEvidence(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                storage.files().deleteByKeys(List.of(KEY));
                return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "ok", true, 1);
            });

            assertThatThrownBy(() -> service.submitEvidence(token, SUBMISSION)).isInstanceOf(ConcurrentUpdateException.class);

            assertThat(storage.hasFile(KEY)).isTrue();
            assertThat(tokens.validateToken(token)).isNotNull();
        }
    }

    @SqlContractTest
    void ofTwoConcurrentSubmissionsExactlyOneSucceeds(DialectTarget target) throws Exception {
        try (StorageContractFixture storage = start(target, WriteAttemptHook.NONE);
             ExecutorService submitters = Executors.newFixedThreadPool(2)) {
            EvidenceUploadService service = service(storage);
            String token = tokens.createToken(storage.server(), "PUN12345", "11111111-2222-3333-4444-555555555555", "Moderator");
            whenAdded(PunishmentOperationStatus.SUCCESS);
            Callable<SubmitEvidenceResult> submit = () -> service.submitEvidence(token, SUBMISSION);

            List<SubmitEvidenceResult> results = new ArrayList<>();
            for (Future<SubmitEvidenceResult> result : submitters.invokeAll(List.of(submit, submit))) {
                results.add(result.get());
            }

            assertThat(results).containsExactlyInAnyOrder(new SubmitEvidenceResult.Success(), new SubmitEvidenceResult.InvalidToken());
        }
    }

    private StorageContractFixture start(DialectTarget target, WriteAttemptHook attemptHook) {
        StorageContractFixture storage = StorageContractFixture.start(target, directory, attemptHook);
        storage.file(KEY, 42, StorageContractFixture.START,
            StorageContractFixture.START.plus(StorageContractFixture.UNATTACHED_TTL));
        tokens = new EvidenceUploadTokenService(
            new JooqEvidenceUploadTokenRepository(storage.database().dsl(), storage.database().unitOfWork()),
            storage.database().clock());
        return storage;
    }

    private void whenAdded(PunishmentOperationStatus status) {
        when(punishmentEvidence.addUploadedEvidence(any(), any(), any(), any(), any()))
            .thenReturn(new PunishmentOperationResult(status, "result", status == PunishmentOperationStatus.SUCCESS, 1));
    }

    private EvidenceUploadService service(StorageContractFixture storage) {
        InstanceContext instance = mock(InstanceContext.class);
        when(instance.instance()).thenReturn(storage.server());
        StoredObjectUrlResolver resolver = mock(StoredObjectUrlResolver.class);
        when(resolver.keyOf(URL)).thenReturn(Optional.of(KEY));
        return new EvidenceUploadService(tokens, mock(PlayerRepository.class), instance,
            new MediaValidationService(mock(StorageLimitPolicy.class)), punishmentEvidence, storage.metadata(),
            mock(UploadOrchestrationService.class), resolver, storage.files(), storage.database().unitOfWork());
    }
}
