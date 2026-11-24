package gg.modl.backend.player.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.dto.request.AddEvidenceRequest;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.player.service.PunishmentQueryService.UploadedEvidenceItem;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentEvidenceService {
    private static final String URL_EVIDENCE = "url";
    private static final String FILE_EVIDENCE = "file";

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PunishmentRepository punishmentRepository;
    private final EvidenceFileAttachments evidenceFiles;
    private final PunishmentRealtimePublisher realtimePublisher;

    public PunishmentOperationResult addEvidence(Server server, String punishmentId, String evidenceUrl, @Nullable String issuerName,
                                                 @Nullable String issuerId) {
        SafeUrls.requireSafe(evidenceUrl, "Invalid evidence URL");
        List<String> storageKeys = evidenceFiles.keysOf(Stream.ofNullable(evidenceUrl).toList());
        String storedName = PunishmentMapper.storedName(issuerId, issuerName);

        return unitOfWork.write(() -> {
            if (punishmentRepository.findById(punishmentId).isEmpty()) {
                return notFound();
            }
            Instant now = clock.instant();
            PunishmentEvidence evidence =
                new PunishmentEvidence(null, evidenceUrl, URL_EVIDENCE, storedName, issuerId, now, null, null, null);
            punishmentRepository.appendEvidence(punishmentId, List.of(evidence),
                PunishmentEntries.note("added evidence", now, storedName, issuerId));
            evidenceFiles.markAttached(storageKeys);
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence added", true, 1);
        });
    }

    public void addEvidence(Server server, UUID playerUuid, String punishmentId, AddEvidenceRequest request) {
        String url = request.url();
        SafeUrls.requireSafe(url, "Invalid evidence URL");
        List<String> storageKeys = evidenceFiles.keysOf(url == null ? List.of() : List.of(url));
        String issuerId = request.issuerId();
        String storedName = PunishmentMapper.storedName(issuerId,
            Objects.requireNonNullElse(request.issuerName(), PunishmentEntries.SYSTEM_ISSUER));

        unitOfWork.write(() -> {
            OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
            PunishmentEvidence evidence = new PunishmentEvidence(
                request.text(),
                request.url(),
                request.type(),
                storedName,
                issuerId,
                clock.instant(),
                request.fileName(),
                request.fileType(),
                request.fileSize()
            );
            punishmentRepository.appendEvidence(punishmentId, List.of(evidence), null);
            evidenceFiles.markAttached(storageKeys);
            publishDetailsChanged(server, playerUuid, punishmentId);
        });
    }

    public PunishmentOperationResult addUploadedEvidence(Server server, String punishmentId, String issuerName, @Nullable String issuerId, List<UploadedEvidenceItem> evidenceItems) {
        List<String> storageKeys = evidenceFiles.keysOf(
            evidenceItems.stream().map(UploadedEvidenceItem::url).filter(Objects::nonNull).toList());
        String storedName = PunishmentMapper.storedName(issuerId, issuerName);

        return unitOfWork.write(() -> {
            if (punishmentRepository.findById(punishmentId).isEmpty()) {
                return notFound();
            }
            Instant now = clock.instant();
            List<PunishmentEvidence> evidence = evidenceItems.stream()
                .map(item -> new PunishmentEvidence(null, item.url(), FILE_EVIDENCE, storedName, issuerId, now,
                    item.fileName(), item.fileType(), item.fileSize()))
                .toList();
            PunishmentNote note = PunishmentEntries.note(
                "uploaded " + evidenceItems.size() + " evidence file(s)", now, storedName, issuerId);
            punishmentRepository.appendEvidence(punishmentId, evidence, note);
            evidenceFiles.markAttached(storageKeys);
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Evidence uploaded successfully", true, evidenceItems.size());
        });
    }

    public PunishmentOperationResult addPunishmentNote(Server server, String punishmentId, String text, @Nullable String issuerName,
                                                       @Nullable String issuerId) {
        return unitOfWork.write(() -> {
            if (punishmentRepository.findById(punishmentId).isEmpty()) {
                return notFound();
            }
            appendNote(punishmentId, text, issuerName, issuerId);
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Note added", true, 1);
        });
    }

    public void addPunishmentNote(Server server, UUID playerUuid, String punishmentId, String text, @Nullable String issuerName,
                                  @Nullable String issuerId) {
        unitOfWork.write(() -> {
            OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
            appendNote(punishmentId, text, issuerName, issuerId);
            publishDetailsChanged(server, playerUuid, punishmentId);
        });
    }

    private void appendNote(String punishmentId, String text, @Nullable String issuerName, @Nullable String issuerId) {
        punishmentRepository.appendNote(punishmentId,
            PunishmentEntries.note(text, clock.instant(), PunishmentMapper.storedName(issuerId, issuerName), issuerId));
    }

    private void publishDetailsChanged(Server server, UUID playerUuid, String punishmentId) {
        PunishmentRecord changed = OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
        unitOfWork.afterCommit(() -> realtimePublisher.punishmentDetailsChanged(server, changed));
    }

    private static PunishmentOperationResult notFound() {
        return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, "Punishment not found", false, 0);
    }
}
