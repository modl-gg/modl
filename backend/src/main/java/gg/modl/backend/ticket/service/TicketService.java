package gg.modl.backend.ticket.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.service.PunishmentModificationAuthorizer;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.QuickResponseSettings;
import gg.modl.backend.settings.data.TicketFormSettings;
import gg.modl.backend.settings.service.QuickResponseSettingsService;
import gg.modl.backend.settings.service.TicketFormSettingsService;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketNote;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.BulkTicketUpdateRequest;
import gg.modl.backend.ticket.dto.request.CreateTicketRequest;
import gg.modl.backend.ticket.dto.request.QuickResponseRequest;
import gg.modl.backend.ticket.dto.request.SubmitTicketFormRequest;
import gg.modl.backend.ticket.dto.request.UpdateTicketRequest;
import gg.modl.backend.ticket.dto.response.QuickResponseResult;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.repository.TicketSummary;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketService {
    private static final String NOT_FOUND_MESSAGE = "Ticket not found";
    private static final String ALREADY_SUBMITTED = "TICKET_ALREADY_SUBMITTED";
    private static final String DEFAULT_CREATOR_NAME = "API User";

    private final TicketRepository ticketRepository;
    private final TicketResponseAssembler ticketResponseAssembler;
    private final QuickResponseSettingsService quickResponseSettingsService;
    private final TicketFormSettingsService ticketFormSettingsService;
    private final TicketNotificationService notificationService;
    private final TicketInserter ticketInserter;
    private final TicketContentService contentService;
    private final TicketMessages ticketMessages;
    private final TicketUploads ticketUploads;
    private final TicketOpenedAnnouncer openedAnnouncer;
    private final ContactEmailPolicy contactEmailPolicy;
    private final TicketChangeAuthorizer changeAuthorizer;
    private final PunishmentModificationAuthorizer modificationAuthorizer;
    private final AppealWorkflowTransitionService appealTransitions;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public TicketResponse getTicketById(Server server, String ticketId) {
        return ticketResponseAssembler.toTicketResponse(server, requireTicket(ticketId));
    }

    public TicketResponse toResponse(Server server, Ticket ticket) {
        return ticketResponseAssembler.toTicketResponse(server, ticket);
    }

    public Optional<Ticket> getTicketRaw(Server server, String ticketId) {
        return ticketRepository.findById(ticketId);
    }

    public TicketResponse createTicket(Server server, CreateTicketRequest request) {
        return createTicketInternal(server, request, false);
    }

    public TicketResponse createUnfinishedTicket(Server server, CreateTicketRequest request) {
        return createTicketInternal(server, request, true);
    }

    private TicketResponse createTicketInternal(Server server, CreateTicketRequest request, boolean forceUnfinished) {
        requireWithinLimits(request);
        TicketCategory category = TicketCategory.fromCanonicalId(request.type());
        TicketCategories.requireOpenableAsTicket(category);
        String creatorEmail = contactEmailPolicy.accept(request.creatorEmail(), contactEmailPolicy.forTickets());
        boolean emailAuth = Boolean.TRUE.equals(request.emailAuthEnabled()) || requiresEmailAuth(server, category);
        List<Object> requestedAttachments = contentService.normalizeAttachments(request.attachments());

        Ticket created = unitOfWork.write(() -> {
            TicketUploads.Attached uploads = ticketUploads.attach(requestedAttachments, request.formData());
            Ticket ticket = newTicket(request, category, forceUnfinished, creatorEmail, emailAuth, uploads, clock.instant());
            Ticket saved = ticketInserter.insertNew(server, category.getTicketPrefix(), ticket);
            if (saved.getStatus() == TicketStatus.OPEN) {
                openedAnnouncer.opened(server, saved);
            }
            return saved;
        });
        return ticketResponseAssembler.toTicketResponse(server, created);
    }

    private static void requireWithinLimits(CreateTicketRequest request) {
        TicketInputLimits.requireLine("subject", request.subject());
        TicketInputLimits.requireLine("creatorIdentifier", request.creatorIdentifier());
        TicketInputLimits.requireText("description", request.description());
        TicketInputLimits.requireForm("formData", request.formData());
        TicketInputLimits.requireForm("fieldLabels", request.fieldLabels());
        TicketInputLimits.requireAttachments(request.attachments());
    }

    private static void requireWithinLimits(SubmitTicketFormRequest request) {
        TicketInputLimits.requireLine("subject", request.subject());
        TicketInputLimits.requireLine("creatorIdentifier", request.creatorIdentifier());
        TicketInputLimits.requireForm("formData", request.formData());
        TicketInputLimits.requireForm("fieldLabels", request.fieldLabels());
        TicketInputLimits.requireAttachments(request.attachments());
    }

    private Ticket newTicket(CreateTicketRequest request, TicketCategory category, boolean forceUnfinished,
                             @Nullable String creatorEmail, boolean emailAuth, TicketUploads.Attached uploads,
                             Instant now) {
        TicketContentService.FormDataProcessingResult formData = contentService.processFormDataForContent(uploads.formData());
        List<Object> attachments = contentService.mergeAttachments(uploads.attachments(), formData.attachments());
        boolean hasSubject = request.subject() != null && !request.subject().isBlank();
        TicketStatus status = !forceUnfinished && (category.isReport() || hasSubject)
            ? TicketStatus.OPEN
            : TicketStatus.UNFINISHED;
        String creatorName = request.creatorName() != null ? request.creatorName() : DEFAULT_CREATOR_NAME;

        Map<String, Object> data = new LinkedHashMap<>();
        if (creatorEmail != null) {
            data.put(TicketDataKeys.CREATOR_EMAIL, creatorEmail);
        }
        if (request.creatorIdentifier() != null) {
            data.put(TicketDataKeys.CREATOR_IDENTIFIER, request.creatorIdentifier());
        }

        List<TicketReply> replies = new ArrayList<>();
        String content = contentService.buildTicketContent(request, formData.formData());
        if (!content.isBlank() || !attachments.isEmpty()) {
            replies.add(openingReply(creatorName, content, attachments, request.creatorIdentifier(), now));
        }

        List<Map<String, Object>> chat = request.chatMessages();
        return Ticket.builder()
            .type(category)
            .subject(hasSubject ? request.subject() : category.getDisplayName())
            .status(status)
            .appealWorkflowStatus(category.isAppeal() ? AppealWorkflowStatus.OPEN : null)
            .creatorName(creatorName)
            .creatorUuid(UuidUtils.normalizeIfPresent(request.creatorUuid()))
            .reportedPlayer(request.reportedPlayerName())
            .reportedPlayerUuid(UuidUtils.normalizeIfPresent(request.reportedPlayerUuid()))
            .tags(request.tags() != null ? new ArrayList<>(request.tags()) : new ArrayList<>())
            .replies(replies)
            .chatMessages(chat == null || chat.isEmpty() ? null : contentService.sanitizeChatMessages(chat, now))
            .formData(uploads.formData() != null ? new LinkedHashMap<>(uploads.formData()) : null)
            .data(data)
            .locked(status.isTerminal())
            .priority(TicketPriority.resolveOrDefault(request.priority()))
            .emailAuthEnabled(emailAuth)
            .created(now)
            .updatedAt(now)
            .build();
    }

    public TicketResponse updateTicket(Server server, String ticketId, UpdateTicketRequest request, @Nullable String staffEmail) {
        changeAuthorizer.requirePermitted(server, staffEmail, TicketChange.requestedBy(request));
        Ticket updated = unitOfWork.write(() -> applyUpdate(server, ticketId, request));
        return ticketResponseAssembler.toTicketResponse(server, updated);
    }

    private Ticket applyUpdate(Server server, String ticketId, UpdateTicketRequest request) {
        Ticket ticket = requireTicket(ticketId);
        String id = ticket.getId();
        if (request.data() != null) {
            TicketDataKeys.requireProtectedUnchanged(ticket.getData(), request.data());
        }
        Instant now = clock.instant();
        TicketReply reply = request.newReply() != null ? ticketMessages.staffReply(request.newReply(), now) : null;
        if (reply != null) {
            reply.setAttachments(ticketUploads.attach(reply.getAttachments()));
        }
        TicketNote note = request.newNote() != null ? ticketMessages.requestedNote(request.newNote(), request.issuerName(), now) : null;
        if (note != null) {
            note.setAttachments(ticketUploads.attach(note.getAttachments()));
        }

        TicketStatus status = requestedStatus(ticket.getStatus(), request);
        Map<String, Object> data = request.data() != null && !request.data().isEmpty()
            ? merged(ticket.getData(), request.data())
            : null;
        if (status != null) {
            requireReopenable(id, ticket.getType(), ticket.appealedPunishmentId().orElse(null), ticket.getAppealWorkflowStatus(), status);
        }
        if (status != null || request.hidden() != null || data != null) {
            TicketHeaderPatch.TicketHeaderPatchBuilder patch = status != null
                ? TicketLifecycle.change(ticket.getType(), ticket.getAppealWorkflowStatus(), status, now)
                : TicketHeaderPatch.builder().updatedAt(now);
            ticketRepository.updateHeader(id, ticket.getVersion(), patch.hidden(request.hidden()).data(data).build());
        }
        if (request.tags() != null) {
            replaceTags(ticket, request.tags(), now);
        }
        if (request.assignedTo() != null) {
            ticketRepository.replaceAssignees(id, TicketAssigneeUtil.normalizeCollection(request.assignedTo()), now);
        }

        if (reply != null) {
            ticketRepository.appendReply(id, reply, now);
        }
        if (note != null) {
            ticketRepository.appendNote(id, note, now);
        }

        Ticket saved = requireTicket(id);
        if (reply != null && reply.isStaff()) {
            unitOfWork.afterCommit(() -> notificationService.notifyTicketReply(server, saved, reply));
        }
        if (!ticket.getStatus().isTerminal() && saved.getStatus().isTerminal()) {
            unitOfWork.afterCommit(() -> notificationService.notifyTicketClosed(server, saved));
        }
        return saved;
    }

    @Nullable
    private static TicketStatus requestedStatus(TicketStatus current, UpdateTicketRequest request) {
        TicketStatus requested = request.status() != null ? TicketStatus.fromCanonicalId(request.status()) : null;
        if (request.locked() == null) {
            return requested;
        }
        return statusForLock(requested != null ? requested : current, request.locked());
    }

    private void requireReopenable(String id, TicketCategory type, @Nullable String appealedPunishmentId,
                                   @Nullable AppealWorkflowStatus workflow, TicketStatus status) {
        if (type == TicketCategory.APPEAL) {
            appealTransitions.requireReopenable(
                id, appealedPunishmentId, workflow, AppealWorkflowStatus.afterLifecycleChange(workflow, status));
        }
    }

    private void replaceTags(Ticket ticket, List<String> tags, Instant now) {
        List<String> removed = ticket.getTags().stream().filter(tag -> !tags.contains(tag)).toList();
        ticketRepository.removeTags(ticket.getId(), removed, now);
        ticketRepository.addTags(ticket.getId(), tags, now);
    }

    public int bulkUpdateTickets(Server server, BulkTicketUpdateRequest request, @Nullable String staffEmail) {
        if (!hasBulkChanges(request)) {
            return 0;
        }
        changeAuthorizer.requirePermitted(server, staffEmail, TicketChange.requestedBy(request));
        return unitOfWork.write(() -> {
            List<TicketSummary> tickets = ticketRepository.findSummaries(request.ticketIds());
            Instant now = clock.instant();
            List<String> closed = new ArrayList<>();
            for (TicketSummary ticket : tickets) {
                if (applyBulkUpdate(ticket, request, now)) {
                    closed.add(ticket.getId());
                }
            }
            if (!closed.isEmpty()) {
                unitOfWork.afterCommit(() -> closed.forEach(id -> ticketRepository.findById(id)
                    .ifPresent(ticket -> notificationService.notifyTicketClosed(server, ticket))));
            }
            return tickets.size();
        });
    }

    private static boolean hasBulkChanges(BulkTicketUpdateRequest request) {
        return request.locked() != null
            || request.hidden() != null
            || request.assignTo() != null
            || (request.addLabels() != null && !request.addLabels().isEmpty())
            || (request.removeLabels() != null && !request.removeLabels().isEmpty());
    }

    private boolean applyBulkUpdate(TicketSummary ticket, BulkTicketUpdateRequest request, Instant now) {
        String id = ticket.getId();
        TicketStatus status = request.locked() != null ? statusForLock(ticket.getStatus(), request.locked()) : null;
        if (status != null) {
            requireReopenable(id, ticket.getType(), ticket.getAppealPunishmentId(), ticket.getAppealWorkflowStatus(), status);
        }
        if (status != null || request.hidden() != null) {
            TicketHeaderPatch.TicketHeaderPatchBuilder patch = status != null
                ? TicketLifecycle.change(ticket.getType(), ticket.getAppealWorkflowStatus(), status, now)
                : TicketHeaderPatch.builder().updatedAt(now);
            ticketRepository.updateHeader(id, ticket.getVersion(), patch.hidden(request.hidden()).build());
        }
        if (request.addLabels() != null && !request.addLabels().isEmpty()) {
            ticketRepository.addTags(id, request.addLabels(), now);
        }
        if (request.removeLabels() != null && !request.removeLabels().isEmpty()) {
            ticketRepository.removeTags(id, request.removeLabels(), now);
        }
        if (request.assignTo() != null) {
            ticketRepository.replaceAssignees(id, TicketAssigneeUtil.normalizeAssignment(request.assignTo()), now);
        }
        return status != null && status.isTerminal() && !ticket.getStatus().isTerminal();
    }

    public QuickResponseResult processQuickResponse(Server server, String ticketId, QuickResponseRequest request,
                                                    @Nullable String staffEmail, @Nullable String staffUsername) {
        QuickResponseSettings settings = quickResponseSettingsService.getQuickResponseSettings(server);
        QuickResponseSettings.Action action = settings != null
            ? quickResponseSettingsService.findAction(settings, request.categoryId(), request.actionId())
            : null;
        if (action != null) {
            changeAuthorizer.requirePermitted(server, staffEmail, TicketChange.requestedBy(action));
            if (action.approvesAppeal()) {
                modificationAuthorizer.requireAppealApproval(server, staffEmail);
            }
            if (action.reducesAppeal()) {
                modificationAuthorizer.shorteningBy(server, staffEmail).require();
            }
        }

        return unitOfWork.write(() -> {
            Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
            if (ticket == null) {
                return quickResponseFailure(NOT_FOUND_MESSAGE, null);
            }
            if (settings == null) {
                return quickResponseFailure("Quick response settings not found", ticketId);
            }
            if (action == null) {
                return quickResponseFailure("Action not found", ticketId);
            }
            return applyQuickResponse(server, ticket, action, staffUsername);
        });
    }

    private static QuickResponseResult quickResponseFailure(String message, @Nullable String ticketId) {
        return new QuickResponseResult(false, message, ticketId, null, false, false, null);
    }

    private QuickResponseResult applyQuickResponse(Server server, Ticket ticket, QuickResponseSettings.Action action,
                                                   @Nullable String staffUsername) {
        Instant now = clock.instant();
        boolean closes = Boolean.TRUE.equals(action.getCloseTicket());
        if (closes) {
            ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(),
                TicketLifecycle.change(ticket.getType(), ticket.getAppealWorkflowStatus(), TicketStatus.CLOSED, now).build());
        }
        TicketReply reply = ticketMessages.staffReply(staffUsername != null ? staffUsername : "System", action.getMessage(), null, null, now);
        ticketRepository.appendReply(ticket.getId(), reply, now);

        Ticket saved = requireTicket(ticket.getId());
        unitOfWork.afterCommit(() -> {
            notificationService.notifyTicketReply(server, saved, reply);
            if (closes) {
                notificationService.notifyTicketClosed(server, saved);
            }
        });
        return new QuickResponseResult(true, "Quick response applied successfully", ticket.getId(), action.getName(),
            closes, false, action.getAppealAction());
    }

    public TicketResponse submitTicketForm(Server server, String ticketId, SubmitTicketFormRequest request, boolean emailVerified) {
        requireWithinLimits(request);
        String requestedEmail = Optional
            .ofNullable(contactEmailPolicy.accept(request.creatorEmail(), contactEmailPolicy.forTickets()))
            .orElseGet(() -> contentService.legacyFormDataEmail(request.formData()));
        Ticket submitted = unitOfWork.write(() -> applySubmission(server, ticketId, request, requestedEmail, emailVerified));
        return ticketResponseAssembler.toTicketResponse(server, submitted);
    }

    private Ticket applySubmission(Server server, String ticketId, SubmitTicketFormRequest request,
                                   @Nullable String requestedEmail, boolean emailVerified) {
        Ticket ticket = requireTicket(ticketId);
        if (ticket.isLocked() || ticket.getStatus() != TicketStatus.UNFINISHED || ticket.getType() == TicketCategory.APPEAL) {
            throw new ConflictException("This ticket was already submitted", ALREADY_SUBMITTED);
        }
        Instant now = clock.instant();
        boolean emailAuth = ticket.isEmailAuthEnabled()
            || optsIntoEmailAuth(request) || requiresEmailAuth(server, ticket.getType());

        Map<String, Object> data = new LinkedHashMap<>();
        if (ticket.getData() != null) {
            data.putAll(ticket.getData());
        }
        Object existingEmail = data.get(TicketDataKeys.CREATOR_EMAIL);
        if (requestedEmail != null && (existingEmail == null || emailVerified)) {
            data.put(TicketDataKeys.CREATOR_EMAIL, requestedEmail);
        }
        if (request.creatorIdentifier() != null) {
            data.put(TicketDataKeys.CREATOR_IDENTIFIER, request.creatorIdentifier());
        }
        if (emailAuth) {
            data.put(TicketDataKeys.EMAIL_AUTH_ENABLED, true);
        }

        TicketUploads.Attached uploads =
            ticketUploads.attach(contentService.normalizeAttachments(request.attachments()), request.formData());
        Map<String, Object> submittedFormData = uploads.formData();
        Map<String, Object> storedFormData = submittedFormData == null || submittedFormData.isEmpty()
            ? null
            : merged(ticket.getFormData(), submittedFormData);
        TicketInputLimits.requireForm("formData", storedFormData);
        ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(), TicketHeaderPatch.builder()
            .updatedAt(now)
            .status(TicketStatus.OPEN)
            .locked(false)
            .subject(request.subject() != null && !request.subject().isBlank() ? request.subject() : null)
            .emailAuthEnabled(emailAuth)
            .data(data)
            .formData(storedFormData)
            .build());

        TicketContentService.FormDataProcessingResult formData = contentService.processFormDataForContent(uploads.formData());
        List<Object> attachments = contentService.mergeAttachments(uploads.attachments(), formData.attachments());
        if (ticket.getReplies().isEmpty()) {
            String content = contentService.buildFormDataContent(formData.formData(), request.fieldLabels());
            if (!content.isBlank() || !attachments.isEmpty()) {
                String author = ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player";
                ticketRepository.appendReply(
                    ticket.getId(), openingReply(author, content, attachments, request.creatorIdentifier(), now), now);
            }
        }
        Ticket submitted = requireTicket(ticket.getId());
        openedAnnouncer.opened(server, submitted);
        return submitted;
    }

    private static boolean optsIntoEmailAuth(SubmitTicketFormRequest request) {
        if (Boolean.TRUE.equals(request.emailAuthEnabled())) {
            return true;
        }
        Object formOptIn = request.formData() != null ? request.formData().get(TicketDataKeys.EMAIL_AUTH_ENABLED) : null;
        return formOptIn != null && Boolean.parseBoolean(formOptIn.toString());
    }

    public void closeTicketForPunishment(Server server, String ticketId, String issuerName) {
        unitOfWork.write(() -> {
            Instant now = clock.instant();
            boolean report = ticketRepository.findSummaries(List.of(ticketId)).stream()
                .anyMatch(ticket -> ticket.getType().isReport());
            if (!report || ticketRepository.closeIfUnlocked(List.of(ticketId), now) == 0) {
                return;
            }
            ticketRepository.appendReply(ticketId,
                ticketMessages.staffReply(issuerName, "Report accepted - punishment has been issued.", "report_accepted", null, now), now);
        });
    }

    public void reopenTicketForPunishment(Server server, String ticketId, String issuerName) {
        unitOfWork.write(() -> ticketRepository.findSummaries(List.of(ticketId)).stream()
            .filter(ticket -> ticket.getType().isReport())
            .findFirst()
            .ifPresent(ticket -> ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(),
                TicketLifecycle.change(ticket.getType(), ticket.getAppealWorkflowStatus(), TicketStatus.OPEN, clock.instant()).build())));
    }

    public Set<String> getPublicFormFieldIds(Server server, Ticket ticket) {
        if (ticket == null || ticket.getType() == null) {
            return Set.of();
        }
        TicketFormSettings.TicketForm form = ticketFormSettingsService.getFormByType(server, ticket.getType().getId());
        if (form == null || form.getFields() == null) {
            return Set.of();
        }
        return form.getFields().stream()
            .map(TicketFormSettings.FormField::getId)
            .filter(id -> id != null && !id.isBlank())
            .collect(Collectors.toSet());
    }

    private boolean requiresEmailAuth(Server server, TicketCategory category) {
        TicketFormSettings.TicketForm form = ticketFormSettingsService.getFormByType(server, category.getId());
        return form != null && form.isRequireEmailAuth();
    }

    private Ticket requireTicket(String ticketId) {
        return ticketRepository.findById(ticketId).orElseThrow(() -> new ResourceNotFoundException(NOT_FOUND_MESSAGE));
    }

    private static TicketReply openingReply(String author, String content, List<Object> attachments,
                                            @Nullable String creatorIdentifier, Instant now) {
        return TicketReply.builder()
            .id(EntityIds.next())
            .name(author)
            .content(content)
            .type("user")
            .created(now)
            .staff(false)
            .attachments(attachments)
            .creatorIdentifier(creatorIdentifier)
            .build();
    }

    private static Map<String, Object> merged(@Nullable Map<String, Object> existing, Map<String, Object> changes) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        merged.putAll(changes);
        return merged;
    }

    private static TicketStatus statusForLock(TicketStatus current, boolean locked) {
        if (locked) {
            return TicketStatus.CLOSED;
        }
        return current == TicketStatus.UNFINISHED ? TicketStatus.UNFINISHED : TicketStatus.OPEN;
    }
}
