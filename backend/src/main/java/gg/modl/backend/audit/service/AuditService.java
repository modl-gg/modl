package gg.modl.backend.audit.service;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentPhase;
import gg.modl.backend.player.repository.IssuerMatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.IssuerNameResolver;
import gg.modl.backend.player.service.PunishmentIssuer;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.player.service.PunishmentShortening;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.service.StaffService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {
    static final int BULK_PAGE_SIZE = 500;
    static final int BULK_WRITE_UNIT_SIZE = 50;
    static final int BULK_TYPES_MAX = 64;

    private static final String MODERATION_LEVEL = "moderation";
    private static final String DEFAULT_ROLLBACK_REASON = "Admin rollback";
    private static final String DEFAULT_BULK_ROLLBACK_REASON = "Bulk rollback";
    private static final String UNKNOWN_PLAYER = "Unknown";
    private static final long PERMANENT_DURATION = -1L;
    private static final Comparator<PunishmentRecord> PARENTS_BEFORE_LINKED_BANS = Comparator
        .comparing((PunishmentRecord target) -> target.punishment().getTypeOrdinal() == Punishment.LINKED_BAN_TYPE_ORDINAL)
        .thenComparing(target -> target.punishment().getId());

    private final UnitOfWork unitOfWork;
    private final PunishmentRepository punishmentRepository;
    private final AuditLogRepository auditLogRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final StaffService staffService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentMutationService punishmentMutationService;
    private final IssuerNameResolver issuerNameResolver;
    private final Clock clock;

    public boolean rollbackPunishment(Server server, String punishmentId, String reason, PunishmentIssuer performer) {
        requireShortReason(reason);
        Rollback rollback = new Rollback(
            server, performer, reason, Objects.requireNonNullElse(reason, DEFAULT_ROLLBACK_REASON));
        return unitOfWork.write(() -> {
            PunishmentRecord target = punishmentRepository.findById(punishmentId).orElse(null);
            if (target == null) {
                return false;
            }
            if (!rollBack(rollback, target)) {
                throw new ValidationException("This punishment cannot be rolled back");
            }
            Punishment punishment = target.punishment();
            String issuer = issuerNameResolver.resolve(punishment.getIssuerId(), punishment.getIssuerName(), server);
            recordRollback(rollback, target, "Rolled back " + typeNameOf(server, target) + " for " + playerNameOf(target),
                issuer, false);
            return true;
        });
    }

    public int rollbackAllPunishmentsByStaff(Server server, String staffUsername, String reason, PunishmentIssuer performer) {
        return rollbackByIssuer(server, staffUsername, null, null, reason, performer);
    }

    public int rollbackPunishmentsByDateRange(Server server, String staffUsername, Instant startDate, Instant endDate,
                                              String reason, PunishmentIssuer performer) {
        return rollbackByIssuer(server, staffUsername, startDate, endDate, reason, performer);
    }

    private int rollbackByIssuer(Server server, String staffUsername, @Nullable Instant from, @Nullable Instant until,
                                 String reason, PunishmentIssuer performer) {
        requireShortReason(reason);
        String staffId = staffService.getStaffByUsername(server, staffUsername).map(StaffResponse::id).orElse(null);
        IssuerMatch issuer = new IssuerMatch(staffUsername, staffId);
        Rollback rollback = new Rollback(
            server, performer, reason, Objects.requireNonNullElse(reason, DEFAULT_BULK_ROLLBACK_REASON));
        return applyByIds(afterId -> punishmentRepository.pageIdsByIssuer(issuer, from, until, afterId, BULK_PAGE_SIZE), target -> {
            if (!rollBack(rollback, target)) {
                return false;
            }
            String description = "Bulk rollback: " + typeNameOf(server, target) + " for " + playerNameOf(target)
                + " (issued by " + staffUsername + ")";
            recordRollback(rollback, target, description, staffUsername, true);
            return true;
        });
    }

    public int bulkPardonByType(Server server, List<Integer> typeOrdinals, String reason, PunishmentIssuer performer) {
        requireShortReason(reason);
        Map<String, Object> details = Map.of(
            AuditMetadata.PARDON_REASON, Objects.requireNonNullElse(reason, ""),
            AuditMetadata.BULK_PARDON, true);
        return applyToTypesInBulkPhases(typeOrdinals, (target, now) -> {
            if (!punishmentLifecycleService.pardonPunishment(
                    server, target.punishment().getId(), performer.name(), performer.id(), reason, null).success()) {
                return false;
            }
            String description = "Bulk pardon: " + typeNameOf(server, target) + " for " + playerNameOf(target);
            recordModeration(server, target, performer.name(), now, description, details);
            return true;
        });
    }

    public int bulkSetExpirationByType(Server server, List<Integer> typeOrdinals, long newDurationMs, String reason,
                                       PunishmentIssuer performer, PunishmentShortening shortening) {
        requireShortReason(reason);
        long effectiveDuration = newDurationMs <= 0 ? PERMANENT_DURATION : newDurationMs;
        Map<String, Object> details = Map.of(
            AuditMetadata.REASON, Objects.requireNonNullElse(reason, ""),
            AuditMetadata.NEW_DURATION, newDurationMs,
            AuditMetadata.BULK_DURATION_CHANGE, true);
        return applyToTypesInBulkPhases(typeOrdinals, (target, now) -> {
            if (!punishmentMutationService.changeDuration(
                    server, target.punishment().getId(), effectiveDuration, performer.name(), performer.id(), shortening).success()) {
                return false;
            }
            String description = "Bulk duration change: " + typeNameOf(server, target) + " for " + playerNameOf(target);
            recordModeration(server, target, performer.name(), now, description, details);
            return true;
        });
    }

    private static void requireShortReason(@Nullable String reason) {
        if (reason != null && reason.codePointCount(0, reason.length()) > RequestValidationLimits.AUDIT_ROLLBACK_REASON_MAX_LENGTH) {
            throw new ValidationException("The reason must be at most " + RequestValidationLimits.AUDIT_ROLLBACK_REASON_MAX_LENGTH + " characters");
        }
    }

    private int applyToTypesInBulkPhases(List<Integer> typeOrdinals, BulkTargetAction action) {
        List<Integer> ordinals = typeOrdinals.stream().distinct().toList();
        if (ordinals.size() > BULK_TYPES_MAX) {
            throw new ValidationException("At most " + BULK_TYPES_MAX + " punishment types can be changed at once", "INVALID_ARGUMENT");
        }
        if (ordinals.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant();
        return applyByIds(
            afterId -> punishmentRepository.pageIdsByTypes(ordinals, PunishmentPhase.NOT_ENDED, now, afterId, BULK_PAGE_SIZE),
            target -> PunishmentPhase.NOT_ENDED.contains(PunishmentPhase.of(target.punishment(), now)) && action.apply(target, now));
    }

    private int applyByIds(TargetIds pages, Predicate<PunishmentRecord> action) {
        int affected = 0;
        String afterId = null;
        do {
            String from = afterId;
            List<String> ids = unitOfWork.read(() -> pages.after(from));
            for (int start = 0; start < ids.size(); start += BULK_WRITE_UNIT_SIZE) {
                List<String> unit = ids.subList(start, Math.min(start + BULK_WRITE_UNIT_SIZE, ids.size()));
                affected += unitOfWork.write(() -> applyPage(unit, action));
            }
            afterId = ids.size() < BULK_PAGE_SIZE ? null : ids.getLast();
        } while (afterId != null);
        return affected;
    }

    private int applyPage(List<String> ids, Predicate<PunishmentRecord> action) {
        List<PunishmentRecord> targets = ids.stream()
            .map(punishmentRepository::findById)
            .flatMap(Optional::stream)
            .sorted(PARENTS_BEFORE_LINKED_BANS)
            .toList();
        int affected = 0;
        for (PunishmentRecord target : targets) {
            if (action.test(target)) {
                affected++;
            }
        }
        return affected;
    }

    private boolean rollBack(Rollback rollback, PunishmentRecord target) {
        if (target.punishment().hasRollback()) {
            return false;
        }
        return punishmentLifecycleService.rollbackPunishment(
            rollback.server(), target.punishment().getId(), rollback.performer(), rollback.reason()).success();
    }

    private void recordRollback(Rollback rollback, PunishmentRecord target, String description, String issuer, boolean bulk) {
        recordModeration(rollback.server(), target, rollback.performer().name(), clock.instant(), description, Map.of(
            AuditMetadata.STAFF_USERNAME, issuer,
            AuditMetadata.ROLLBACK_REASON, rollback.auditedReason(),
            AuditMetadata.BULK_ROLLBACK, bulk));
    }

    private void recordModeration(
        Server server, PunishmentRecord target, String performer, Instant at, String description, Map<String, Object> details) {
        Map<String, Object> metadata = new HashMap<>(details);
        metadata.put(AuditMetadata.PUNISHMENT_ID, target.punishment().getId());
        metadata.put(AuditMetadata.PLAYER_ID, target.playerMinecraftUuid());
        metadata.put(AuditMetadata.PLAYER_NAME, playerNameOf(target));
        metadata.put(AuditMetadata.PUNISHMENT_TYPE, typeNameOf(server, target));
        auditLogRepository.insert(AuditLog.builder()
            .created(at)
            .level(MODERATION_LEVEL)
            .source(AuditActor.displayNameFor(performer))
            .description(description)
            .metadata(metadata)
            .build());
    }

    private String typeNameOf(Server server, PunishmentRecord target) {
        return punishmentTypeService.getPunishmentTypeName(server, target.punishment().getTypeOrdinal());
    }

    private static String playerNameOf(PunishmentRecord target) {
        return Objects.requireNonNullElse(target.playerUsername(), UNKNOWN_PLAYER);
    }

    private record Rollback(Server server, PunishmentIssuer performer, @Nullable String reason, String auditedReason) {
    }

    @FunctionalInterface
    private interface TargetIds {
        List<String> after(@Nullable String afterId);
    }

    @FunctionalInterface
    private interface BulkTargetAction {
        boolean apply(PunishmentRecord target, Instant now);
    }
}
