package gg.modl.backend.player.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.PunishmentOptionChange;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.player.data.punishment.PunishmentToggleOption;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.player.dto.request.ModifyPunishmentTicketsRequest;
import gg.modl.backend.player.repository.PunishmentPatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.service.AppealWorkflowTransitionService;
import gg.modl.backend.ticket.service.TicketService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentMutationService {
    private static final long PERMANENT_DURATION = -1L;
    static final String STAT_WIPE_NOT_DUE = "No stat wipe is due for this punishment";

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PunishmentRepository punishmentRepository;
    private final TicketService ticketService;
    private final AppealWorkflowTransitionService appealWorkflowTransitionService;
    private final IssuerNameResolver issuerNameResolver;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentRealtimePublisher realtimePublisher;
    private final PlayerStatusCalculator statusCalculator;

    public void addModification(Server server, UUID playerUuid, String punishmentId, AddModificationRequest request,
                                PunishmentShortening shortening) {
        PunishmentDurationLimit.require(request.effectiveDuration());
        requireNotRollback(request.type());
        PunishmentOptionChange optionChange = PunishmentModificationType.optionChange(request.type());
        unitOfWork.write(() -> {
            PunishmentRecord target = OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
            if (PunishmentModificationType.isPardon(request.type()) && target.punishment().hasEndedEnforcement()) {
                appealDecisionOf(request).ifPresent(decision -> punishmentRepository.patch(punishmentId, target.version(),
                    PunishmentPatch.appealDecision(decision.outcome(), decision.appealTicketId())));
                syncLinkedAppealOutcome(server, punishmentId, request);
                return;
            }
            if (optionChange != null) {
                punishmentLifecycleService.requireOptionOffered(server, target.punishment(), optionChange);
            }
            Instant now = clock.instant();
            Long effectiveDuration = effectiveDurationOf(request);
            if (effectiveDuration != null) {
                shortening.requireFor(target.punishment(), effectiveDuration, now);
            }
            PunishmentModification modification = new PunishmentModification(
                IdGenerator.generateShortId(),
                request.type(),
                now,
                PunishmentMapper.storedName(request.issuerId(), request.issuerName()),
                request.issuerId(),
                request.reason() != null ? request.reason() : "",
                effectiveDuration,
                request.appealTicketId(),
                null
            );
            PunishmentPatch change = optionChange != null
                ? optionPatch(optionChange)
                : patchFor(request.type(), effectiveDuration);
            PunishmentPatch patch = appealDecisionOf(request)
                .map(decision -> change.withAppealDecision(decision.outcome(), decision.appealTicketId()))
                .orElse(change);
            punishmentRepository.appendModification(punishmentId, target.version(), modification, patch, List.of());
            if (optionChange == null) {
                punishmentLifecycleService.promoteAfterCommit(server, target.playerId());
            }
            if (optionChange != null) {
                cascadeOptionChange(server, punishmentId, optionChange, shortening);
            } else if (target.punishment().data().altBlocking()) {
                cascadeToLinkedBans(server, punishmentId, request.type(), effectiveDuration, shortening);
            }
            syncLinkedAppealOutcome(server, punishmentId, request);
            publishChange(server, punishmentId, request.type());
        });
    }

    @Nullable
    private static Long effectiveDurationOf(AddModificationRequest request) {
        if (!PunishmentModificationType.isDurationChange(request.type())) {
            return null;
        }
        Long requested = request.effectiveDuration();
        return requested != null ? requested : Long.valueOf(PERMANENT_DURATION);
    }

    private static PunishmentPatch patchFor(String type, @Nullable Long effectiveDuration) {
        if (PunishmentModificationType.isPardon(type)) {
            return PunishmentPatch.builder().status(PunishmentStatus.PARDONED).build();
        }
        if (PunishmentModificationType.isDurationChange(type)) {
            return PunishmentPatch.builder().duration(effectiveDuration).build();
        }
        return PunishmentPatch.NONE;
    }

    private void cascadeToLinkedBans(Server server, String parentId, String type, @Nullable Long effectiveDuration,
                                     PunishmentShortening shortening) {
        if (PunishmentModificationType.isPardon(type)) {
            punishmentLifecycleService.cascadePardonLinkedBans(server, parentId);
        } else if (PunishmentModificationType.isDurationChange(type)) {
            punishmentLifecycleService.cascadeDurationChangeToLinkedBans(server, parentId, effectiveDuration, shortening);
        }
    }

    private static void requireNotRollback(String type) {
        if (PunishmentModificationType.fromName(type) == PunishmentModificationType.ROLLBACK) {
            throw new ValidationException("Punishments are rolled back from the audit page", "INVALID_ARGUMENT");
        }
    }

    private void syncLinkedAppealOutcome(Server server, String punishmentId, AddModificationRequest request) {
        appealDecisionOf(request).ifPresent(decision -> {
            String issuerName = issuerNameResolver.resolve(request.issuerId(), request.issuerName(), server);
            appealWorkflowTransitionService.applyOutcomeForPunishment(
                server, decision.appealTicketId(), punishmentId, decision.outcome(), issuerName);
        });
    }

    private static Optional<AppealDecision> appealDecisionOf(AddModificationRequest request) {
        String appealTicketId = request.appealTicketId();
        PunishmentModificationType modificationType = PunishmentModificationType.fromName(request.type());
        AppealWorkflowStatus outcome = modificationType != null ? modificationType.appealOutcome() : null;
        if (appealTicketId == null || outcome == null || !outcome.isDecision()) {
            return Optional.empty();
        }
        return Optional.of(new AppealDecision(outcome, appealTicketId));
    }

    private record AppealDecision(AppealWorkflowStatus outcome, String appealTicketId) {
    }

    public PunishmentOperationResult changeDuration(Server server, String punishmentId, @Nullable Long newDuration,
                                                    @Nullable String issuerName, @Nullable String issuerId,
                                                    PunishmentShortening shortening) {
        PunishmentDurationLimit.require(newDuration);
        long effective = newDuration != null ? newDuration : PERMANENT_DURATION;
        String storedName = PunishmentMapper.storedName(issuerId, issuerName);

        return unitOfWork.write(() -> {
            PunishmentRecord target = punishmentRepository.findById(punishmentId).orElse(null);
            if (target == null) {
                return notFound("Punishment not found");
            }
            Punishment punishment = target.punishment();
            Instant now = clock.instant();
            shortening.requireFor(punishment, effective, now);
            PunishmentModification modification = PunishmentEntries.modification(
                PunishmentModificationType.MANUAL_DURATION_CHANGE, now, storedName, issuerId, "Duration changed", effective);
            String durationText = effective <= 0 ? "permanent" : PunishmentMapper.formatDuration(effective, false);
            PunishmentNote note = PunishmentEntries.note("changed duration to " + durationText, now, storedName, issuerId);

            punishmentRepository.appendModification(punishmentId, target.version(), modification,
                PunishmentPatch.builder().duration(effective).build(), List.of(note));
            punishmentLifecycleService.promoteAfterCommit(server, target.playerId());
            publishModified(server, punishmentId);

            int cascaded = punishment.data().altBlocking()
                ? punishmentLifecycleService.cascadeDurationChangeToLinkedBans(server, punishmentId, newDuration, shortening)
                : 0;
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, durationChangedMessage(cascaded), true, cascaded + 1);
        });
    }

    private static String durationChangedMessage(int cascaded) {
        if (cascaded == 0) {
            return "Duration changed";
        }
        return "Duration changed (cascaded to " + cascaded + " linked ban" + (cascaded > 1 ? "s" : "") + ")";
    }

    public PunishmentOperationResult toggleOption(Server server, String punishmentId, String option, boolean enabled,
                                                  @Nullable String issuerName, @Nullable String issuerId,
                                                  PunishmentShortening shortening) {
        PunishmentToggleOption toggleOption = PunishmentToggleOption.from(option);
        if (toggleOption == null) {
            return new PunishmentOperationResult(PunishmentOperationStatus.INVALID_REQUEST, "Invalid option", false, 0);
        }

        return unitOfWork.write(() -> {
            PunishmentRecord target = punishmentRepository.findById(punishmentId).orElse(null);
            if (target == null) {
                return notFound("Punishment not found");
            }
            PunishmentOptionChange change = new PunishmentOptionChange(toggleOption, enabled);
            punishmentLifecycleService.requireOptionOffered(server, target.punishment(), change);
            punishmentRepository.patch(punishmentId, target.version(), optionPatch(change));
            cascadeOptionChange(server, punishmentId, change, shortening);
            punishmentRepository.appendNote(punishmentId, PunishmentEntries.note(
                (enabled ? "enabled " : "disabled ") + toggleOption.displayName(),
                clock.instant(),
                PunishmentMapper.storedName(issuerId, issuerName),
                issuerId));
            publishModified(server, punishmentId);
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Option toggled", true, 1);
        });
    }

    private static PunishmentPatch optionPatch(PunishmentOptionChange change) {
        return switch (change.option()) {
            case ALT_BLOCKING -> PunishmentPatch.builder().altBlocking(change.enabled()).build();
            case STAT_WIPE -> PunishmentPatch.builder().wipeAfterExpiry(change.enabled()).build();
        };
    }

    private void cascadeOptionChange(Server server, String punishmentId, PunishmentOptionChange change,
                                     PunishmentShortening shortening) {
        if (change.endsAltBlocking()) {
            punishmentLifecycleService.liftAutomaticLinkedBans(server, punishmentId, shortening);
        }
    }

    public PunishmentOperationResult acknowledgeStatWipe(Server server, String punishmentId) {
        return unitOfWork.write(() -> {
            PunishmentRecord target = punishmentRepository.findById(punishmentId).orElse(null);
            if (target == null) {
                return notFound("Punishment not found");
            }
            if (!target.punishment().data().wipeAfterExpiry()) {
                return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP,
                    "Stat wipe no longer enabled for this punishment", false, 0);
            }
            if (!statusCalculator.isStatWipeDue(target.punishment())) {
                return new PunishmentOperationResult(PunishmentOperationStatus.NO_OP, STAT_WIPE_NOT_DUE, false, 0);
            }
            punishmentRepository.patch(punishmentId, target.version(),
                PunishmentPatch.builder().statWipeCompletedAt(clock.instant()).build());
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Stat wipe acknowledged", true, 1);
        });
    }

    public PunishmentOperationResult modifyPunishmentTickets(Server server, String punishmentId, ModifyPunishmentTicketsRequest request) {
        return unitOfWork.write(() -> {
            PunishmentRecord target = punishmentRepository.findById(punishmentId).orElse(null);
            if (target == null) {
                return notFound("Failed to modify punishment tickets");
            }
            applyTicketChanges(server, target, request);
            return new PunishmentOperationResult(PunishmentOperationStatus.SUCCESS, "Punishment tickets modified", true, 1);
        });
    }

    public void modifyPunishmentTickets(Server server, UUID playerUuid, String punishmentId, ModifyPunishmentTicketsRequest request) {
        unitOfWork.write(() -> {
            PunishmentRecord target = OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
            applyTicketChanges(server, target, request);
            PunishmentRecord changed = OwnedPunishments.require(punishmentRepository, playerUuid, punishmentId);
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentDetailsChanged(server, changed));
        });
    }

    private void applyTicketChanges(Server server, PunishmentRecord target, ModifyPunishmentTicketsRequest request) {
        String punishmentId = target.punishment().getId();
        Set<String> original = new HashSet<>(target.punishment().getAttachedTicketIds());
        Set<String> updated = new LinkedHashSet<>(target.punishment().getAttachedTicketIds());
        if (request.addTicketIds() != null) {
            updated.addAll(request.addTicketIds());
        }
        if (request.removeTicketIds() != null) {
            request.removeTicketIds().forEach(updated::remove);
        }

        punishmentRepository.patch(punishmentId, target.version(), PunishmentPatch.NONE);
        punishmentRepository.replaceTickets(punishmentId, List.copyOf(updated));
        if (!request.modifyAssociatedTickets()) {
            return;
        }

        Set<String> changedInLockOrder = new TreeSet<>();
        updated.stream().filter(id -> !original.contains(id)).forEach(changedInLockOrder::add);
        original.stream().filter(id -> !updated.contains(id)).forEach(changedInLockOrder::add);
        if (changedInLockOrder.isEmpty()) {
            return;
        }
        String issuerName = issuerNameResolver.resolve(request.issuerId(), request.issuerName(), server);
        for (String ticketId : changedInLockOrder) {
            if (updated.contains(ticketId)) {
                ticketService.closeTicketForPunishment(server, ticketId, issuerName);
            } else {
                ticketService.reopenTicketForPunishment(server, ticketId, issuerName);
            }
        }
    }

    public void linkAppealToPunishment(Server server, String playerUuid, String punishmentId,
                                       String appealId, PunishmentNote note) {
        unitOfWork.write(() -> {
            if (OwnedPunishments.find(punishmentRepository, playerUuid, punishmentId).isPresent()) {
                punishmentRepository.attachTicket(punishmentId, appealId, note);
            }
        });
    }

    public void recordAppealRejection(Server server, String playerUuid, String punishmentId,
                                      PunishmentNote note, String appealTicketId) {
        unitOfWork.write(() -> {
            PunishmentRecord target = OwnedPunishments.find(punishmentRepository, playerUuid, punishmentId).orElse(null);
            if (target == null) {
                return;
            }
            punishmentRepository.patch(punishmentId, target.version(),
                PunishmentPatch.appealDecision(AppealWorkflowStatus.REJECTED, appealTicketId));
            punishmentRepository.appendNote(punishmentId, note);
        });
    }

    public void applyAppealApproval(Server server, String playerUuid, String punishmentId,
                                    PunishmentModification modification, PunishmentNote note, String appealTicketId) {
        PunishmentDurationLimit.require(modification.effectiveDuration());
        unitOfWork.write(() -> {
            PunishmentRecord target = OwnedPunishments.find(punishmentRepository, playerUuid, punishmentId).orElse(null);
            if (target == null) {
                return;
            }
            punishmentRepository.appendModification(punishmentId, target.version(), modification,
                PunishmentPatch.appealDecision(AppealWorkflowStatus.APPROVED, appealTicketId),
                List.of(note));
            punishmentLifecycleService.promoteAfterCommit(server, target.playerId());
            publishChange(server, punishmentId, modification.type());
        });
    }

    private void publishModified(Server server, String punishmentId) {
        punishmentRepository.findById(punishmentId).ifPresent(changed ->
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentModified(server, changed)));
    }

    private void publishChange(Server server, String punishmentId, String appendedType) {
        if (!PunishmentModificationType.isPardon(appendedType)) {
            publishModified(server, punishmentId);
            return;
        }
        punishmentRepository.findById(punishmentId).ifPresent(pardoned ->
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentPardoned(server, pardoned)));
    }

    private static PunishmentOperationResult notFound(String message) {
        return new PunishmentOperationResult(PunishmentOperationStatus.NOT_FOUND, message, false, 0);
    }
}
