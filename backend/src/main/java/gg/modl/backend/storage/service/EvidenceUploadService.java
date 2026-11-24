package gg.modl.backend.storage.service;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.PlayerDataUtils;
import gg.modl.backend.player.service.PunishmentEvidenceService;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.player.service.PunishmentQueryService.UploadedEvidenceItem;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import gg.modl.backend.storage.StorageNamespace;
import gg.modl.backend.storage.dto.request.EvidenceConfirmUploadRequest;
import gg.modl.backend.storage.dto.request.EvidenceItemRequest;
import gg.modl.backend.storage.dto.request.EvidencePresignUploadRequest;
import gg.modl.backend.storage.dto.request.SubmitEvidenceRequest;
import gg.modl.backend.storage.repository.StorageFile;
import gg.modl.backend.storage.repository.StorageFileRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvidenceUploadService {
    private static final String UNKNOWN_PLAYER = "Unknown";

    private final EvidenceUploadTokenService tokenService;
    private final PlayerRepository playerRepository;
    private final InstanceContext instanceContext;
    private final MediaValidationService validationService;
    private final PunishmentEvidenceService punishmentEvidenceService;
    private final StorageMetadataService storageMetadataService;
    private final UploadOrchestrationService uploadOrchestrationService;
    private final StoredObjectUrlResolver storedObjectUrlResolver;
    private final StorageFileRepository storageFileRepository;
    private final UnitOfWork unitOfWork;

    public Optional<TokenInfo> validateToken(String token) {
        Optional<AuthorizedUpload> upload = authorize(token);
        if (upload.isEmpty()) {
            return Optional.empty();
        }
        EvidenceUploadTokenService.UploadToken uploadToken = upload.get().token();
        String playerName = playerRepository.findByMinecraftUuid(UuidUtils.normalize(uploadToken.playerUuid()), PlayerParts.USERNAMES)
            .map(player -> PlayerDataUtils.extractLatestUsername(player.getUsernames()))
            .orElse(UNKNOWN_PLAYER);
        return Optional.of(new TokenInfo(uploadToken.punishmentId(), playerName, uploadToken.issuerName(),
            validationService.maxUploadBytes(MediaCategory.EVIDENCE)));
    }

    public PresignUploadResult presignUpload(String token, EvidencePresignUploadRequest request) {
        Optional<AuthorizedUpload> upload = authorize(token);
        if (upload.isEmpty()) {
            return new PresignUploadResult.InvalidToken();
        }
        UploadOrchestrationService.PresignOutcome outcome = uploadOrchestrationService.presign(upload.get().server(),
            new UploadOrchestrationService.UploadPresignRequest(
                MediaCategory.EVIDENCE.uploadType(),
                request.fileName(),
                request.contentType(),
                request.fileSize(),
                upload.get().token().punishmentId(),
                null
            ));
        return new PresignUploadResult.Orchestrated(outcome);
    }

    public ConfirmUploadResult confirmUpload(String token, EvidenceConfirmUploadRequest request) {
        Optional<AuthorizedUpload> upload = authorize(token);
        if (upload.isEmpty()) {
            return new ConfirmUploadResult.InvalidToken();
        }
        if (!ownsKey(upload.get(), request.key())) {
            return new ConfirmUploadResult.InvalidKey();
        }
        return new ConfirmUploadResult.Orchestrated(uploadOrchestrationService.confirm(upload.get().server(), request.key()));
    }

    public SubmitEvidenceResult submitEvidence(String token, SubmitEvidenceRequest request) {
        Optional<AuthorizedUpload> upload = authorize(token);
        if (upload.isEmpty()) {
            return new SubmitEvidenceResult.InvalidToken();
        }
        List<EvidenceItemRequest> items = request.evidence();
        List<String> keys = new ArrayList<>(items.size());
        for (EvidenceItemRequest item : items) {
            Optional<String> key = storedObjectUrlResolver.keyOf(item.url()).filter(candidate -> ownsKey(upload.get(), candidate));
            if (key.isEmpty()) {
                return new SubmitEvidenceResult.InvalidUrl();
            }
            keys.add(key.get());
        }

        return unitOfWork.write(() -> attachEvidence(upload.get(), items, keys));
    }

    private SubmitEvidenceResult attachEvidence(AuthorizedUpload upload, List<EvidenceItemRequest> items, List<String> keys) {
        EvidenceUploadTokenService.UploadToken token = upload.token();
        if (tokenService.validateToken(token.token()) == null) {
            return new SubmitEvidenceResult.InvalidToken();
        }
        Map<String, StorageFile> confirmedFiles = storageMetadataService.findConfirmedFiles(upload.server(), keys);
        List<UploadedEvidenceItem> evidenceItems = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            StorageFile file = confirmedFiles.get(keys.get(i));
            if (file == null) {
                return new SubmitEvidenceResult.InvalidUrl();
            }
            EvidenceItemRequest item = items.get(i);
            evidenceItems.add(new UploadedEvidenceItem(item.url(), item.fileName(), file.contentType(), file.size()));
        }

        PunishmentOperationResult result = punishmentEvidenceService.addUploadedEvidence(
            upload.server(),
            token.punishmentId(),
            token.issuerName(),
            null,
            evidenceItems
        );
        if (result.status() == PunishmentOperationStatus.NOT_FOUND) {
            return new SubmitEvidenceResult.PunishmentNotFound(result.message());
        }
        Set<String> attached = confirmedFiles.keySet();
        if (storageFileRepository.markAttached(attached) != attached.size()) {
            throw new ConcurrentUpdateException("An uploaded evidence file was removed while it was being submitted");
        }
        if (!tokenService.invalidateToken(token.token())) {
            throw new ConcurrentUpdateException("Another submission used the evidence upload token");
        }
        return new SubmitEvidenceResult.Success();
    }

    private Optional<AuthorizedUpload> authorize(String token) {
        EvidenceUploadTokenService.UploadToken uploadToken = tokenService.validateToken(token);
        if (uploadToken == null) {
            return Optional.empty();
        }
        return Optional.of(new AuthorizedUpload(instanceContext.instance(), uploadToken));
    }

    private boolean ownsKey(AuthorizedUpload upload, String key) {
        return validationService.isKeyInNamespace(key) && key.startsWith(upload.evidencePrefix());
    }

    private record AuthorizedUpload(Server server, EvidenceUploadTokenService.UploadToken token) {
        String evidencePrefix() {
            return StorageNamespace.KEY_PREFIX + MediaCategory.EVIDENCE.uploadType() + "/" + token.punishmentId() + "/";
        }
    }

    public record TokenInfo(String punishmentId, String playerName, String issuerName, long maxUploadBytes) {
    }

    public sealed interface PresignUploadResult {
        record InvalidToken() implements PresignUploadResult {}

        record Orchestrated(UploadOrchestrationService.PresignOutcome outcome) implements PresignUploadResult {}
    }

    public sealed interface ConfirmUploadResult {
        record InvalidToken() implements ConfirmUploadResult {}

        record InvalidKey() implements ConfirmUploadResult {}

        record Orchestrated(UploadOrchestrationService.ConfirmOutcome outcome) implements ConfirmUploadResult {}
    }

    public sealed interface SubmitEvidenceResult {
        record InvalidToken() implements SubmitEvidenceResult {}

        record InvalidUrl() implements SubmitEvidenceResult {}

        record PunishmentNotFound(String message) implements SubmitEvidenceResult {}

        record Success() implements SubmitEvidenceResult {}
    }
}
