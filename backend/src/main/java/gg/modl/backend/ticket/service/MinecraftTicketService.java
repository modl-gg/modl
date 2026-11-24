package gg.modl.backend.ticket.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.replay.util.ReplayReferenceUtil;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.data.AppealWorkflowStatus;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketPriority;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.dto.request.AssignReportRequest;
import gg.modl.backend.ticket.dto.request.MinecraftClaimTicketRequest;
import gg.modl.backend.ticket.dto.request.MinecraftCreateTicketRequest;
import gg.modl.backend.ticket.dto.response.MinecraftPlayerTicketView;
import gg.modl.backend.ticket.dto.response.MinecraftReportView;
import gg.modl.backend.ticket.dto.response.MinecraftTicketDetailReplyView;
import gg.modl.backend.ticket.dto.response.MinecraftTicketDetailView;
import gg.modl.backend.ticket.dto.response.MinecraftTicketListItemView;
import gg.modl.backend.ticket.dto.response.MinecraftTicketLookupView;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.ticket.repository.ListedTicket;
import gg.modl.backend.ticket.repository.ThreadDigest;
import gg.modl.backend.ticket.repository.TicketHeaderPatch;
import gg.modl.backend.ticket.repository.TicketRepository;
import gg.modl.backend.ticket.util.TicketAssigneeUtil;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftTicketService {
    private static final Pattern CHAT_LINE_PATTERN =
        Pattern.compile("^(?:(\\d{1,2}:\\d{2}:\\d{2})\\s+)?([^:]{1,48}):\\s(.*)$", Pattern.DOTALL);
    private static final DateTimeFormatter CHAT_TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm:ss");

    private final TicketRepository ticketRepository;
    private final TicketNotificationService notificationService;
    private final TicketInserter ticketInserter;
    private final TicketOpenedAnnouncer openedAnnouncer;
    private final TicketPublicLinks ticketPublicLinks;
    private final PublicRecordAccessService recordAccessService;
    private final MinecraftIssuerAuthorizer issuerAuthorizer;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public Ticket createMinecraftTicket(Server server, MinecraftCreateTicketRequest request) {
        return unitOfWork.write(() -> createMinecraftTicketInternal(server, request, false));
    }

    private Ticket createMinecraftTicketInternal(Server server, MinecraftCreateTicketRequest request, boolean unfinished) {
        TicketCategory ticketCategory = TicketCategory.fromCanonicalId(request.type());
        TicketCategories.requireOpenableAsTicket(ticketCategory);
        Instant now = clock.instant();

        List<Ticket.ChatMessage> chatMessages = new ArrayList<>();
        if (!unfinished && request.chatMessages() != null && !request.chatMessages().isEmpty()) {
            for (String message : request.chatMessages()) {
                if (message == null || message.isBlank()) {
                    continue;
                }
                chatMessages.add(parseChatMessage(message, now));
            }
        }

        Map<String, Object> ticketData = new HashMap<>();
        if (request.createdServer() != null && !request.createdServer().isBlank()) {
            ticketData.put("createdServer", request.createdServer());
        }

        Ticket ticket = Ticket.builder()
            .type(ticketCategory)
            .subject(request.subject())
            .status(unfinished ? TicketStatus.UNFINISHED : TicketStatus.OPEN)
            .appealWorkflowStatus(ticketCategory.isAppeal() ? AppealWorkflowStatus.OPEN : null)
            .creatorUuid(UuidUtils.normalize(request.creatorUuid()))
            .creatorName(request.creatorName())
            .reportedPlayer(request.reportedPlayerName())
            .reportedPlayerUuid(UuidUtils.normalizeIfPresent(request.reportedPlayerUuid()))
            .tags(request.tags() != null ? new ArrayList<>(request.tags()) : new ArrayList<>())
            .replies(new ArrayList<>())
            .notes(new ArrayList<>())
            .chatMessages(unfinished ? null : chatMessages)
            .data(ticketData.isEmpty() ? null : ticketData)
            .priority(TicketPriority.resolveOrDefault(request.priority()))
            .replayUrl(request.replayUrl())
            .replayId(ReplayReferenceUtil.extractReplayId(request.replayUrl()))
            .locked(false)
            .created(now)
            .updatedAt(now)
            .build();

        if (!unfinished && request.description() != null && !request.description().isBlank()) {
            TicketReply initialReply = TicketReply.builder()
                .id(EntityIds.next())
                .content(request.description())
                .name(request.creatorName() != null ? request.creatorName() : "Player")
                .creatorIdentifier(UuidUtils.normalize(request.creatorUuid()))
                .staff(false)
                .type("user")
                .created(now)
                .build();
            ticket.getReplies().add(initialReply);
        }

        Ticket created = ticketInserter.insertNew(server, ticketCategory.getTicketPrefix(), ticket);
        if (!unfinished) {
            openedAnnouncer.opened(server, created);
        }
        return created;
    }

    public Ticket createUnfinishedMinecraftTicket(Server server, MinecraftCreateTicketRequest request) {
        return unitOfWork.write(() -> createMinecraftTicketInternal(server, request, true));
    }

    @Nullable
    public String publicUrl(@Nullable Ticket ticket) {
        return ticket == null ? null : ticketPublicLinks.of(ticket);
    }

    public List<ListedTicket> getMinecraftTickets(Server server, @Nullable String status, @Nullable String type, int limit) {
        return ticketRepository.findMinecraftTickets(status, type, limit);
    }

    public Optional<Ticket> getMinecraftTicket(Server server, String ticketId) {
        return ticketRepository.findById(ticketId);
    }

    public List<Ticket> getMinecraftTicketsByCreator(Server server, String creatorUuid, int limit) {
        return ticketRepository.findRecentByCreator(UuidUtils.normalize(creatorUuid), limit);
    }

    public MinecraftTicketClaimResult claimMinecraftTicket(Server server, String ticketId, MinecraftClaimTicketRequest request) {
        return unitOfWork.write(() -> claim(server, ticketId, request));
    }

    private MinecraftTicketClaimResult claim(Server server, String ticketId, MinecraftClaimTicketRequest request) {
        Ticket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) {
            return new MinecraftTicketClaimResult(MinecraftTicketClaimStatus.NOT_FOUND, null);
        }

        String claimerUuid = UuidUtils.normalize(request.playerUuid());
        String creatorUuid = ticket.getCreatorUuid();
        boolean linked = creatorUuid != null && !creatorUuid.isBlank();
        if (linked && Objects.equals(UuidUtils.normalizeIfPresent(creatorUuid), claimerUuid)) {
            return new MinecraftTicketClaimResult(MinecraftTicketClaimStatus.SUCCESS, ticket);
        }
        PublicRecordAccessService.AccessResult access = recordAccessService.authorize(server, ticket, request.accessKey());
        if (access.access() != PublicRecordAccessService.Access.GRANTED) {
            return new MinecraftTicketClaimResult(MinecraftTicketClaimStatus.NOT_FOUND, null);
        }
        if (linked) {
            return new MinecraftTicketClaimResult(MinecraftTicketClaimStatus.ALREADY_LINKED, null);
        }

        String id = ticket.getId();
        Instant now = clock.instant();
        ticketRepository.updateHeader(id, ticket.getVersion(), TicketHeaderPatch.builder()
            .updatedAt(now)
            .creatorUuid(claimerUuid)
            .creatorName(request.playerName())
            .build());
        List<String> ownedReplyIds = creatorReplyIds(ticket);
        if (!ownedReplyIds.isEmpty()) {
            ticketRepository.reassignReplies(id, ownedReplyIds, request.playerName(), claimerUuid);
        }
        return new MinecraftTicketClaimResult(MinecraftTicketClaimStatus.SUCCESS, ticketRepository.findById(id).orElseThrow());
    }

    private List<String> creatorReplyIds(Ticket ticket) {
        String originalIdentifier = resolveOriginalCreatorIdentifier(ticket);
        String originalName = ticket.getCreatorName();
        return ticket.getReplies().stream()
            .filter(reply -> !reply.isStaff())
            .filter(reply -> {
                String identifier = reply.getCreatorIdentifier();
                return (identifier != null && !identifier.isBlank())
                    ? identifier.equals(originalIdentifier)
                    : (originalName != null && originalName.equals(reply.getName()));
            })
            .map(TicketReply::getId)
            .toList();
    }

    @Nullable
    private String resolveOriginalCreatorIdentifier(Ticket ticket) {
        if (ticket.getData() != null) {
            Object stored = ticket.getData().get(TicketDataKeys.CREATOR_IDENTIFIER);
            if (stored instanceof String identifier && !identifier.isBlank()) {
                return identifier;
            }
        }
        for (TicketReply reply : ticket.getReplies()) {
            if (reply.isStaff()) {
                continue;
            }
            String identifier = reply.getCreatorIdentifier();
            if (identifier != null && !identifier.isBlank()) {
                return identifier;
            }
        }
        return null;
    }

    public List<ListedTicket> getMinecraftTicketsByIds(Server server, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ticketRepository.findListedByIds(ids);
    }

    public List<MinecraftReportView> getMinecraftReports(Server server, String status, int limit) {
        return ticketRepository.findReports(status, null, limit, true)
            .stream()
            .map(this::toMinecraftReport)
            .toList();
    }

    @Nullable
    private static String firstPlayerVisibleContent(ThreadDigest thread) {
        TicketReply first = thread.firstVisibleReply();
        return first == null ? null : first.getContent();
    }

    private MinecraftReportView toMinecraftReport(ListedTicket listed) {
        Ticket ticket = listed.ticket();
        return new MinecraftReportView(
            ticket.getId(),
            typeIdOrNull(ticket),
            ticket.getCreatorName() != null ? ticket.getCreatorName() : "Unknown",
            ticket.getCreatorUuid(),
            ticket.getReportedPlayerUuid(),
            ticket.getReportedPlayer(),
            ticket.getSubject(),
            firstPlayerVisibleContent(listed.thread()),
            statusOrOpen(ticket).getId(),
            priorityOrNormal(ticket).getId(),
            ticket.getCreated(),
            ticket.getAssignedTo(),
            ticket.getChatMessages(),
            ticketPublicLinks.replay(ticket)
        );
    }

    public List<MinecraftReportView> getMinecraftReportsForPlayer(Server server, String playerUuid, String status, int limit) {
        return ticketRepository.findReports(status, UuidUtils.normalize(playerUuid), limit, false)
            .stream()
            .map(this::toMinecraftReport)
            .toList();
    }

    public ReportOperationResult dismissMinecraftReport(Server server, String ticketId, MinecraftIssuer issuer, String dismissedBy,
                                                        @Nullable String reason) {
        issuerAuthorizer.requirePermission(server, issuer, PermissionService.TICKET_CLOSE_ALL);
        return closeReport(server, ticketId, dismissedBy,
            "Thank you for submitting this report. After careful review, we have found insufficient evidence to take action at this time.",
            (data, now) -> {
                if (reason != null && !reason.isBlank()) {
                    data.put("dismissReason", reason);
                }
                if (dismissedBy != null) {
                    data.put("dismissedBy", dismissedBy);
                    data.put("dismissedAt", now.toEpochMilli());
                }
            });
    }

    private ReportOperationResult closeReport(
        Server server,
        String ticketId,
        String staffName,
        String closeMessage,
        BiConsumer<Map<String, Object>, Instant> applyMetadata
    ) {
        return unitOfWork.write(() -> {
            Ticket ticket = findReport(ticketId).orElse(null);
            if (ticket == null) {
                return new ReportOperationResult(ReportOperationStatus.NOT_FOUND, null);
            }
            if (ticket.isLocked() || ticket.getStatus().isTerminal()) {
                return new ReportOperationResult(ReportOperationStatus.SUCCESS, ticket);
            }

            Instant now = clock.instant();
            Map<String, Object> data = ticket.getData() != null ? new HashMap<>(ticket.getData()) : new HashMap<>();
            applyMetadata.accept(data, now);
            ticketRepository.updateHeader(ticket.getId(), ticket.getVersion(), TicketHeaderPatch.builder()
                .updatedAt(now)
                .status(TicketStatus.CLOSED)
                .locked(true)
                .data(data.isEmpty() ? null : data)
                .build());
            TicketReply reply = TicketReply.builder()
                .id(EntityIds.next())
                .name(staffName != null ? staffName : "Staff")
                .content(closeMessage)
                .type("reply")
                .created(now)
                .staff(true)
                .action("close")
                .build();
            ticketRepository.appendReply(ticket.getId(), reply, now);

            Ticket saved = ticketRepository.findById(ticket.getId()).orElseThrow();
            unitOfWork.afterCommit(() -> {
                notificationService.notifyTicketReply(server, saved, reply);
                notificationService.notifyTicketClosed(server, saved);
            });
            return new ReportOperationResult(ReportOperationStatus.SUCCESS, saved);
        });
    }

    private Optional<Ticket> findReport(String ticketId) {
        return ticketRepository.findById(ticketId).filter(MinecraftTicketService::isReport);
    }

    private static boolean isReport(Ticket ticket) {
        return ticket.getType().isReport();
    }

    public ReportOperationResult resolveMinecraftReport(
        Server server,
        String ticketId,
        MinecraftIssuer issuer,
        String resolvedBy,
        @Nullable String resolution,
        @Nullable String punishmentId
    ) {
        issuerAuthorizer.requirePermission(server, issuer, PermissionService.TICKET_CLOSE_ALL);
        return closeReport(server, ticketId, resolvedBy,
            "Thank you for creating this report. After careful review, we have accepted this and the reported player has received a punishment.",
            (data, now) -> {
                if (resolution != null && !resolution.isBlank()) {
                    data.put("resolution", resolution);
                }
                if (resolvedBy != null) {
                    data.put("resolvedBy", resolvedBy);
                    data.put("resolvedAt", now.toEpochMilli());
                }
                if (punishmentId != null) {
                    data.put("linkedPunishmentId", punishmentId);
                }
            });
    }

    public ReportOperationResult assignMinecraftReport(Server server, String ticketId, AssignReportRequest request) {
        return unitOfWork.write(() -> {
            Ticket ticket = findReport(ticketId).orElse(null);
            if (ticket == null) {
                return new ReportOperationResult(ReportOperationStatus.NOT_FOUND, null);
            }
            ticketRepository.replaceAssignees(
                ticket.getId(), TicketAssigneeUtil.normalizeAssignment(request.assignee()), clock.instant());
            return new ReportOperationResult(
                ReportOperationStatus.SUCCESS, ticketRepository.findById(ticket.getId()).orElseThrow());
        });
    }

    public ReportOperationResult assignMinecraftReport(Server server, String ticketId, String assignee) {
        return assignMinecraftReport(server, ticketId, new AssignReportRequest(assignee));
    }

    public MinecraftTicketListItemView toTicketListItem(ListedTicket listed) {
        Ticket ticket = listed.ticket();
        return new MinecraftTicketListItemView(
            ticket.getId(),
            typeIdOrNull(ticket),
            typeIdOrNull(ticket),
            ticket.getSubject(),
            statusIdOrNull(ticket),
            ticket.getCreatorName(),
            ticket.getCreatorUuid(),
            priorityIdOrNull(ticket),
            ticket.getAssignedTo(),
            ticket.getCreated(),
            ticket.getUpdatedAt(),
            listed.thread().staffResponded(),
            listed.thread().visibleReplies(),
            ticket.isLocked()
        );
    }

    public MinecraftTicketDetailView toTicketDetail(Ticket ticket) {
        List<MinecraftTicketDetailReplyView> replies = new ArrayList<>();
        for (TicketReply reply : ticket.ensureReplies()) {
            replies.add(new MinecraftTicketDetailReplyView(
                reply.getId(),
                reply.getContent(),
                reply.getName(),
                reply.getCreatorIdentifier(),
                reply.isStaff(),
                reply.getCreated()
            ));
        }

        return new MinecraftTicketDetailView(
            ticket.getId(),
            typeIdOrNull(ticket),
            typeIdOrNull(ticket),
            ticket.getSubject(),
            statusIdOrNull(ticket),
            ticket.getCreatorName(),
            ticket.getCreatorUuid(),
            priorityIdOrNull(ticket),
            ticket.getAssignedTo(),
            ticket.getCreated(),
            ticket.getUpdatedAt(),
            ticket.isLocked(),
            replies,
            ticket.getChatMessages(),
            ticketPublicLinks.replay(ticket)
        );
    }

    public MinecraftTicketLookupView toTicketLookupItem(ListedTicket listed) {
        Ticket ticket = listed.ticket();
        String firstReplyContent = firstPlayerVisibleContent(listed.thread());

        return new MinecraftTicketLookupView(
            ticket.getId(),
            typeIdOrNull(ticket),
            typeIdOrNull(ticket),
            ticket.getSubject(),
            statusIdOrNull(ticket),
            ticket.getCreatorName(),
            ticket.getCreatorUuid(),
            ticket.getCreated(),
            firstReplyContent
        );
    }

    public MinecraftPlayerTicketView toPlayerTicketItem(Ticket ticket) {
        return new MinecraftPlayerTicketView(
            ticket.getId(),
            typeIdOrNull(ticket),
            typeIdOrNull(ticket),
            ticket.getSubject(),
            statusIdOrNull(ticket),
            ticket.getCreated(),
            ticketPublicLinks.of(ticket)
        );
    }

    public enum MinecraftTicketClaimStatus {
        SUCCESS,
        NOT_FOUND,
        ALREADY_LINKED
    }

    public enum ReportOperationStatus {
        SUCCESS,
        NOT_FOUND
    }

    public record MinecraftTicketClaimResult(MinecraftTicketClaimStatus status, @Nullable Ticket ticket) {
        @Nullable
        public String subject() {
            return ticket == null ? null : ticket.getSubject();
        }
    }

    public record ReportOperationResult(ReportOperationStatus status, @Nullable Ticket ticket) {}

    private static Ticket.ChatMessage parseChatMessage(String rawMessage, Instant reportTime) {
        String truncated = rawMessage.substring(0, Math.min(rawMessage.length(), TicketContentService.MAX_CHAT_MESSAGE_LENGTH));
        Matcher matcher = CHAT_LINE_PATTERN.matcher(truncated);
        if (matcher.matches()) {
            return Ticket.ChatMessage.builder()
                .content(matcher.group(3))
                .timestamp(reconstructTimestamp(matcher.group(1), reportTime))
                .sender(matcher.group(2).trim())
                .build();
        }
        return Ticket.ChatMessage.builder()
            .content(truncated)
            .timestamp(reportTime)
            .build();
    }

    static Instant reconstructTimestamp(@Nullable String timeOfDay, Instant reportTime) {
        if (timeOfDay == null) {
            return reportTime;
        }
        try {
            LocalTime messageTime = LocalTime.parse(timeOfDay, CHAT_TIME_FORMAT);
            OffsetDateTime report = reportTime.atOffset(ZoneOffset.UTC);
            OffsetDateTime messageMoment = report.with(messageTime);
            if (messageMoment.isAfter(report)) {
                messageMoment = messageMoment.minusDays(1);
            }
            return messageMoment.toInstant();
        } catch (DateTimeParseException e) {
            return reportTime;
        }
    }

    private static @Nullable String typeIdOrNull(Ticket ticket) {
        TicketCategory type = ticket.getType();
        return type != null ? type.getId() : null;
    }

    private static @Nullable String statusIdOrNull(Ticket ticket) {
        TicketStatus status = ticket.getStatus();
        return status != null ? status.getId() : null;
    }

    private static @Nullable String priorityIdOrNull(Ticket ticket) {
        TicketPriority priority = ticket.getPriority();
        return priority != null ? priority.getId() : null;
    }

    private static TicketStatus statusOrOpen(Ticket ticket) {
        TicketStatus status = ticket.getStatus();
        return status != null ? status : TicketStatus.OPEN;
    }

    private static TicketPriority priorityOrNormal(Ticket ticket) {
        TicketPriority priority = ticket.getPriority();
        return priority != null ? priority : TicketPriority.NORMAL;
    }
}
