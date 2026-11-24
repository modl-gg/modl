package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.database.GeneratedIdInsert;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.infrastructure.validation.SafeUrls;
import gg.modl.backend.iplookup.settings.PlayerAddressSettingsStore;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.LogService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.EnforcementCategoryResolver;
import gg.modl.backend.player.data.punishment.IdentityRestriction;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentData;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentOptionChange;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.request.CreateEvidenceRequest;
import gg.modl.backend.player.dto.request.CreateNoteRequest;
import gg.modl.backend.player.dto.request.CreatePunishmentRequest;
import gg.modl.backend.player.dto.request.MinecraftCreatePunishmentRequest;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentPatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeIndex;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.service.TicketService;
import gg.modl.backend.webhook.WebhookEvent;
import gg.modl.backend.webhook.WebhookPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentLifecycleService {
    private static final long PERMANENT_DURATION = -1L;
    private static final String PARENT_PARDONED = "Auto-pardoned: parent ban was pardoned";
    private static final String PARENT_ROLLED_BACK = "Auto-pardoned: parent ban was rolled back";
    private static final String PARENT_STOPPED_ALT_BLOCKING = "Auto-pardoned: alt-blocking was disabled on the parent ban";
    private static final String DEFAULT_ROLLBACK_REASON = "Rollback";
    private static final String PARENT_RESTRICTION_LIFTED = "Auto-pardoned: parent ban was lifted";
    private static final String IDENTITY_UNKNOWN = "IDENTITY_UNKNOWN";
    private static final String UNKNOWN_TYPE = "UNKNOWN_PUNISHMENT_TYPE";
    private static final String UNKNOWN_USERNAME_MESSAGE = "modl does not know this player's username, so a ban until they"
        + " change it could never lift. Use a manual ban, or try again after the player has joined.";
    private static final String UNKNOWN_SKIN_MESSAGE = "modl does not know this player's skin, so a ban until they change it"
        + " could never lift. Use a manual ban, or try again after the player has joined with a Minecraft account"
        + " (skins of offline-mode players cannot be checked).";
    private static final String RESTRICTION_HAS_NO_OPTIONS =
        "A ban until a username or skin change has no alt-blocking and no stat wipe";
    private static final Set<String> CLIENT_SETTABLE_FLAGS = Set.of(
        PunishmentData.ALT_BLOCKING,
        PunishmentData.WIPE_AFTER_EXPIRY,
        "banLinkedAccounts",
        "kickSameIP",
        PunishmentData.SILENT,
        "aiGenerated",
        PunishmentData.PENDING_ACKNOWLEDGEMENT);
    private static final Set<String> CLIENT_SETTABLE_PUNISHMENT_DATA_KEYS = Set.of(
        PunishmentData.ALT_BLOCKING,
        PunishmentData.WIPE_AFTER_EXPIRY,
        "banLinkedAccounts",
        "kickSameIP",
        PunishmentData.SILENT,
        PunishmentData.LINKED_BAN_ID,
        PunishmentData.SEVERITY,
        PunishmentData.DURATION,
        PunishmentData.REASON,
        "aiGenerated",
        PunishmentData.PENDING_ACKNOWLEDGEMENT
    );

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final PlayerPunishmentLock playerLock;
    private final EvidenceFileAttachments evidenceFiles;
    private final TicketService ticketService;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentTypeService punishmentTypeService;
    private final PunishmentDurationCalculator durationCalculator;
    private final IssuerNameResolver issuerNameResolver;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final WebhookPublisher webhookPublisher;
    private final PunishmentRealtimePublisher realtimePublisher;
    private final LogService logService;
    private final PunishmentPromotion promotion;
    private final MinecraftIssuerAuthorizer issuerAuthorizer;
    private final PlayerAddressSettingsStore addressSettings;

    public void validatePunishmentPermission(Server server, String email, int typeOrdinal) {
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.panelPerformer(server, email);
        if (!performer.identified()) {
            throw new ForbiddenException("No authenticated user found for permission check");
        }
        if (performer.superAdmin()) {
            return;
        }

        if (punishmentTypeService.getPunishmentTypeByOrdinal(server, typeOrdinal).isEmpty()) {
            throw new ValidationException("Invalid punishment type");
        }

        String applyPermission = PermissionService.punishmentApplyPermissionId(typeOrdinal);
        if (!permissionService.hasPermission(server, performer.roleId(), applyPermission)) {
            throw new ForbiddenException("You do not have permission to apply this punishment type");
        }
    }

    public String createPanelPunishment(Server server, UUID playerUuid, PunishmentIssuer issuer, CreatePunishmentRequest request) {
        PunishmentType type = punishmentTypeService.getPunishmentTypeByOrdinal(server, request.typeOrdinal()).orElse(null);
        return createPunishment(server, playerUuid, PanelPunishmentRequests.issuedBy(issuer, type, request));
    }

    public String createMinecraftPunishment(Server server, MinecraftCreatePunishmentRequest request) {
        MinecraftIssuer issuer = MinecraftIssuer.of(request.issuerId(), request.console());
        issuerAuthorizer.requirePermission(server, issuer, PermissionService.punishmentApplyPermissionId(request.typeOrdinal()));
        UUID playerUuid = UUID.fromString(request.targetUuid());

        List<CreateNoteRequest> notes = request.notes() == null ? null : request.notes().stream()
            .map(CreateNoteRequest::new)
            .toList();

        Map<String, Object> data = request.data() != null ? new HashMap<>(request.data()) : new HashMap<>();
        PunishmentDataView.ofMap(data).setPendingAcknowledgement(true);

        return createPunishment(server, playerUuid, new CreatePunishmentRequest(
            request.issuerName(),
            issuer.staffId(),
            issuer.isConsole() ? PunishmentIssuerKind.CONSOLE : PunishmentIssuerKind.STAFF,
            request.typeOrdinal(),
            notes,
            null,
            request.attachedTicketIds(),
            request.severity(),
            request.status(),
            data,
            request.reason(),
            request.duration()
        ));
    }

    public String createPunishment(Server server, UUID playerUuid, CreatePunishmentRequest request) {
        PunishmentCreationLimits.require(request);
        validateReasonLength(request);
        requireDurationWithinLimit(request);
        requireAcceptedSeverity(request);
        List<String> evidenceUrls = requireSafeEvidenceUrls(request);
        List<String> storageKeys = evidenceFiles.keysOf(evidenceUrls);
        PunishmentType type = PunishmentTypeIndex.byOrdinal(punishmentTypeService.getPunishmentTypes(server))
            .get(request.typeOrdinal());
        if (type == null) {
            throw new ValidationException("This punishment type does not exist", UNKNOWN_TYPE);
        }

        return unitOfWork.write(() -> {
            Instant now = clock.instant();
            Player player = playerRepository.findByMinecraftUuid(playerUuid.toString())
                .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
            playerLock.lock(player.getId());
            List<Punishment> existing = punishmentRepository.findByPlayer(player.getId());

            Map<String, Object> data = filterClientSettableData(request.data());
            referenceLinkedBan(data);
            PunishmentDataView view = PunishmentDataView.ofMap(data);
            Long duration = applyRequestedTerms(server, request, type, existing, view);
            applyRestrictionBlocks(type, player, view);
            ResolvedCategory category = EnforcementCategoryResolver.resolve(type, view);
            view.setEnforcementCategory(category);
            boolean waitsToStart = mustWaitToStart(existing, category);
            boolean awaitsAcknowledgement = view.removePendingAcknowledgement();
            boolean startsAtCreation = !category.isEnforced() && !type.isKick();
            if (waitsToStart || (awaitsAcknowledgement && !startsAtCreation)) {
                view.setStatus(PunishmentStatus.UNSTARTED);
            }

            Punishment punishment = new Punishment(
                "",
                request.typeOrdinal(),
                PunishmentMapper.storedName(request.issuerId(), request.issuerName()),
                request.issuerId(),
                request.issuerKind(),
                now,
                startsAtCreation ? now : null,
                new ArrayList<>(),
                PunishmentEntries.creationNotes(request, now),
                PunishmentEntries.creationEvidence(request, now),
                request.attachedTicketIds() != null ? request.attachedTicketIds() : List.of(),
                data
            );
            String punishmentId = insertWithFreshId(player.getId(), punishment, category);
            evidenceFiles.markAttached(storageKeys);
            PunishmentRecord issued = requireRecord(punishmentId);
            recordIssued(server, request, issued, duration);
            announceIssued(server, issued, waitsToStart);
            return punishmentId;
        });
    }

    private void requireDurationWithinLimit(CreatePunishmentRequest request) {
        PunishmentDurationLimit.require(request.duration());
        if (request.data() != null) {
            PunishmentDurationLimit.require(PunishmentDataView.ofMap(request.data()).duration());
        }
    }

    private static void requireAcceptedSeverity(CreatePunishmentRequest request) {
        PunishmentSeverities.requireAccepted(request.severity());
        if (request.data() != null) {
            PunishmentSeverities.requireAccepted(request.data().get(PunishmentData.SEVERITY));
        }
    }

    private void referenceLinkedBan(Map<String, Object> data) {
        Object reference = data.remove(PunishmentData.LINKED_BAN_ID);
        if (reference == null || (reference instanceof String text && text.isBlank())) {
            return;
        }
        String parentId = Optional.of(reference)
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .flatMap(punishmentRepository::lockById)
            .map(parent -> parent.punishment().getId())
            .orElseThrow(() -> new ValidationException("linkedBanId must be the id of an existing punishment", "INVALID_ARGUMENT"));
        PunishmentDataView.ofMap(data).setLinkedBanId(parentId);
    }

    private void validateReasonLength(CreatePunishmentRequest request) {
        requireReasonWithinLimit(request.reason());
        if (request.data() != null) {
            requireReasonWithinLimit(request.data().get(PunishmentData.REASON));
        }
    }

    private void requireReasonWithinLimit(@Nullable Object reason) {
        if (reason instanceof String text && text.length() > RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH) {
            throw new ValidationException("reason must be at most "
                + RequestValidationLimits.PLAYER_PUNISHMENT_REASON_MAX_LENGTH + " characters");
        }
    }

    private static List<String> requireSafeEvidenceUrls(CreatePunishmentRequest request) {
        if (request.evidence() == null) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (CreateEvidenceRequest evidence : request.evidence()) {
            SafeUrls.requireSafe(evidence.fileUrl(), "Invalid evidence URL");
            if (evidence.fileUrl() != null) {
                urls.add(evidence.fileUrl());
            }
        }
        return urls;
    }

    private static Map<String, Object> filterClientSettableData(@Nullable Map<String, Object> requestData) {
        Map<String, Object> data = new HashMap<>();
        if (requestData == null) {
            return data;
        }
        for (String key : CLIENT_SETTABLE_PUNISHMENT_DATA_KEYS) {
            if (requestData.containsKey(key) && hasExpectedType(key, requestData.get(key))) {
                data.put(key, requestData.get(key));
            }
        }
        return data;
    }

    private static boolean hasExpectedType(String key, @Nullable Object value) {
        return !CLIENT_SETTABLE_FLAGS.contains(key) || value instanceof Boolean;
    }

    @Nullable
    private Long applyRequestedTerms(Server server, CreatePunishmentRequest request, @Nullable PunishmentType type,
                                     List<Punishment> existing, PunishmentDataView view) {
        if (request.severity() != null) {
            view.setSeverity(request.severity());
        }
        if (request.reason() != null && !request.reason().isBlank()) {
            view.setReason(request.reason());
        }
        if (type != null && type.restrictsIdentity()) {
            view.removeDurationAndOptions();
            return null;
        }
        String selectedLevel = OffenseLevels.selectedIn(request);
        if (selectedLevel != null) {
            view.setOffenseLevel(selectedLevel);
            view.setStatus(PunishmentMapper.offenseDisplayStatus(selectedLevel));
        }

        Long duration = request.duration();
        boolean hasConfiguredTerms = request.severity() != null
            || (view.duration() == null && OffenseLevels.escalates(type));
        if (duration == null && hasConfiguredTerms) {
            PunishmentDurationCalculator.DurationResult derived =
                durationCalculator.calculate(server, existing, request.typeOrdinal(), view.severity(), selectedLevel);
            duration = derived.duration();
            if (derived.status() != null) {
                view.setStatus(derived.status());
            }
            if (derived.offenseLevel() != null) {
                view.setOffenseLevel(derived.offenseLevel());
            }
        }
        if (duration == null) {
            duration = view.duration();
        }
        if (duration != null && duration != 0) {
            view.setDuration(duration);
        }
        return duration;
    }

    private static void applyRestrictionBlocks(@Nullable PunishmentType type, Player player, PunishmentDataView data) {
        if (type == null || !type.restrictsIdentity()) {
            return;
        }
        if (type.isPermanentUntilUsernameChange()) {
            String username = IdentityRestriction.knownOrNull(PlayerDataUtils.extractLatestUsername(player.getUsernames()));
            if (username != null) {
                data.setBlockedName(username);
            }
        }
        if (type.isPermanentUntilSkinChange()) {
            String skinHash = IdentityRestriction.knownOrNull(player.data().lastSkinHash());
            if (skinHash != null) {
                data.setBlockedSkin(skinHash);
            }
        }
        if (!IdentityRestriction.canLift(type, data)) {
            throw new ValidationException(
                type.isPermanentUntilSkinChange() ? UNKNOWN_SKIN_MESSAGE : UNKNOWN_USERNAME_MESSAGE, IDENTITY_UNKNOWN);
        }
    }

    private void announceIssued(Server server, PunishmentRecord issued, boolean waitsBehindAnother) {
        unitOfWork.afterCommit(() -> {
            if (waitsBehindAnother) {
                realtimePublisher.punishmentQueued(server, issued);
            } else {
                realtimePublisher.punishmentIssued(server, issued);
            }
        });
    }

    private boolean mustWaitToStart(List<Punishment> existing, ResolvedCategory category) {
        return category.isEnforced() && existing.stream().anyMatch(punishment ->
            statusCalculator.getEffectiveCategory(punishment) == category
                && statusCalculator.isPunishmentActiveOrUnstarted(punishment));
    }

    private String insertWithFreshId(String playerId, Punishment punishment, ResolvedCategory category) {
        return GeneratedIdInsert.insert(
            IdGenerator::generateShortId,
            id -> {
                punishment.setId(id);
                return punishmentRepository.insertIfAbsent(playerId, punishment, category);
            },
            GeneratedIdInsert.DEFAULT_ATTEMPTS);
    }

    private void recordIssued(Server server, CreatePunishmentRequest request, PunishmentRecord issued, @Nullable Long duration) {
        String issuer = issuerNameResolver.resolve(request.issuerId(), request.issuerName(), server);
        String typeName = punishmentTypeService.getPunishmentTypeName(server, request.typeOrdinal());
        List<String> ticketIds = request.attachedTicketIds() != null ? request.attachedTicketIds() : List.of();

        Map<String, String> webhookVariables = Map.of(
            "id", issued.punishment().getId(),
            "playerName", issued.playerUsername(),
            "playerUuid", issued.playerMinecraftUuid(),
            "type", typeName,
            "severity", Objects.requireNonNullElse(request.severity(), ""),
            "reason", Objects.requireNonNullElse(request.reason(), ""),
            "duration", duration != null ? PunishmentMapper.formatDuration(duration, duration < 0) : "Permanent",
            "issuer", issuer,
            "ticketId", String.join(", ", ticketIds)
        );

        ticketIds.stream().distinct().sorted()
            .forEach(ticketId -> ticketService.closeTicketForPunishment(server, ticketId, issuer));
        logService.recordModerationAction(server, AuditActor.named(issuer), typeName + " issued for " + issued.playerUsername());

        unitOfWork.afterCommit(() -> webhookPublisher.publish(WebhookEvent.NEW_PUNISHMENTS, webhookVariables));
    }

    public PunishmentOperationResult acknowledgePunishment(Server server, UUID playerUuid, String punishmentId, boolean executed) {
        return unitOfWork.write(() -> {
            Player player = playerRepository.findByMinecraftUuid(playerUuid.toString()).orElse(null);
            if (player == null) {
                return notFound("Player not found: " + playerUuid);
            }
            playerLock.lock(player.getId());
            Punishment target = OwnedPunishments.find(punishmentRepository, playerUuid.toString(), punishmentId)
                .map(PunishmentRecord::punishment)
                .orElse(null);
            if (target == null) {
                return notFound("Punishment not found: " + punishmentId + " for player: " + playerUuid);
            }
            if (!executed) {
                return noOp("The server could not carry out the punishment; it is delivered again");
            }
            if (target.hasEndedEnforcement()) {
                return noOp("Punishment has already ended");
            }
            if (target.hasUnstartedStatus()
                && promotion.waitsBehindAnother(punishmentRepository.findEnforcementRelevant(player.getId(), clock.instant()), target)) {
                return noOp("Punishment waits until the current one ends");
            }
            boolean started = target.getStarted() == null
                ? punishmentRepository.markStarted(punishmentId, clock.instant())
                : punishmentRepository.promote(punishmentId);
            if (!started) {
                return noOp("Punishment already acknowledged");
            }
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment acknowledged", true, 1);
        });
    }

    private static PunishmentOperationResult noOp(String message) {
        return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, message, true, 0);
    }

    public PunishmentOperationResult pardonPunishment(Server server, String punishmentId, @Nullable String issuerName,
                                                      @Nullable String issuerId, @Nullable String reason,
                                                      @Nullable String expectedType) {
        ResolvedCategory expected = PardonTargets.categoryOf(expectedType);
        return unitOfWork.write(() -> {
            PunishmentRecord found = punishmentRepository.findById(punishmentId).orElse(null);
            if (found == null) {
                return notFound("Punishment not found");
            }
            PardonTargets.requireCategory(found.punishment(), expected);
            if (found.punishment().hasEndedEnforcement()) {
                return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP,
                    "Punishment has already been " + (found.punishment().isPardoned() ? "pardoned" : "rolled back"), false, 0);
            }

            PunishmentRecord pardoned = applyManualPardon(server, found, issuerName, issuerId, reason);
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentPardoned(server, pardoned));
            if (found.punishment().data().altBlocking()) {
                cascadePardonLinkedBans(server, punishmentId);
            }
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment pardoned", true, 1);
        });
    }

    public int pardonPunishments(Server server, Player player, List<Punishment> targets,
                                 @Nullable String issuerName, @Nullable String issuerId, @Nullable String reason) {
        List<String> targetIds = targets.stream().map(Punishment::getId).distinct().sorted().toList();
        if (targetIds.isEmpty()) {
            return 0;
        }
        String playerUuid = player.getMinecraftUuid().toString();
        return unitOfWork.write(() -> {
            List<PunishmentRecord> pardoned = new ArrayList<>();
            Set<String> altBlockingParents = new LinkedHashSet<>();
            for (String id : targetIds) {
                PunishmentRecord found = OwnedPunishments.find(punishmentRepository, playerUuid, id).orElse(null);
                if (found == null || found.punishment().hasEndedEnforcement()) {
                    continue;
                }
                pardoned.add(applyManualPardon(server, found, issuerName, issuerId, reason));
                if (found.punishment().data().altBlocking()) {
                    altBlockingParents.add(id);
                }
            }
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentsPardoned(server, pardoned));
            altBlockingParents.forEach(parentId -> cascadePardonLinkedBans(server, parentId));
            return pardoned.size();
        });
    }

    public PunishmentOperationResult rollbackPunishment(Server server, String punishmentId, PunishmentIssuer performer,
                                                        @Nullable String reason) {
        return unitOfWork.write(() -> {
            PunishmentRecord found = punishmentRepository.findById(punishmentId).orElse(null);
            if (found == null) {
                return notFound("Punishment not found");
            }
            if (found.punishment().hasRollback()) {
                return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP,
                    "Punishment has already been rolled back", false, 0);
            }

            PunishmentModification modification = PunishmentEntries.modification(PunishmentModificationType.ROLLBACK,
                clock.instant(), PunishmentMapper.storedName(performer.id(), performer.name()), performer.id(),
                Objects.requireNonNullElse(reason, DEFAULT_ROLLBACK_REASON), null);
            punishmentRepository.appendModification(punishmentId, found.version(), modification, PunishmentPatch.NONE, List.of());
            promotion.promoteAfterCommit(server, found.playerId());
            PunishmentRecord rolledBack = requireRecord(punishmentId);
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentModified(server, rolledBack));
            if (found.punishment().data().altBlocking()) {
                systemPardonLinkedBans(server, punishmentId, PARENT_ROLLED_BACK);
            }
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment rolled back", true, 1);
        });
    }

    private PunishmentRecord applyManualPardon(Server server, PunishmentRecord target, @Nullable String issuerName,
                                               @Nullable String issuerId, @Nullable String reason) {
        Instant now = clock.instant();
        String storedName = PunishmentMapper.storedName(issuerId, issuerName);
        PunishmentModification modification = PunishmentEntries.modification(PunishmentModificationType.MANUAL_PARDON,
            now, storedName, issuerId, reason != null ? reason : "", null);
        List<PunishmentNote> notes = new ArrayList<>();
        notes.add(PunishmentEntries.note("pardoned punishment", now, storedName, issuerId));
        if (reason != null && !reason.isBlank()) {
            notes.add(PunishmentEntries.note(reason, now, storedName, issuerId));
        }
        return appendPardon(server, target, modification, notes);
    }

    private PunishmentRecord applySystemPardon(Server server, PunishmentRecord target, String reason, Instant now) {
        PunishmentModification modification = PunishmentEntries.modification(PunishmentModificationType.SYSTEM_PARDON,
            now, PunishmentEntries.SYSTEM_ISSUER, null, reason, null);
        return appendPardon(server, target, modification, List.of(PunishmentEntries.systemNote(reason, now)));
    }

    private PunishmentRecord appendPardon(Server server, PunishmentRecord target, PunishmentModification modification,
                                          List<PunishmentNote> notes) {
        String id = target.punishment().getId();
        punishmentRepository.appendModification(id, target.version(), modification,
            PunishmentPatch.builder().status(PunishmentStatus.PARDONED).build(), notes);
        promotion.promoteAfterCommit(server, target.playerId());
        return requireRecord(id);
    }

    void promoteAfterCommit(Server server, String playerId) {
        promotion.promoteAfterCommit(server, playerId);
    }

    public int cascadePardonLinkedBans(Server server, String parentPunishmentId) {
        return systemPardonLinkedBans(server, parentPunishmentId, PARENT_PARDONED);
    }

    private int systemPardonLinkedBans(Server server, String parentPunishmentId, String reason) {
        return cascadeToLinkedBans(server, parentPunishmentId, linkedBan -> true,
            PunishmentLifecycleService::endedWithTheirParent,
            (linkedBan, now) -> applySystemPardon(server, linkedBan, reason, now), realtimePublisher::punishmentsPardoned);
    }

    private static void endedWithTheirParent(List<Punishment> linkedBans, Instant now) {
    }

    int liftAutomaticLinkedBans(Server server, String parentPunishmentId, PunishmentShortening shortening) {
        return cascadeToLinkedBans(server, parentPunishmentId,
            linkedBan -> linkedBan.data().linkedBanParentUuid() != null,
            (lifted, now) -> shortening.require(),
            (linkedBan, now) -> applySystemPardon(server, linkedBan, PARENT_STOPPED_ALT_BLOCKING, now), realtimePublisher::punishmentsPardoned);
    }

    void requireOptionFitsRestriction(Punishment punishment, PunishmentOptionChange change) {
        if (change.enabled() && IdentityRestriction.isHeldBy(punishment)) {
            throw new ValidationException(RESTRICTION_HAS_NO_OPTIONS, "INVALID_ARGUMENT");
        }
    }

    void requireOptionOffered(Server server, Punishment punishment, PunishmentOptionChange change) {
        if (!change.enabled()) {
            return;
        }
        requireOptionFitsRestriction(punishment, change);
        boolean offered = punishmentTypeService.getPunishmentTypeByOrdinal(server, punishment.getTypeOrdinal())
            .filter(change.option()::isOfferedBy)
            .isPresent();
        if (!offered) {
            throw new ValidationException(
                "This punishment type does not offer " + change.option().displayName(), "INVALID_ARGUMENT");
        }
    }

    public int cascadeDurationChangeToLinkedBans(Server server, String parentPunishmentId, @Nullable Long newDuration,
                                                 PunishmentShortening shortening) {
        PunishmentDurationLimit.require(newDuration);
        long effective = newDuration != null ? newDuration : PERMANENT_DURATION;
        return cascadeToLinkedBans(server, parentPunishmentId, linkedBan -> true,
            (changed, now) -> shortening.requireForAll(changed, effective, now),
            (linkedBan, now) -> applyLinkedBanDurationChange(server, linkedBan, effective, now), realtimePublisher::punishmentsModified);
    }

    private int cascadeToLinkedBans(Server server, String parentPunishmentId, Predicate<Punishment> included,
                                    BiConsumer<List<Punishment>, Instant> permitted,
                                    BiConsumer<PunishmentRecord, Instant> change,
                                    BiConsumer<Server, List<PunishmentRecord>> announce) {
        return unitOfWork.write(() -> {
            Instant now = clock.instant();
            List<PunishmentRecord> affected = punishmentRepository.findLinkedBans(parentPunishmentId).stream()
                .filter(linkedBan -> linkedBan.punishment().getTypeOrdinal() == Punishment.LINKED_BAN_TYPE_ORDINAL)
                .filter(linkedBan -> included.test(linkedBan.punishment()))
                .filter(linkedBan -> statusCalculator.isPunishmentActive(linkedBan.punishment()))
                .toList();
            if (affected.isEmpty()) {
                return 0;
            }
            permitted.accept(affected.stream().map(PunishmentRecord::punishment).toList(), now);
            affected.forEach(linkedBan -> change.accept(linkedBan, now));

            Set<String> affectedIds = new LinkedHashSet<>(affected.stream().map(linkedBan -> linkedBan.punishment().getId()).toList());
            List<PunishmentRecord> changed = punishmentRepository.findLinkedBans(parentPunishmentId).stream()
                .filter(linkedBan -> affectedIds.contains(linkedBan.punishment().getId()))
                .toList();
            unitOfWork.afterCommit(() -> announce.accept(server, changed));
            return affected.size();
        });
    }

    private void applyLinkedBanDurationChange(Server server, PunishmentRecord linkedBan, long effective, Instant now) {
        String id = linkedBan.punishment().getId();
        PunishmentModification modification = PunishmentEntries.modification(PunishmentModificationType.MANUAL_DURATION_CHANGE,
            now, PunishmentEntries.SYSTEM_ISSUER, null, "Cascaded from parent ban duration change", effective);
        punishmentRepository.appendModification(id, linkedBan.version(), modification,
            PunishmentPatch.builder().duration(effective).build(),
            List.of(PunishmentEntries.systemNote("Duration changed (cascaded from parent ban)", now)));
        promotion.promoteAfterCommit(server, linkedBan.playerId());
    }

    public List<String> promoteUnstartedPunishments(Server server, Player player) {
        return promotion.promote(server, player);
    }

    public List<String> enforceAltBlockingBans(Server server, Player player) {
        if (hasActiveBan(player.getPunishments())) {
            return List.of();
        }
        String playerId = player.getId();
        if (enforceableParents(playerId, punishmentRepository.findActiveAltBlockingBansAmongLinked(playerId, sharedAccountLimit(), clock.instant()))
            .isEmpty()) {
            return List.of();
        }
        ResolvedCategory category = EnforcementCategoryResolver.resolve(
            punishmentTypeService.getPunishmentTypeByOrdinal(server, Punishment.LINKED_BAN_TYPE_ORDINAL).orElse(null),
            PunishmentDataView.ofMap(Map.of()));
        return unitOfWork.write(() -> issueLinkedBan(server, playerId, category).map(id -> List.of(id)).orElse(List.of()));
    }

    private boolean hasActiveBan(List<Punishment> punishments) {
        return punishments.stream().anyMatch(punishment ->
            statusCalculator.getEffectiveCategory(punishment) == ResolvedCategory.BAN
                && statusCalculator.isPunishmentActive(punishment));
    }

    private Optional<String> issueLinkedBan(Server server, String playerId, ResolvedCategory category) {
        playerLock.lock(playerId);
        Instant now = clock.instant();
        List<Punishment> current = punishmentRepository.findEnforcementRelevant(playerId, now);
        if (hasActiveBan(current)) {
            return Optional.empty();
        }
        PunishmentRecord parent = lockedParentToEnforce(playerId, now).orElse(null);
        if (parent == null) {
            return Optional.empty();
        }

        Instant parentExpiry = statusCalculator.getEffectiveExpiry(parent.punishment());
        Long duration = null;
        if (parentExpiry != null) {
            long remaining = parentExpiry.toEpochMilli() - now.toEpochMilli();
            if (remaining <= 0) {
                return Optional.empty();
            }
            duration = remaining;
        }

        Map<String, Object> data = new HashMap<>();
        PunishmentDataView view = PunishmentDataView.ofMap(data);
        view.setLinkedBanId(parent.punishment().getId());
        view.setLinkedBanParentUuid(parent.playerMinecraftUuid());
        view.setEnforcementCategory(category);
        if (duration != null) {
            view.setDuration(duration);
        }
        Punishment linkedBan = new Punishment("", Punishment.LINKED_BAN_TYPE_ORDINAL, PunishmentEntries.SYSTEM_ISSUER, null,
            PunishmentIssuerKind.AUTOMATED, now, now, new ArrayList<>(), PunishmentEntries.linkedBanNotes(now), List.of(), List.of(), data);
        String linkedBanId = insertWithFreshId(playerId, linkedBan, category);
        announceIssued(server, requireRecord(linkedBanId), false);
        return Optional.of(linkedBanId);
    }

    private Optional<PunishmentRecord> lockedParentToEnforce(String playerId, Instant now) {
        List<String> candidates = punishmentRepository.findActiveAltBlockingBansAmongLinked(playerId, sharedAccountLimit(), now).stream()
            .map(parent -> parent.punishment().getId())
            .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        punishmentRepository.lockAll(candidates.stream().sorted().toList());
        List<PunishmentRecord> lockedParents = candidates.stream()
            .map(punishmentRepository::findById)
            .flatMap(Optional::stream)
            .toList();
        return enforceableParents(playerId, lockedParents).stream().findFirst();
    }

    private int sharedAccountLimit() {
        return addressSettings.current().effectiveSharedAccountLimit();
    }

    private List<PunishmentRecord> enforceableParents(String playerId, List<PunishmentRecord> candidates) {
        List<PunishmentRecord> parents = candidates.stream().filter(this::enforcesAltBlocking).toList();
        if (parents.isEmpty()) {
            return List.of();
        }
        LinkedBanExemptions exemptions = LinkedBanExemptions.of(punishmentRepository.findLinkedBansOfPlayer(playerId,
            parents.stream().map(parent -> parent.punishment().getId()).toList()), statusCalculator);
        return parents.stream().filter(parent -> !exemptions.covers(parent.punishment().getId())).toList();
    }

    private boolean enforcesAltBlocking(PunishmentRecord parent) {
        Punishment punishment = parent.punishment();
        return punishment.data().altBlocking()
            && punishment.enforcementCategory() == ResolvedCategory.BAN
            && statusCalculator.isPunishmentActive(punishment);
    }

    public List<String> checkRestrictionAutoPardons(Server server, Player player, String currentUsername,
                                                    @Nullable String currentSkinHash) {
        if (liftedRestrictions(player.getPunishments(), currentUsername, currentSkinHash).isEmpty()) {
            return List.of();
        }
        String playerId = player.getId();
        UUID playerUuid = player.getMinecraftUuid();
        return unitOfWork.write(() -> {
            playerLock.lock(playerId);
            List<LiftedRestriction> lifted = liftedRestrictions(
                punishmentRepository.findEnforcementRelevant(playerId, clock.instant()), currentUsername, currentSkinHash);
            lifted.forEach(restriction ->
                systemPardonPunishment(server, playerUuid, restriction.punishmentId(), restriction.reason()));
            return lifted.stream().map(LiftedRestriction::punishmentId).toList();
        });
    }

    private List<LiftedRestriction> liftedRestrictions(List<Punishment> punishments,
                                                       @Nullable String username, @Nullable String skinHash) {
        List<LiftedRestriction> lifted = new ArrayList<>();
        for (Punishment punishment : punishments) {
            if (statusCalculator.isPunishmentActiveOrUnstarted(punishment)) {
                IdentityRestriction.liftReason(punishment, username, skinHash)
                    .ifPresent(reason -> lifted.add(new LiftedRestriction(punishment.getId(), reason)));
            }
        }
        return lifted;
    }

    public void systemPardonPunishment(Server server, UUID playerUuid, String punishmentId, String reason) {
        unitOfWork.write(() -> {
            PunishmentRecord found = OwnedPunishments.find(punishmentRepository, playerUuid.toString(), punishmentId).orElse(null);
            if (found == null) {
                return;
            }
            PunishmentRecord pardoned = applySystemPardon(server, found, reason, clock.instant());
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentPardoned(server, pardoned));
            if (found.punishment().data().altBlocking()) {
                systemPardonLinkedBans(server, punishmentId, PARENT_RESTRICTION_LIFTED);
            }
        });
    }

    private PunishmentRecord requireRecord(String punishmentId) {
        return punishmentRepository.findById(punishmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Punishment not found"));
    }

    private static PunishmentOperationResult notFound(String message) {
        return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, message, false, 0);
    }

    private record LiftedRestriction(String punishmentId, String reason) {
    }
}
