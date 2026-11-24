package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.dto.response.PunishmentPreviewResponse;
import gg.modl.backend.player.dto.response.PunishmentPreviewView;
import gg.modl.backend.player.dto.response.PunishmentResponse;
import gg.modl.backend.player.dto.response.PunishmentSearchResult;
import gg.modl.backend.player.dto.response.AppealEligibility;
import gg.modl.backend.player.dto.response.AppealInfoView;
import gg.modl.backend.player.dto.response.LinkedBanView;
import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.repository.PunishmentSearchRow;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.DefaultPunishmentTypes;
import gg.modl.backend.settings.data.DurationDetail;
import gg.modl.backend.settings.data.OffenderThresholdSettings;
import gg.modl.backend.settings.data.PunishmentDurationResolver;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.SettingsCodec;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.storage.service.EvidenceUploadTokenService;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentQueryService {
    private static final int RECENT_VIEW_LIMIT = 100;
    private static final int SEARCH_SCAN_LIMIT = 500;
    private static final int SEARCH_RESULT_LIMIT = 20;
    private static final DurationDetail UNTIL_IDENTITY_CHANGE = new DurationDetail(0, null, "permanent ban");
    private static final String NOT_STARTED_MESSAGE =
        "This punishment has not been started yet and cannot be appealed at this time.";

    private final Clock clock;
    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final OffenderThresholdSettingsService thresholdSettingsService;
    private final EvidenceUploadTokenService evidenceUploadTokenService;
    private final IssuerNameResolver issuerNameResolver;
    private final TicketRepository ticketRepository;

    public List<PunishmentResponse> getActivePunishments(Server server, UUID playerUuid) {
        Player player = playerRepository.findByMinecraftUuid(playerUuid.toString()).orElse(null);
        if (player == null) {
            return List.of();
        }
        List<Punishment> active = punishmentRepository.findByPlayer(player.getId()).stream()
            .filter(statusCalculator::isPunishmentActive)
            .toList();
        return toPunishmentResponses(server, active);
    }

    public List<PunishmentResponse> getPlayerPunishmentResponses(Server server, Player player) {
        return toPunishmentResponses(server, player.getPunishments());
    }

    private List<PunishmentResponse> toPunishmentResponses(Server server, List<Punishment> punishments) {
        Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPunishments(server, punishments);
        return punishments.stream()
            .map(punishment -> buildPunishmentResponse(server, punishment, null, resolvedIssuers))
            .toList();
    }

    private PunishmentResponse toPunishmentResponseWithPlayer(Server server, PunishmentRecord found) {
        return buildPunishmentResponse(server, found.punishment(), found,
            issuerNameResolver.resolveForPunishments(server, List.of(found.punishment())));
    }

    private PunishmentResponse buildPunishmentResponse(Server server, Punishment punishment, @Nullable PunishmentRecord owner,
                                                       Map<String, String> resolvedIssuers) {
        PunishmentDataView data = punishment.data();
        boolean active = statusCalculator.isPunishmentActive(punishment);
        Instant expires = statusCalculator.getEffectiveExpiry(punishment);

        String playerUuid = owner != null ? owner.playerMinecraftUuid() : null;
        String playerUsername = owner != null ? owner.playerUsername() : null;

        int ordinal = punishment.getTypeOrdinal();

        String effectiveCategory = statusCalculator.getEffectiveCategory(punishment).displayName();

        return new PunishmentResponse(
            punishment.getId(),
            punishmentTypeService.getPunishmentTypeName(server, ordinal),
            ordinal,
            PunishmentMapper.resolveIssuer(punishment.getIssuerId(), punishment.getIssuerName(), resolvedIssuers),
            punishment.getIssued(),
            punishment.getStarted(),
            punishmentTypeService.isAppealable(server, ordinal),
            data.reason(),
            data.severity(),
            data.asMap() != null ? resolveOffenderStatus(data) : null,
            active,
            expires,
            playerUuid,
            playerUsername,
            data.asMap() != null ? data.altBlocking() : null,
            data.asMap() != null ? data.wipeAfterExpiry() : null,
            effectiveCategory,
            resolveModifications(punishment.getModifications(), resolvedIssuers),
            resolveNotes(punishment.getNotes(), resolvedIssuers),
            resolveEvidence(punishment.getEvidence(), resolvedIssuers),
            punishment.getAttachedTicketIds(),
            data.duration()
        );
    }

    private static @Nullable String resolveOffenderStatus(PunishmentDataView data) {
        String status = data.status();
        if (status != null
            && !PunishmentStatus.UNSTARTED.equals(status)
            && !PunishmentStatus.PARDONED.equals(status)) {
            return status;
        }
        String offenseLevel = data.offenseLevel();
        if (offenseLevel != null) {
            return switch (offenseLevel.toLowerCase(Locale.ROOT)) {
                case "first" -> "low";
                default -> offenseLevel;
            };
        }
        return null;
    }

    static Set<String> collectIssuerIds(Punishment punishment) {
        Set<String> ids = new HashSet<>();
        if (punishment.getIssuerId() != null) {
            ids.add(punishment.getIssuerId());
        }
        for (PunishmentModification m : punishment.getModifications()) {
            if (m.issuerId() != null) {
                ids.add(m.issuerId());
            }
        }
        for (PunishmentNote n : punishment.getNotes()) {
            if (n.issuerId() != null) {
                ids.add(n.issuerId());
            }
        }
        for (PunishmentEvidence e : punishment.getEvidence()) {
            if (e.uploadedById() != null) {
                ids.add(e.uploadedById());
            }
        }
        return ids;
    }

    private static List<PunishmentModification> resolveModifications(List<PunishmentModification> modifications, Map<String, String> resolvedIssuers) {
        return modifications.stream().map(m -> new PunishmentModification(
            m.id(), m.type(), m.date(),
            PunishmentMapper.resolveIssuer(m.issuerId(), m.issuerName(), resolvedIssuers),
            m.issuerId(), m.reason(), m.effectiveDuration(), m.appealTicketId(), m.data()
        )).toList();
    }

    private static List<PunishmentNote> resolveNotes(List<PunishmentNote> notes, Map<String, String> resolvedIssuers) {
        return notes.stream().map(n -> new PunishmentNote(
            n.id(), n.text(), n.date(),
            PunishmentMapper.resolveIssuer(n.issuerId(), n.issuerName(), resolvedIssuers),
            n.issuerId()
        )).toList();
    }

    private static List<PunishmentEvidence> resolveEvidence(List<PunishmentEvidence> evidence, Map<String, String> resolvedIssuers) {
        return evidence.stream().map(e -> new PunishmentEvidence(
            e.text(), e.url(), e.type(),
            PunishmentMapper.resolveIssuer(e.uploadedById(), e.uploadedBy(), resolvedIssuers),
            e.uploadedById(), e.uploadedAt(), e.fileName(), e.fileType(), e.fileSize()
        )).toList();
    }

    public Optional<PunishmentView> getMinecraftPunishmentById(Server server, String punishmentId) {
        return punishmentRepository.findById(punishmentId)
            .map(found -> toPunishmentViews(server, List.of(found)).getFirst());
    }

    public List<PunishmentView> getRecentPunishments(Server server, int hours) {
        Instant cutoff = clock.instant().minus(Duration.ofHours(hours));
        return toPunishmentViews(server, punishmentRepository.findRecent(cutoff, RECENT_VIEW_LIMIT));
    }

    private List<PunishmentView> toPunishmentViews(Server server, List<PunishmentRecord> records) {
        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);
        Map<String, String> resolvedIssuers = issuerNameResolver.resolveForPunishments(server,
            records.stream().map(PunishmentRecord::punishment).toList());
        return records.stream()
            .map(found -> PunishmentMapper.toPunishmentView(found.punishment(), types, resolvedIssuers)
                .withPlayer(found.playerMinecraftUuid(), found.playerUsername()))
            .toList();
    }

    public List<PunishmentSearchResult> searchPunishments(Server server, String searchQuery, boolean activeOnly) {
        Instant now = clock.instant();
        Map<String, SearchCandidate> candidates = new LinkedHashMap<>();
        Stream.of(exactIdMatch(searchQuery), exactPlayerMatches(searchQuery), recentCandidates())
            .flatMap(List::stream)
            .filter(candidate -> !activeOnly || candidate.row().isActiveAt(now))
            .forEach(candidate -> candidates.putIfAbsent(candidate.row().id(), candidate));
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> issuerIds = new HashSet<>();
        candidates.values().forEach(candidate -> {
            if (candidate.row().issuerId() != null) {
                issuerIds.add(candidate.row().issuerId());
            }
        });
        Map<String, String> resolvedIssuers = issuerNameResolver.batchResolve(issuerIds, server);
        String needle = searchQuery.toLowerCase(Locale.ROOT);
        return candidates.values().stream()
            .filter(candidate -> matchesSearch(candidate, needle, resolvedIssuers))
            .limit(SEARCH_RESULT_LIMIT)
            .map(candidate -> new PunishmentSearchResult(
                candidate.row().id(),
                candidate.row().playerUsername(),
                candidate.row().typeOrdinal(),
                candidate.row().isActiveAt(now) ? "Active" : "Inactive",
                candidate.row().issued()))
            .toList();
    }

    private List<SearchCandidate> exactIdMatch(String searchQuery) {
        return punishmentRepository.findSearchRow(searchQuery).map(row -> new SearchCandidate(row, false)).stream().toList();
    }

    private List<SearchCandidate> exactPlayerMatches(String searchQuery) {
        return playerRepository.findByUsernameIgnoreCase(searchQuery)
            .map(player -> punishmentRepository.findSearchRowsOfPlayer(player.getId()).stream()
                .map(row -> new SearchCandidate(row, true))
                .toList())
            .orElseGet(List::of);
    }

    private List<SearchCandidate> recentCandidates() {
        return punishmentRepository.findRecentSearchRows(SEARCH_SCAN_LIMIT).stream()
            .map(row -> new SearchCandidate(row, false))
            .toList();
    }

    private static boolean matchesSearch(SearchCandidate candidate, String needle, Map<String, String> resolvedIssuers) {
        PunishmentSearchRow row = candidate.row();
        String issuer = PunishmentMapper.resolveIssuer(row.issuerId(), row.issuerName(), resolvedIssuers);
        return candidate.ownerMatched()
            || containsIgnoreCase(row.id(), needle)
            || containsIgnoreCase(row.playerUsername(), needle)
            || containsIgnoreCase(issuer, needle)
            || containsIgnoreCase(row.reason(), needle);
    }

    private static boolean containsIgnoreCase(@Nullable String value, String lowerCaseNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerCaseNeedle);
    }

    public List<LinkedBanView> getLinkedBansForParent(Server server, String parentPunishmentId) {
        return punishmentRepository.findLinkedBans(parentPunishmentId).stream()
            .filter(linkedBan -> linkedBan.punishment().getTypeOrdinal() == Punishment.LINKED_BAN_TYPE_ORDINAL)
            .map(linkedBan -> new LinkedBanView(
                linkedBan.punishment().getId(),
                linkedBan.playerMinecraftUuid(),
                linkedBan.playerUsername(),
                statusCalculator.isPunishmentActive(linkedBan.punishment())))
            .toList();
    }

    public PunishmentPreviewView previewPunishment(Server server, String playerUuid, int typeOrdinal) {
        return previewPunishment(server, playerUuid, typeOrdinal, null);
    }

    public PunishmentPreviewView previewPunishment(Server server, String playerUuid, int typeOrdinal,
                                                   @Nullable String selectedOffenseLevel) {
        Player player = playerRepository.findByMinecraftUuid(UuidUtils.normalize(playerUuid)).orElse(null);
        if (player == null) {
            return PunishmentPreviewResponse.error("Player not found");
        }

        List<PunishmentType> types = punishmentTypeService.getPunishmentTypes(server);
        PunishmentType punishmentType = PunishmentTypeIndex.byOrdinal(types).get(typeOrdinal);
        if (punishmentType == null) {
            return PunishmentPreviewResponse.error("Punishment type not found");
        }

        OffenderThresholdSettings thresholds = thresholdSettingsService.getThresholdSettings(server);
        PlayerStatusCalculator.PlayerStatus currentStatus = statusCalculator.calculateStatus(server,
            punishmentRepository.findByPlayer(player.getId()));

        boolean isSocial = punishmentType.isSocial();
        String historyLevel = OffenseLevels.fromHistory(thresholds, currentStatus, punishmentType);
        String selectedLevel = OffenseLevels.levelOf(selectedOffenseLevel);
        String offenseLevel = selectedLevel != null ? selectedLevel : historyLevel;
        String socialOffenderLevel = thresholds.getSocialOffenderLevel(currentStatus.socialPoints());
        String gameplayOffenderLevel = thresholds.getGameplayOffenderLevel(currentStatus.gameplayPoints());

        String displayStatus = PunishmentMapper.offenseDisplayStatus(historyLevel);

        PunishmentPreviewResponse.PunishmentPreviewResponseBuilder builder = PunishmentPreviewResponse.builder()
            .status(200)
            .success(true)
            .offenderStatus(displayStatus)
            .socialStatus(socialOffenderLevel)
            .gameplayStatus(gameplayOffenderLevel)
            .socialPoints(currentStatus.socialPoints())
            .gameplayPoints(currentStatus.gameplayPoints())
            .singleSeverityPunishment(punishmentType.isSingleSeverityPunishment())
            .permanentUntilUsernameChange(punishmentType.isPermanentUntilUsernameChange())
            .permanentUntilSkinChange(punishmentType.isPermanentUntilSkinChange())
            .canBeAltBlocking(punishmentType.isCanBeAltBlocking())
            .canBeStatWiping(punishmentType.isCanBeStatWiping())
            .category(punishmentType.getCategory());

        if (punishmentType.isSingleSeverityPunishment()
            || punishmentType.isPermanentUntilUsernameChange()
            || punishmentType.isPermanentUntilSkinChange()) {
            builder.singleSeverity(buildSeverityPreview(punishmentType, "regular", offenseLevel, currentStatus, isSocial, thresholds));
        } else {
            builder.lenient(buildSeverityPreview(punishmentType, "low", offenseLevel, currentStatus, isSocial, thresholds));
            builder.regular(buildSeverityPreview(punishmentType, "regular", offenseLevel, currentStatus, isSocial, thresholds));
            builder.aggravated(buildSeverityPreview(punishmentType, "severe", offenseLevel, currentStatus, isSocial, thresholds));
        }

        return builder.build();
    }

    private PunishmentPreviewResponse.SeverityPreview buildSeverityPreview(
        PunishmentType type,
        String severity,
        String offenseLevel,
        PlayerStatusCalculator.PlayerStatus currentStatus,
        boolean isSocial,
        OffenderThresholdSettings thresholds
    ) {
        int points = type.getPointsForSeverity(severity);
        DurationDetail durationDetail = type.restrictsIdentity()
            ? UNTIL_IDENTITY_CHANGE
            : PunishmentDurationResolver.resolveDetail(type, severity, offenseLevel);

        if (points == 0) {
            PunishmentType defaultType = PunishmentTypeIndex.byOrdinal(DefaultPunishmentTypes.getAll()).get(type.getOrdinal());
            if (defaultType != null) {
                points = defaultType.getPointsForSeverity(severity);
            }
        }

        long durationMs = durationDetail != null ? durationDetail.toMilliseconds() : 0L;
        boolean permanent = (durationDetail != null && durationDetail.isPermanent())
            || (durationMs <= 0 && OffenseLevels.escalates(type) && !type.isKick());
        String punishmentType = durationDetail != null
                                ? (durationDetail.isBan() ? "ban" : (durationDetail.isMute() ? "mute" : "kick"))
                                : "unknown";

        int newSocialPoints = currentStatus.socialPoints() + (isSocial ? points : 0);
        int newGameplayPoints = currentStatus.gameplayPoints() + (isSocial ? 0 : points);

        return PunishmentPreviewResponse.SeverityPreview.builder()
            .severity(severity)
            .points(points)
            .durationMs(durationMs)
            .durationFormatted(PunishmentMapper.formatDuration(durationMs, permanent))
            .punishmentType(punishmentType)
            .permanent(permanent)
            .newSocialStatus(thresholds.getSocialOffenderLevel(newSocialPoints))
            .newGameplayStatus(thresholds.getGameplayOffenderLevel(newGameplayPoints))
            .newSocialPoints(newSocialPoints)
            .newGameplayPoints(newGameplayPoints)
            .build();
    }

    public Optional<String> createEvidenceUploadToken(Server server, String punishmentId, @Nullable String issuerName) {
        return punishmentRepository.findById(punishmentId)
            .map(found -> evidenceUploadTokenService.createToken(
                server,
                punishmentId,
                found.playerMinecraftUuid(),
                issuerName
            ));
    }

    public Optional<AppealEligibility> getPublicPunishmentWithAppealEligibility(Server server, String punishmentId) {
        PunishmentRecord found = punishmentRepository.findById(punishmentId).orElse(null);
        if (found == null) {
            return Optional.empty();
        }
        if (found.punishment().getStarted() == null) {
            return Optional.of(new AppealEligibility.NotStarted(NOT_STARTED_MESSAGE));
        }

        PunishmentResponse punishment = toPunishmentResponseWithPlayer(server, found);
        Map<String, Object> existingAppeal = ticketRepository.findAppealsByPunishmentId(punishmentId).stream()
            .max(Comparator.comparing(Ticket::getCreated, Comparator.nullsFirst(Comparator.<Instant>naturalOrder())))
            .map(PunishmentQueryService::describeAppeal)
            .orElse(null);

        Map<String, Object> appealForm = punishmentTypeService.getPunishmentTypeByOrdinal(server, punishment.typeOrdinal())
            .map(PunishmentType::getAppealForm)
            .filter(form -> form.getFields() != null && !form.getFields().isEmpty())
            .map(SettingsCodec::toDocument)
            .orElse(null);

        return Optional.of(new AppealEligibility.Eligible(new AppealInfoView(
            punishment.id(),
            punishment.type(),
            punishment.issued(),
            punishment.expires(),
            punishment.active(),
            punishment.isAppealable(),
            found.playerMinecraftUuid(),
            existingAppeal,
            appealForm
        )));
    }

    private static Map<String, Object> describeAppeal(Ticket appeal) {
        Map<String, Object> description = new HashMap<>();
        description.put("id", appeal.getId());
        description.put("submittedDate", appeal.getCreated());
        description.put("status", appeal.getStatus() != null ? appeal.getStatus().getId() : TicketStatus.OPEN.getId());
        if (appeal.getAppealWorkflowStatus() != null) {
            description.put("appealWorkflowStatus", appeal.getAppealWorkflowStatus().getId());
        }
        description.put("locked", appeal.isLocked());
        return description;
    }

    public PunishmentResponse getPunishmentById(Server server, String punishmentId) {
        PunishmentRecord found = punishmentRepository.findById(punishmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Punishment not found"));
        return toPunishmentResponseWithPlayer(server, found);
    }

    public enum PunishmentOperationStatus {
        SUCCESS,
        NOT_FOUND,
        INVALID_REQUEST,
        NO_OP
    }

    private record SearchCandidate(PunishmentSearchRow row, boolean ownerMatched) {
    }

    public record UploadedEvidenceItem(String url, String fileName, @Nullable String fileType, Long fileSize) {
    }

    public record PunishmentOperationResult(
        PunishmentOperationStatus status,
        String message,
        boolean success,
        int affectedCount
    ) {
    }
}
