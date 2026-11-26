package gg.modl.backend.replay.service;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayUploadClaim;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.storage.StoredObject;
import gg.modl.backend.storage.service.StorageDeletionService;
import gg.modl.backend.storage.service.StorageMetadataService;
import gg.modl.backend.storage.service.UploadConfirmation;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReplayConfirmationService {
    static final Duration VALIDATION_LEASE = Duration.ofMinutes(2);
    private static final String REPLAY_CONTENT_TYPE = "application/octet-stream";

    private final ReplayRepository replayRepository;
    private final ObjectStorage objectStorage;
    private final StorageMetadataService storageMetadataService;
    private final StorageDeletionService storageDeletionService;
    private final ReplayFileValidator fileValidator;
    private final ReplayValidationGate validationGate;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public ReplayConfirmation confirmUpload(Server server, String replayId) {
        if (!TextLimits.canMatch(REPLAYS.ID, replayId)) {
            return ReplayConfirmation.NOT_FOUND;
        }
        Optional<ReplayDocument> existing = replayRepository.findById(replayId);
        if (existing.isEmpty()) {
            return ReplayConfirmation.NOT_FOUND;
        }
        ReplayDocument doc = existing.get();
        Optional<ReplayConfirmation> settled = settledOutcome(doc);
        if (settled.isPresent()) {
            return settled.get();
        }
        Optional<ReplayValidationGate.Permit> permit = validationGate.tryEnter();
        if (permit.isEmpty()) {
            return ReplayConfirmation.TRY_AGAIN;
        }
        try {
            return claimAndValidate(server, doc);
        } finally {
            permit.get().close();
        }
    }

    private ReplayConfirmation claimAndValidate(Server server, ReplayDocument doc) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ReplayUploadClaim claim = new ReplayUploadClaim(doc.getId(), now.plus(VALIDATION_LEASE));
        if (!unitOfWork.write(() -> replayRepository.claimUpload(claim, now))) {
            return replayRepository.findById(doc.getId())
                .map(current -> settledOutcome(current).orElse(ReplayConfirmation.TRY_AGAIN))
                .orElse(ReplayConfirmation.NOT_FOUND);
        }
        return validateClaimed(server, claim, doc.getStorageKey());
    }

    private ReplayConfirmation validateClaimed(Server server, ReplayUploadClaim claim, @Nullable String storageKey) {
        Optional<StoredObject> stored;
        try {
            stored = storageKey == null ? Optional.empty() : objectStorage.stat(storageKey);
        } catch (RuntimeException e) {
            return retryLater(claim, e);
        }
        if (stored.isEmpty()) {
            log.warn("Replay {} upload not found in storage", claim.replayId());
            return discard(server, claim, null, ReplayConfirmation.UPLOAD_MISSING);
        }
        StoredObject object = stored.get();
        ReplayFileCheck check;
        try {
            check = validate(object.key());
        } catch (IOException | RuntimeException e) {
            return retryLater(claim, e);
        }
        String problem = check.problem();
        if (problem != null) {
            log.warn("Replay {} was rejected: {}", claim.replayId(), problem);
            return discard(server, claim, object.key(), ReplayConfirmation.FILE_INVALID);
        }
        Recorded recorded = unitOfWork.write(() -> recordUpload(server, claim, object, check.formatVersion()));
        return switch (recorded) {
            case COMPLETED -> {
                log.debug("Replay {} confirmed", claim.replayId());
                yield ReplayConfirmation.CONFIRMED;
            }
            case CLAIM_LOST -> ReplayConfirmation.TRY_AGAIN;
            case RESERVATION_LAPSED -> {
                log.warn("Replay {} was confirmed after its upload reservation lapsed", claim.replayId());
                yield discard(server, claim, object.key(), ReplayConfirmation.RESERVATION_LAPSED);
            }
        };
    }

    private ReplayFileCheck validate(String storageKey) throws IOException {
        try (InputStream file = objectStorage.read(storageKey)) {
            return fileValidator.check(file);
        }
    }

    private ReplayConfirmation retryLater(ReplayUploadClaim claim, Exception failure) {
        unitOfWork.write(() -> replayRepository.releaseUpload(claim));
        log.warn("Replay {} could not be read for validation and stays pending: {}", claim.replayId(), failure.toString());
        return ReplayConfirmation.TRY_AGAIN;
    }

    private ReplayConfirmation discard(Server server, ReplayUploadClaim claim, @Nullable String storageKey,
                                       ReplayConfirmation outcome) {
        if (!unitOfWork.write(() -> replayRepository.failUpload(claim))) {
            return ReplayConfirmation.TRY_AGAIN;
        }
        if (storageKey != null) {
            storageDeletionService.discard(server, storageKey);
        }
        return outcome;
    }

    private Recorded recordUpload(Server server, ReplayUploadClaim claim, StoredObject object, int formatVersion) {
        if (!replayRepository.holdClaim(claim)) {
            return Recorded.CLAIM_LOST;
        }
        UploadConfirmation confirmation =
            storageMetadataService.confirmUpload(server, object.key(), object.size(), REPLAY_CONTENT_TYPE);
        if (!confirmation.recorded()) {
            return Recorded.RESERVATION_LAPSED;
        }
        return replayRepository.completeUpload(claim, object.size(), formatVersion) ? Recorded.COMPLETED : Recorded.CLAIM_LOST;
    }

    private static Optional<ReplayConfirmation> settledOutcome(ReplayDocument replay) {
        return switch (replay.getStatus()) {
            case ReplayDocument.STATUS_COMPLETE -> Optional.of(ReplayConfirmation.CONFIRMED);
            case ReplayDocument.STATUS_FAILED -> Optional.of(ReplayConfirmation.ALREADY_FAILED);
            default -> Optional.empty();
        };
    }

    private enum Recorded { COMPLETED, CLAIM_LOST, RESERVATION_LAPSED }
}
