package gg.modl.minecraft.core.service.sync;

import gg.modl.minecraft.core.connection.HttpUrl;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.api.AbstractPlayer;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.NotificationAcknowledgeRequest;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.StaffAudience;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.StaffAudiences;
import gg.modl.minecraft.core.staff.StaffNoticeType;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.RecentIds;
import gg.modl.minecraft.core.util.Scheduling;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static gg.modl.minecraft.core.util.Java8Collections.listOf;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

class NotificationService {
    private static final String GAMEPLAY_REPORT_TYPE = "player";
    private static final int MAX_HOVER_TEXT_LENGTH = 200;
    private static final long NOTIFICATION_INITIAL_DELAY_MS = 2000, NOTIFICATION_INTER_DELAY_MS = 1500;
    private static final int MAX_ACK_BATCH_SIZE = 100;
    private static final int ANNOUNCED_STAFF_NOTIFICATION_CAPACITY = 512;

    private final Platform platform;
    private final HttpClientHolder httpClientHolder;
    private final Cache cache;
    private final PluginLogger logger;
    private final LocaleManager localeManager;
    private final PanelUrl panelUrl;
    private final StaffAudiences staffAudiences;
    private final boolean debugMode;
    private final RecentIds announcedStaffNotifications = new RecentIds(ANNOUNCED_STAFF_NOTIFICATION_CAPACITY);

    @Setter private volatile @Nullable ScheduledExecutorService executor;

    NotificationService(Platform platform, HttpClientHolder httpClientHolder, Cache cache,
                        PluginLogger logger, LocaleManager localeManager, PanelUrl panelUrl, StaffAudiences staffAudiences,
                        boolean debugMode) {
        this.platform = platform;
        this.httpClientHolder = httpClientHolder;
        this.cache = cache;
        this.logger = logger;
        this.localeManager = localeManager;
        this.panelUrl = panelUrl;
        this.staffAudiences = staffAudiences;
        this.debugMode = debugMode;
    }

    void processStaffNotification(SyncResponse.StaffNotification notification) {
        if (!announcedStaffNotifications.markIfNew(notification.getId())) {
            if (debugMode) logger.info("Suppressed repeated staff notification " + notification.getId());
            return;
        }
        StaffNoticeType type = StaffNoticeType.fromWire(notification.getType());
        if (type == null) {
            if (debugMode) logger.info("Dropped staff notification " + notification.getId() + " of unknown type " + notification.getType());
            return;
        }
        StaffAudience audience = type.audienceIn(staffAudiences);
        try {
            Map<String, Object> data = notification.getData();
            if (type == StaffNoticeType.TICKET_CREATED && data != null) {
                processTicketCreatedNotification(notification, data, audience);
            } else {
                platform.staffBroadcast("&7&o[" + notification.getMessage() + "&7&o]", audience);
            }
            if (debugMode) logger.info("Processed staff notification: " + notification.getMessage());
        } catch (Exception e) {
            logger.warning("Error processing staff notification: " + e.getMessage());
        }
    }

    private void processTicketCreatedNotification(SyncResponse.StaffNotification notification, Map<String, Object> data,
                                                  StaffAudience audience) {
        String ticketUrl = staffTicketUrl(data);
        String subject = extractString(data, "subject");
        String firstReply = extractString(data, "firstReplyContent");
        String ticketType = extractString(data, "ticketType");
        String reportedPlayer = extractString(data, "reportedPlayer");

        String hoverText = buildTicketHoverText(subject, firstReply);
        boolean isGameplayReport = GAMEPLAY_REPORT_TYPE.equals(ticketType) && !reportedPlayer.isEmpty();

        String clickAction;
        String clickValue;
        if (isGameplayReport) {
            clickAction = "run_command";
            clickValue = "/target " + reportedPlayer;
            hoverText += (hoverText.isEmpty() ? "" : "\n\n")
                    + localeManager.getMessageWithRawValues("staff_notifications.click_to_target", mapOf("player", reportedPlayer));
        } else {
            clickAction = "open_url";
            clickValue = ticketUrl;
        }

        ClickableJsonMessage link = ClickableJsonMessage.text("[" + notification.getMessage() + "]")
                .color("gray")
                .italic(true)
                .hoverText(hoverText);
        if ("run_command".equals(clickAction)) {
            link.runCommand(clickValue);
        } else {
            link.openUrl(clickValue);
        }

        String json = ClickableJsonMessage.empty()
                .extra(link)
                .toJson();
        platform.staffJsonBroadcast(json, audience);
    }

    void processPlayerNotification(SyncResponse.PlayerNotification notification) {
        try {
            if (debugMode)
                logger.info("Processing notification " + notification.getId() + " (type: " + notification.getType() + "): " + notification.getMessage());

            String targetPlayerUuid = notification.getTargetPlayerUuid();
            if (targetPlayerUuid == null || targetPlayerUuid.isEmpty()) {
                handleNotificationForAllOnlinePlayers(notification);
                return;
            }

            UUID playerUuid;
            try {
                playerUuid = UUID.fromString(targetPlayerUuid);
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid UUID format in notification: " + targetPlayerUuid);
                return;
            }

            if (deliverNotificationToPlayerAndCheck(playerUuid, notification)) {
                acknowledgeNotification(playerUuid, notification.getId());
            }
        } catch (Exception e) {
            logger.severe("Error processing player notification: " + e.getMessage());
        }
    }

