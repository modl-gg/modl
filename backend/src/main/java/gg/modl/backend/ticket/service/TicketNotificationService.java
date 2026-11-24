package gg.modl.backend.ticket.service;

import gg.modl.backend.database.EntityIds;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.email.EmailHTMLTemplate;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.repository.PlayerNotification;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.service.SyncProtoFactory;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.config.NotificationExpiry;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.proto.modl.v1.SyncPlayerNotification;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@Slf4j
public class TicketNotificationService {
    private static final DateTimeFormatter TRANSCRIPT_TIME_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US).withZone(ZoneId.systemDefault());
    private final PlayerRepository playerRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final NotificationExpiry notificationExpiry;
    private final EmailService emailService;
    private final PublicUrls publicUrls;
    private final TicketPublicLinks ticketPublicLinks;
    private final RealtimeEventPublisher realtimeEventPublisher;
    private final SyncProtoFactory syncProtoFactory;

    public TicketNotificationService(PlayerRepository playerRepository, UnitOfWork unitOfWork, Clock clock,
                                     EmailService emailService, PublicUrls publicUrls, TicketPublicLinks ticketPublicLinks,
                                     RealtimeEventPublisher realtimeEventPublisher, SyncProtoFactory syncProtoFactory,
                                     NotificationExpiry notificationExpiry) {
        this.playerRepository = playerRepository;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.emailService = emailService;
        this.publicUrls = publicUrls;
        this.ticketPublicLinks = ticketPublicLinks;
        this.realtimeEventPublisher = realtimeEventPublisher;
        this.syncProtoFactory = syncProtoFactory;
        this.notificationExpiry = notificationExpiry;
    }

    @Async
    public void notifyTicketReply(Server server, Ticket ticket, TicketReply reply) {
        if (!reply.isStaff()) {
            return;
        }

        String creatorEmail = getCreatorEmail(ticket);
        String creatorUuid = ticket.getCreatorUuid();

        if (creatorEmail != null && !creatorEmail.isBlank()) {
            sendEmailNotification(server, ticket, reply, creatorEmail);
        }

        if (creatorUuid != null && !creatorUuid.isBlank()) {
            createInGameNotification(server, ticket, reply, creatorUuid);
        }
    }

    private void sendEmailNotification(Server server, Ticket ticket, TicketReply reply, String toEmail) {
        try {
            EscapedTicketHeader header = buildEscapedHeader(server, ticket);
            String replyAuthor = HtmlUtils.htmlEscape(reply.getName() != null ? reply.getName() : "Staff");
            String replyContent = HtmlUtils.htmlEscape(reply.getContent() != null ? reply.getContent() : "");

            EmailHTMLTemplate.HTMLEmail email = EmailHTMLTemplate.TICKET_REPLY_TEMPLATE.build(
                header.serverName(),
                header.playerName(),
                true,
                header.ticketType(),
                header.ticketId(),
                header.ticketSubject(),
                replyAuthor,
                replyContent,
                header.ticketUrl()
            );

            emailService.send(toEmail, email);
        } catch (Exception exception) {
            log.error("Failed to send the ticket reply email for ticket {}: {}", ticket.getId(), exception.getMessage());
        }
    }

    private String buildTicketUrl(Ticket ticket) {
        return Objects.requireNonNullElseGet(ticketPublicLinks.of(ticket), publicUrls::panel);
    }

    private String resolveTicketLabel(Ticket ticket) {
        TicketCategory category = ticket.getType();
        return category != null ? category.getDisplayName() : TicketCategory.SUPPORT.getDisplayName();
    }

    private void createInGameNotification(Server server, Ticket ticket, TicketReply reply, String playerUuid) {
        try {
            Player player = findPlayer(playerUuid);
            if (player == null) {
                log.debug("Player with UUID {} not found, skipping in-game notification", playerUuid);
                return;
            }

            Map<String, Object> notificationData = buildNotificationData(ticket);
            notificationData.put("replyAuthor", reply.getName());
            Map<String, Object> notification = buildNotification("TICKET_REPLY", buildNotificationMessage(ticket, reply), notificationData);

            appendNotification(server, player, notification);
        } catch (Exception exception) {
            log.error("Failed to create in-game notification for player {} for ticket {}: {}",
                playerUuid, ticket.getId(), exception.getMessage());
        }
    }

    @Nullable
    private Player findPlayer(String playerUuid) {
        return playerRepository.findByMinecraftUuid(UuidUtils.normalize(playerUuid)).orElse(null);
    }

    private void appendNotification(Server server, Player player, Map<String, Object> notification) {
        if (player.getMinecraftUuid() != null) {
            notification.putIfAbsent("targetPlayerUuid", player.getMinecraftUuid().toString());
            Instant createdAt = clock.instant();
            PlayerNotification pending = new PlayerNotification(
                String.valueOf(notification.get("id")), notification, createdAt, createdAt.plus(notificationExpiry.lifetime()));
            unitOfWork.write(() -> playerRepository.addNotification(player.getId(), pending));
        }

        pushPlayerNotification(server, notification);
    }

    private void pushPlayerNotification(Server server, Map<String, Object> notification) {
        SyncPlayerNotification payload = syncProtoFactory.toPlayerNotification(notification);
        realtimeEventPublisher.pushPlayerNotifications(server, List.of(payload));
    }

    private String buildNotificationMessage(Ticket ticket, TicketReply reply) {
        String replyAuthor = reply.getName() != null ? reply.getName() : "Staff";
        return String.format("%s replied to your ticket #%s", replyAuthor, ticket.getId());
    }

    @Nullable
    private String getCreatorEmail(Ticket ticket) {
        if (ticket.getData() == null) {
            return null;
        }
        Object email = ticket.getData().get(TicketDataKeys.CREATOR_EMAIL);
        if (email == null) {
            return null;
        }

        String normalizedEmail = EmailAddressUtil.normalizeIfValid(email.toString());
        if (normalizedEmail == null) {
            log.warn("Skipping ticket email notification for {} due to an invalid creator email", ticket.getId());
            return null;
        }

        return normalizedEmail;
    }

    @Async
    public void notifyTicketClosed(Server server, Ticket ticket) {
        String creatorEmail = getCreatorEmail(ticket);
        String creatorUuid = ticket.getCreatorUuid();

        if (creatorEmail != null && !creatorEmail.isBlank()) {
            sendTranscriptEmail(server, ticket, creatorEmail);
        }

        if (creatorUuid != null && !creatorUuid.isBlank()) {
            createClosedInGameNotification(server, ticket, creatorUuid);
        }
    }

    private void sendTranscriptEmail(Server server, Ticket ticket, String toEmail) {
        try {
            EscapedTicketHeader header = buildEscapedHeader(server, ticket);

            StringBuilder messagesHtml = new StringBuilder();
            for (TicketReply reply : ticket.ensureReplies()) {
                String author = HtmlUtils.htmlEscape(reply.getName() != null ? reply.getName() : (reply.isStaff() ? "Staff" : "Player"));
                String date = HtmlUtils.htmlEscape(reply.getCreated() != null ? TRANSCRIPT_TIME_FORMAT.format(reply.getCreated()) : "");
                String content = HtmlUtils.htmlEscape(reply.getContent() != null ? reply.getContent() : "").replace("\n", "<br>");
                String roleLabel = reply.isStaff() ? " (Staff)" : "";

                messagesHtml.append("""
                    <div style=\"border: 1px solid #e9ecef; border-radius: 4px; padding: 12px; margin: 10px 0;\">
                      <div style=\"margin-bottom: 8px;\">
                        <strong style=\"color: #333;\">%s%s</strong>
                        <span style=\"color: #888; font-size: 12px; margin-left: 8px;\">%s</span>
                      </div>
                      <div style=\"color: #555; font-size: 14px;\">%s</div>
                    </div>
                    """.formatted(author, roleLabel, date, content));
            }

            EmailHTMLTemplate.HTMLEmail email = EmailHTMLTemplate.TICKET_TRANSCRIPT_TEMPLATE.build(
                header.serverName(), header.playerName(), header.ticketType(), header.ticketId(), header.ticketSubject(), messagesHtml.toString(), header.ticketUrl()
            );

            emailService.send(toEmail, email);
        } catch (Exception exception) {
            log.error("Failed to send the ticket transcript email for ticket {}: {}", ticket.getId(), exception.getMessage());
        }
    }

    private void createClosedInGameNotification(Server server, Ticket ticket, String playerUuid) {
        try {
            Player player = findPlayer(playerUuid);
            if (player == null) {
                return;
            }

            Map<String, Object> notification = buildNotification(
                "TICKET_CLOSED",
                String.format("Your ticket #%s has been closed", ticket.getId()),
                buildNotificationData(ticket));

            appendNotification(server, player, notification);
        } catch (Exception exception) {
            log.error("Failed to create in-game closed notification for player {} for ticket {}: {}",
                playerUuid, ticket.getId(), exception.getMessage());
        }
    }

    private EscapedTicketHeader buildEscapedHeader(Server server, Ticket ticket) {
        String serverName = HtmlUtils.htmlEscape(server.getServerName() != null ? server.getServerName() : "Server");
        String playerName = HtmlUtils.htmlEscape(ticket.getCreatorName() != null ? ticket.getCreatorName() : "Player");
        String ticketType = HtmlUtils.htmlEscape(resolveTicketLabel(ticket));
        String ticketId = HtmlUtils.htmlEscape(ticket.getId());
        String ticketSubject = HtmlUtils.htmlEscape(ticket.getSubject() != null ? ticket.getSubject() : "No Subject");
        String ticketUrl = buildTicketUrl(ticket);
        return new EscapedTicketHeader(serverName, playerName, ticketType, ticketId, ticketSubject, ticketUrl);
    }

    private Map<String, Object> buildNotification(String type, String message, Map<String, Object> data) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("id", EntityIds.next());
        notification.put("type", type);
        notification.put("message", message);
        notification.put("timestamp", clock.millis());
        notification.put("data", data);
        return notification;
    }

    private Map<String, Object> buildNotificationData(Ticket ticket) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("ticketUrl", buildTicketUrl(ticket));
        return data;
    }

    private record EscapedTicketHeader(
        String serverName,
        String playerName,
        String ticketType,
        String ticketId,
        String ticketSubject,
        String ticketUrl
    ) {
    }
}
