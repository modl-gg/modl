package gg.modl.backend.appeal.service;

import gg.modl.backend.appeal.dto.request.AddAppealReplyRequest;
import gg.modl.backend.appeal.dto.request.CreateAppealRequest;
import gg.modl.backend.appeal.dto.request.UpdateAppealStatusRequest;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.repository.PunishmentPatch;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.player.service.PunishmentLifecycleService;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.player.service.PunishmentMutationService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.ticket.repository.TicketSummary;
import gg.modl.backend.ticket.data.AppealSubject;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.service.AppealWorkflowTransitionService;
import gg.modl.backend.ticket.service.ContactEmailPolicy;
import gg.modl.backend.ticket.service.TicketContentService;
import gg.modl.backend.ticket.service.TicketDataKeys;
import gg.modl.backend.ticket.service.TicketInputLimits;
import gg.modl.backend.ticket.service.TicketInserter;
import gg.modl.backend.ticket.service.TicketOpenedAnnouncer;
import gg.modl.backend.ticket.service.TicketMessages;
import gg.modl.backend.ticket.service.TicketReplyAppender;
import gg.modl.backend.ticket.service.TicketResponseAssembler;
import gg.modl.backend.ticket.service.TicketUploads;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppealService {
    private static final String NOT_FOUND_MESSAGE = "Appeal not found";
    private static final String LOCKED_MESSAGE = "Appeal is locked and cannot accept new replies";
    private static final String RESOLUTION_SET_ACTION = "RESOLUTION_SET";
    private static final String SYSTEM_ISSUER = "System";
    private static final String UNKNOWN_PLAYER = "Unknown";
    static final String NOT_APPEALABLE = "PUNISHMENT_NOT_APPEALABLE";

    private final TicketRepository ticketRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentMutationService punishmentMutationService;
    private final PunishmentLifecycleService punishmentLifecycleService;
    private final PunishmentModificationAuthorizer modificationAuthorizer;
    private final TicketInserter ticketInserter;
    private final TicketOpenedAnnouncer openedAnnouncer;
    private final AppealWorkflowTransitionService workflowTransitionService;
    private final TicketContentService contentService;
    private final TicketMessages ticketMessages;
    private final TicketReplyAppender replyAppender;
    private final TicketUploads ticketUploads;
    private final TicketResponseAssembler ticketResponseAssembler;
    private final ContactEmailPolicy contactEmailPolicy;
    private final PunishmentTypeService punishmentTypeService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<TicketResponse> getAppealsByPunishment(Server server, String punishmentId) {
        return ticketResponseAssembler.toTicketResponses(server, ticketRepository.findAppealsByPunishmentId(punishmentId));
    }

    public TicketResponse getAppealById(Server server, String appealId) {
        return ticketResponseAssembler.toTicketResponse(server, requireAppeal(appealId));
    }

    public Optional<Ticket> getAppealRaw(Server server, String appealId) {
        return ticketRepository.findById(appealId).filter(ticket -> ticket.getType() == TicketCategory.APPEAL);
    }

    public TicketResponse toResponse(Server server, Ticket appeal) {
        return ticketResponseAssembler.toTicketResponse(server, appeal);
    }

    public TicketResponse createAppeal(Server server, CreateAppealRequest request) {
        requireWithinLimits(request);
        Ticket created = unitOfWork.write(() -> submitAppeal(server, request));
        return ticketResponseAssembler.toTicketResponse(server, created);
    }

    private static void requireWithinLimits(CreateAppealRequest request) {
        TicketInputLimits.requireText("reason", request.reason());
        TicketInputLimits.requireText("evidence", request.evidence());
        TicketInputLimits.requireForm("additionalData", request.additionalData());
        TicketInputLimits.requireForm("fieldLabels", request.fieldLabels());
        TicketInputLimits.requireAttachments(request.attachments());
    }

    private Ticket submitAppeal(Server server, CreateAppealRequest request) {
        String playerUuid = UuidUtils.normalize(request.playerUuid());
        PunishmentRecord appealed = punishmentRepository.findById(request.punishmentId())
            .filter(punishment -> playerUuid.equals(UuidUtils.normalize(punishment.playerMinecraftUuid())))
            .orElseThrow(() -> new ResourceNotFoundException("Punishment not found for the specified player"));
        if (appealed.punishment().getStarted() == null) {
            throw new PunishmentNotStartedException();
        }
        if (!punishmentTypeService.isAppealable(server, appealed.punishment().getTypeOrdinal())) {
            throw new ValidationException("This punishment cannot be appealed", NOT_APPEALABLE);
        }
        String contactEmail = contactEmailPolicy.accept(request.email(), contactEmailPolicy.forAppeals());

        String punishmentId = appealed.punishment().getId();
        punishmentRepository.patch(punishmentId, appealed.version(), PunishmentPatch.NONE);
        workflowTransitionService.requireNoOtherAppealInProgress(punishmentId, null);

        Instant now = clock.instant();
        TicketUploads.Attached uploads = ticketUploads.attach(
            contentService.normalizeAttachments(request.attachments()),
            request.additionalData() != null ? request.additionalData() : Map.of());
        Map<String, Object> answers = new LinkedHashMap<>(uploads.formData());
        TicketContentService.FormDataProcessingResult processedAnswers = contentService.processFormDataForContent(answers);
        List<Object> attachments = contentService.mergeAttachments(uploads.attachments(), processedAnswers.attachments());
        String username = appealed.playerUsername() != null ? appealed.playerUsername() : UNKNOWN_PLAYER;

        Map<String, Object> data = new LinkedHashMap<>();
        new AppealSubject(punishmentId, playerUuid).writeTo(data);
        if (contactEmail != null) {
            data.put(TicketDataKeys.CONTACT_EMAIL, contactEmail);
        }

        TicketReply initialReply = TicketReply.builder()
            .id(EntityIds.next())
            .name(username)
            .content(buildInitialContent(request, processedAnswers.formData()))
            .type("player")
            .created(now)
            .staff(false)
            .attachments(attachments)
            .build();

        Ticket appeal = Ticket.builder()
            .type(TicketCategory.APPEAL)
            .status(TicketStatus.OPEN)
            .appealWorkflowStatus(AppealWorkflowStatus.OPEN)
            .subject("Appeal for punishment " + punishmentId)
            .creatorName(username)
            .creatorUuid(playerUuid)
            .replies(new ArrayList<>(List.of(initialReply)))
            .data(data)
            .formData(answers)
            .locked(false)
            .created(now)
            .updatedAt(now)
            .build();

        Ticket saved = ticketInserter.insertNew(server, TicketCategory.APPEAL.getTicketPrefix(), appeal);
        punishmentMutationService.linkAppealToPunishment(server, playerUuid, punishmentId, saved.getId(),
            punishmentNote("opened appeal (#" + saved.getId() + ")", SYSTEM_ISSUER, now));
        openedAnnouncer.opened(server, saved);
        return saved;
    }

    private String buildInitialContent(CreateAppealRequest request, Map<String, Object> answers) {
        StringBuilder content = new StringBuilder();

        if (request.reason() != null && !request.reason().isBlank()) {
            content.append(request.reason()).append("\n");
        }

        if (request.evidence() != null && !request.evidence().isBlank()) {
            content.append("Evidence: ").append(request.evidence()).append("\n");
        }

        String answerContent = contentService.buildFormDataContent(answers, request.fieldLabels());
        if (!answerContent.isEmpty()) {
            content.append("\n").append(answerContent).append("\n");
        }

        String body = content.toString().trim();
        if (body.isEmpty()) {
            return "Appeal submitted for punishment " + request.punishmentId() + ".";
        }
        return body;
    }

    public TicketReply addReply(Server server, String appealId, AddAppealReplyRequest request) {
        return unitOfWork.write(() -> {
            Ticket appeal = requireAppeal(appealId);
            Instant now = clock.instant();
            TicketReply reply = ticketMessages.staffReply(
                request.name(), request.content(), request.action(), request.attachments(), now);
            return replyAppender.appendUnlessLocked(appeal, reply, LOCKED_MESSAGE, now);
        });
    }

    public TicketReply addPublicReply(Server server, String appealId, String content, @Nullable List<Object> attachments) {
        return unitOfWork.write(() -> {
            TicketSummary appeal = ticketRepository.findSummaries(List.of(appealId)).stream()
                .filter(summary -> summary.getType() == TicketCategory.APPEAL)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
            Instant now = clock.instant();
            TicketReply reply = ticketMessages.playerReply(appeal.getCreatorName(), content, attachments, now);
            return replyAppender.appendPlayerReply(appeal.getId(), reply, LOCKED_MESSAGE, now);
        });
    }

    public TicketResponse updateStatus(Server server, String appealId, UpdateAppealStatusRequest request,
                                       @Nullable String staffEmail) {
        Ticket updated = unitOfWork.write(() -> changeStatus(server, appealId, request, staffEmail));
        return ticketResponseAssembler.toTicketResponse(server, updated);
    }

    private Ticket changeStatus(Server server, String appealId, UpdateAppealStatusRequest request,
                                @Nullable String staffEmail) {
        Ticket appeal = requireAppeal(appealId);
        AppealWorkflowStatus requested = request.status() != null && !request.status().isBlank()
            ? AppealWorkflowStatus.fromCanonicalId(request.status())
            : null;
        workflowTransitionService.requireReopenBeforeChangingTerminalStatus(appeal.getAppealWorkflowStatus(), requested);
        workflowTransitionService.requireReopenable(
            appeal.getId(), appeal.appealedPunishmentId().orElse(null), appeal.getAppealWorkflowStatus(), requested);

        Instant now = clock.instant();
        String staffUsername = request.staffUsername();
        List<TicketReply> systemReplies = new ArrayList<>();
        TicketHeaderPatch.TicketHeaderPatchBuilder patch = TicketHeaderPatch.builder().updatedAt(now);
        boolean locked = appeal.isLocked();

        if (requested != null && requested != appeal.getAppealWorkflowStatus()) {
            decidePunishment(server, appeal, requested, staffEmail, staffUsername, now);
            TicketStatus lifecycleStatus = requested.toTicketStatus();
            locked = lifecycleStatus.isTerminal();
            patch.appealWorkflowStatus(requested).status(lifecycleStatus);
            systemReplies.add(workflowTransitionService.statusChangeReply(staffUsername, requested, now));
        }

        String resolution = request.resolution();
        if (resolution != null) {
            if (resolution.codePointCount(0, resolution.length()) > RequestValidationLimits.APPEAL_RESOLUTION_MAX_LENGTH) {
                throw new ValidationException("The resolution must be at most "
                    + RequestValidationLimits.APPEAL_RESOLUTION_MAX_LENGTH + " characters");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            if (appeal.getData() != null) {
                data.putAll(appeal.getData());
            }
            if (!resolution.equals(data.get(TicketDataKeys.RESOLUTION))) {
                data.put(TicketDataKeys.RESOLUTION, resolution);
                patch.data(data);
                systemReplies.add(TicketMessages.systemReply(
                    staffUsername, "Resolution set to " + resolution + ".", RESOLUTION_SET_ACTION, now));
            }
        }

        if (request.locked() != null && request.locked() != locked) {
            locked = request.locked();
            systemReplies.add(TicketMessages.systemReply(
                staffUsername, "Ticket " + (locked ? "locked" : "unlocked") + ".", locked ? "LOCKED" : "UNLOCKED", now));
        }

        ticketRepository.updateHeader(appeal.getId(), appeal.getVersion(), patch.locked(locked).build());
        systemReplies.forEach(reply -> ticketRepository.appendReply(appeal.getId(), reply, now));
        return requireAppeal(appeal.getId());
    }

    private void decidePunishment(Server server, Ticket appeal, AppealWorkflowStatus decision,
                                  @Nullable String staffEmail, @Nullable String staffUsername, Instant now) {
        String staffName = staffUsername != null ? staffUsername : SYSTEM_ISSUER;
        appeal.appealSubject().ifPresent(subject -> {
            if (decision == AppealWorkflowStatus.APPROVED) {
                modificationAuthorizer.requireAppealApproval(server, staffEmail);
                approve(server, appeal, subject, staffName, now);
            } else if (decision == AppealWorkflowStatus.REJECTED) {
                punishmentMutationService.recordAppealRejection(server, subject.playerUuid(), subject.punishmentId(),
                    punishmentNote("rejected appeal (#" + appeal.getId() + ")", staffName, now), appeal.getId());
            }
        });
    }

    private void approve(Server server, Ticket appeal, AppealSubject subject, String staffName, Instant now) {
        PunishmentModification modification = new PunishmentModification(
            IdGenerator.generateShortId(),
            PunishmentModificationType.APPEAL_ACCEPT.name(),
            now,
            staffName,
            null,
            "Appeal approved",
            null,
            appeal.getId(),
            null
        );
        punishmentMutationService.applyAppealApproval(server, subject.playerUuid(), subject.punishmentId(),
            modification, punishmentNote("accepted appeal (#" + appeal.getId() + ")", staffName, now), appeal.getId());

        punishmentRepository.findById(subject.punishmentId())
            .filter(appealed -> subject.playerUuid().equals(UuidUtils.normalize(appealed.playerMinecraftUuid())))
            .filter(appealed -> appealed.punishment().data().altBlocking())
            .ifPresent(appealed -> punishmentLifecycleService.cascadePardonLinkedBans(server, subject.punishmentId()));
    }

    private Ticket requireAppeal(String appealId) {
        return ticketRepository.findById(appealId)
            .filter(ticket -> ticket.getType() == TicketCategory.APPEAL)
            .orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    private static PunishmentNote punishmentNote(String text, String issuerName, Instant now) {
        return new PunishmentNote(IdGenerator.generateShortId(), text, now, issuerName, null);
    }
}
