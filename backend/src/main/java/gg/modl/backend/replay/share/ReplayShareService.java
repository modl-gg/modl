package gg.modl.backend.replay.share;

import static gg.modl.backend.database.jooq.Tables.REPLAYS;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.log.service.AuditActors;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.replay.data.ReplayDocument;
import gg.modl.backend.replay.data.ReplayShare;
import gg.modl.backend.replay.repository.ReplayRepository;
import gg.modl.backend.replay.repository.ReplayShareRepository;
import gg.modl.backend.replay.service.ReplayFile;
import gg.modl.backend.replay.service.ReplayService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.GeneralSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReplayShareService {
    public static final String REPLAY_NOT_FOUND = "REPLAY_NOT_FOUND";
    public static final String SHARE_NOT_FOUND = "REPLAY_SHARE_NOT_FOUND";
    public static final String SHARE_LIMIT = "REPLAY_SHARE_LIMIT";
    static final int MAX_OPEN_SHARES_PER_REPLAY = 25;
    static final int CLOSED_SHARES_KEPT = 25;
    private static final String UNKNOWN_TARGET = "an unknown player";

    private final ReplayRepository replays;
    private final ReplayShareRepository shares;
    private final ReplayShareTokens tokens;
    private final ReplayService replayService;
    private final GeneralSettingsService generalSettings;
    private final AuditActors auditActors;
    private final PanelActionAuditor auditor;
    private final PublicUrls publicUrls;
    private final ReplayShareViews views;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public CreatedReplayShare create(Server server, @Nullable String actorEmail, String replayId, ReplayShareExpiry expiry) {
        ReplayDocument replay = completedReplay(replayId);
        String token = tokens.issue();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        ReplayShare share = new ReplayShare(EntityIds.next(), replay.getId(), ReplayShareTokens.hash(token),
            auditActors.account(server, actorEmail).displayName(), now, expiry.expiresAt(now).orElse(null), null, 0L, null);
        unitOfWork.write(() -> {
            if (replays.findById(replay.getId()).isEmpty()) {
                throw notFound();
            }
            if (!shares.insertWithinLimit(share, MAX_OPEN_SHARES_PER_REPLAY, CLOSED_SHARES_KEPT, clock.instant())) {
                throw new ConflictException("This replay has the maximum of " + MAX_OPEN_SHARES_PER_REPLAY + " open share links",
                    SHARE_LIMIT);
            }
            unitOfWork.afterCommit(() -> auditor.recordStaffAction(server, actorEmail,
                "Shared the replay of " + targetOf(replay) + ", " + expiryText(share)));
        });
        return new CreatedReplayShare(share, token, publicUrls.sharedReplay(token));
    }

    public List<ReplayShare> list(String replayId) {
        return shares.listForReplay(completedReplay(replayId).getId());
    }

    public List<ReplayShare> revoke(Server server, @Nullable String actorEmail, String replayId, String shareId) {
        ReplayDocument replay = completedReplay(replayId);
        unitOfWork.write(() -> {
            ReplayShare share = shares.findForReplay(replay.getId(), shareId)
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found", SHARE_NOT_FOUND));
            if (shares.revoke(share.id(), clock.instant())) {
                unitOfWork.afterCommit(() -> auditor.recordStaffAction(server, actorEmail,
                    "Revoked a share link of the replay of " + targetOf(replay)));
            }
        });
        return shares.listForReplay(replay.getId());
    }

    public Optional<SharedReplay> open(Server server, String token, String clientNetwork) {
        Optional<OpenedShare> opened = openShare(token);
        opened.ifPresent(share -> views.record(share.share().id(), clientNetwork));
        return opened.map(share -> new SharedReplay(share.replay(), serverName(server),
            RESTMappingV1.PUBLIC_SHARED_REPLAYS + "/" + token + "/file"));
    }

    public Optional<ReplayFile> openFile(String token) {
        return openShare(token).flatMap(share -> replayService.completedFile(share.replay().getId()));
    }

    private Optional<OpenedShare> openShare(String token) {
        if (!ReplayShareTokens.wellFormed(token)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return shares.findByTokenHash(ReplayShareTokens.hash(token))
            .filter(share -> share.opensAt(now))
            .flatMap(share -> replays.findById(share.replayId())
                .filter(replay -> replay.completedStorageKey().isPresent())
                .map(replay -> new OpenedShare(share, replay)));
    }

    private ReplayDocument completedReplay(String replayId) {
        if (!TextLimits.canMatch(REPLAYS.ID, replayId)) {
            throw notFound();
        }
        return replays.findById(replayId)
            .filter(replay -> replay.completedStorageKey().isPresent())
            .orElseThrow(ReplayShareService::notFound);
    }

    private String serverName(Server server) {
        String name = generalSettings.getGeneralSettings(server).getServerDisplayName();
        return name == null ? "" : name;
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Replay not found", REPLAY_NOT_FOUND);
    }

    private static String targetOf(ReplayDocument replay) {
        String name = replay.getTargetName();
        return name == null || name.isBlank() ? UNKNOWN_TARGET : name;
    }

    private static String expiryText(ReplayShare share) {
        Instant expiresAt = share.expiresAt();
        return expiresAt == null ? "never expires" : "expires " + expiresAt.truncatedTo(ChronoUnit.MINUTES);
    }

    private record OpenedShare(ReplayShare share, ReplayDocument replay) {
    }
}