    private boolean deliverNotificationToPlayerAndCheck(UUID playerUuid, SyncResponse.PlayerNotification notification) {
        AbstractPlayer player = platform.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) {
            if (debugMode) logger.info("Player " + playerUuid + " is offline, notification " + notification.getId() + " deferred");
            return false;
        }

        Map<String, Object> data = notification.getData();
        String ticketUrl = data == null ? null : playerTicketUrl(data);
        if (data != null && ticketUrl != null) {
            sendClickableTicketNotification(playerUuid, notification, data, ticketUrl);
        } else {
            String message = localeManager.getMessage(
                "notification.ticket_reply", mapOf("message", notification.getMessage()));

            platform.runOnMainThread(() -> platform.sendMessage(playerUuid, message));
        }

        if (debugMode) logger.info("Delivered notification " + notification.getId() + " to " + player.getName());
        return true;
    }

    private void sendClickableTicketNotification(UUID playerUuid, SyncResponse.PlayerNotification notification,
                                                 Map<String, Object> data, String ticketUrl) {
        String ticketId = extractString(data, "ticketId");
        String json = buildClickableTicketJson(notification.getMessage(), ticketUrl, ticketId);
        if (debugMode) logger.info("Sending clickable notification JSON: " + json);
        platform.runOnMainThread(() -> platform.sendJsonMessage(playerUuid, json));
    }

    private void handleNotificationForAllOnlinePlayers(SyncResponse.PlayerNotification notification) {
        for (AbstractPlayer player : platform.getOnlinePlayers()) {
            try {
                UUID playerUuid = player.getUuid();
                CachedProfile profile = cache.getPlayerProfile(playerUuid);
                if (profile != null) profile.addNotification(notification);
                deliverNotificationToPlayerAndCheck(playerUuid, notification);
            } catch (Exception e) {
                logger.warning("Error handling notification for player " + player.getName() + ": " + e.getMessage());
            }
        }
    }

    void deliverPendingNotifications(UUID playerUuid) {
        try {
            CachedProfile profile = cache.getPlayerProfile(playerUuid);
            if (profile == null) return;

            List<CachedProfile.PendingNotification> pending = profile.getPendingNotifications();
            pending.removeIf(CachedProfile.PendingNotification::isExpired);
            if (pending.isEmpty()) return;

            List<CachedProfile.PendingNotification> toProcess = new ArrayList<>(pending);
            if (debugMode) logger.info("Delivering " + toProcess.size() + " pending notifications to " + playerUuid);

            List<String> deliveredIds = new ArrayList<>();
            List<String> expiredIds = new ArrayList<>();
            deliverNotificationsWithDelay(playerUuid, toProcess, deliveredIds, expiredIds);
        } catch (Exception e) {
            logger.severe("Error delivering pending notifications: " + e.getMessage());
        }
    }

    private void deliverPendingNotificationToPlayer(UUID playerUuid, CachedProfile.PendingNotification pending) {
        AbstractPlayer player = platform.getPlayer(playerUuid);
        if (player == null || !player.isOnline()) return;

        Map<String, Object> data = pending.getData();
        String playerTicketUrl = data == null ? null : playerTicketUrl(data);
        if (playerTicketUrl != null) {
            String ticketId = extractString(data, "ticketId");
            String json = buildClickableTicketJson(pending.getMessage(), playerTicketUrl, ticketId);
            platform.runOnMainThread(() -> platform.sendJsonMessage(playerUuid, json));
        } else {
            String message = pending.getMessage();
            platform.runOnMainThread(() -> platform.sendMessage(playerUuid, message));
        }
    }

    private void deliverNotificationsWithDelay(UUID playerUuid, List<CachedProfile.PendingNotification> notifications,
                                             List<String> deliveredIds, List<String> expiredIds) {
        ScheduledExecutorService scheduler = executor;
        if (notifications.isEmpty() || scheduler == null) return;
        Scheduling.after(scheduler, () ->
            platform.runOnMainThread(() ->
                deliverNotificationAtIndex(scheduler, playerUuid, notifications, 0, deliveredIds, expiredIds)),
            NOTIFICATION_INITIAL_DELAY_MS, TimeUnit.MILLISECONDS, logger);
    }

    private void deliverNotificationAtIndex(ScheduledExecutorService scheduler, UUID playerUuid,
                                            List<CachedProfile.PendingNotification> notifications, int index,
                                            List<String> deliveredIds, List<String> expiredIds) {
        if (index >= notifications.size()) {
            finalizePendingNotificationDelivery(playerUuid, deliveredIds, expiredIds);
            return;
        }

        CachedProfile.PendingNotification pending = notifications.get(index);
        try {
            if (pending.isExpired()) {
                expiredIds.add(pending.getId());
            } else {
                AbstractPlayer player = platform.getPlayer(playerUuid);
                if (player == null || !player.isOnline()) {
                    if (debugMode) logger.info("Player " + playerUuid + " disconnected during notification delivery");
                    finalizePendingNotificationDelivery(playerUuid, deliveredIds, expiredIds);
                    return;
                }
                deliverPendingNotificationToPlayer(playerUuid, pending);
                deliveredIds.add(pending.getId());
                if (debugMode) logger.info("Delivered pending notification " + pending.getId() + " to " + playerUuid);
            }
        } catch (Exception e) {
            logger.warning("Error delivering pending notification " + pending.getId() + ": " + e.getMessage());
        }

        Scheduling.after(scheduler, () ->
            platform.runOnMainThread(() ->
                deliverNotificationAtIndex(scheduler, playerUuid, notifications, index + 1, deliveredIds, expiredIds)),
            NOTIFICATION_INTER_DELAY_MS, TimeUnit.MILLISECONDS, logger);
    }

    private void finalizePendingNotificationDelivery(UUID playerUuid, List<String> deliveredIds, List<String> expiredIds) {
        try {
            CachedProfile profile = cache.getPlayerProfile(playerUuid);
            if (profile != null) {
                for (String id : expiredIds) profile.removeNotification(id);
                for (String id : deliveredIds) profile.removeNotification(id);
            }
            if (!deliveredIds.isEmpty()) acknowledgeNotifications(playerUuid, deliveredIds);
            if (debugMode) logger.info("Notification delivery complete for " + playerUuid + ". Delivered: " + deliveredIds.size() + ", Expired: " + expiredIds.size());
        } catch (Exception e) {
            logger.severe("Error finalizing pending notification delivery: " + e.getMessage());
        }
    }

    private void acknowledgeNotification(UUID playerUuid, String notificationId) {
        acknowledgeNotifications(playerUuid, listOf(notificationId));
    }

    private void acknowledgeNotifications(UUID playerUuid, List<String> notificationIds) {
        if (notificationIds == null || notificationIds.isEmpty()) return;
        if (notificationIds.size() <= MAX_ACK_BATCH_SIZE) {
            sendAcknowledgeBatch(playerUuid, notificationIds);
            return;
        }
        for (int i = 0; i < notificationIds.size(); i += MAX_ACK_BATCH_SIZE) {
            List<String> batch = new ArrayList<>(notificationIds.subList(i,
                    Math.min(i + MAX_ACK_BATCH_SIZE, notificationIds.size())));
            sendAcknowledgeBatch(playerUuid, batch);
        }
    }

    private void sendAcknowledgeBatch(UUID playerUuid, List<String> batch) {
        try {
            NotificationAcknowledgeRequest request = new NotificationAcknowledgeRequest(
                    playerUuid.toString(), Instant.now().toString(), batch);

            httpClientHolder.getClient().acknowledgeNotifications(request)
                    .thenAccept(response -> {
                        if (debugMode) logger.info("Acknowledged " + batch.size() + " notifications for " + playerUuid);
                    })
                    .exceptionally(throwable -> {
                        Throwable cause = throwable.getCause();
                        if (cause instanceof PanelUnavailableException) {
                            logger.warning("Failed to acknowledge notifications for " + playerUuid + ": Panel temporarily unavailable");
                        } else {
                            logger.warning("Failed to acknowledge notifications for " + playerUuid + ": " + throwable.getMessage());
                        }
                        return null;
                    });
        } catch (Exception e) {
            logger.severe("Error acknowledging notifications: " + e.getMessage());
        }
    }

    private String buildClickableTicketJson(String message, String ticketUrl, String ticketId) {
        return ClickableJsonMessage.empty()
                .extra(ClickableJsonMessage.text(localeManager.getMessage("notification.ticket_prefix")).color("gold"))
                .extra(ClickableJsonMessage.text(message + " ").color("white"))
                .extra(ClickableJsonMessage.text(localeManager.getMessage("messages.click_to_view"))
                        .color("aqua")
                        .underlined(true)
                        .openUrl(ticketUrl)
                        .hoverText(localeManager.getMessage("messages.click_to_view_hover", mapOf("ticketId", ticketId))))
                .toJson();
    }

    private String buildTicketHoverText(String subject, String firstReply) {
        StringBuilder hover = new StringBuilder();
        if (!subject.isEmpty()) hover.append(subject);
        if (!firstReply.isEmpty()) {
            if (hover.length() > 0) hover.append("\n\n");
            hover.append(firstReply.length() > MAX_HOVER_TEXT_LENGTH
                    ? firstReply.substring(0, MAX_HOVER_TEXT_LENGTH) + "..."
                    : firstReply);
        }
        return hover.toString();
    }

    private String staffTicketUrl(Map<String, Object> data) {
        String ticketId = extractString(data, "ticketId");
        return ticketId.isEmpty() ? panelUrl.get() : panelUrl.staffTicket(ticketId);
    }

    static @Nullable String playerTicketUrl(Map<String, Object> data) {
        String ticketUrl = extractString(data, "ticketUrl");
        return HttpUrl.isAbsolute(ticketUrl) ? ticketUrl : null;
    }

    private static String extractString(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val instanceof String ? (String) val : "";
    }

}
