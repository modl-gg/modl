package gg.modl.minecraft.core.cache;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.service.StaffChatService;
import gg.modl.minecraft.core.service.Staff2faService;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Getter
public class CachedProfile {
    private final UUID uuid;
    private final long joinTime = System.currentTimeMillis();

    @Setter private volatile @Nullable SimplePunishment activeMute;
    @Setter private volatile @Nullable SimplePunishment activeBan;
    @Setter private volatile SyncResponse.@Nullable ActiveStaffMember staffMember;

    @Setter private volatile StaffChatService.ChatMode chatMode = StaffChatService.ChatMode.NORMAL;
    @Setter private volatile boolean interceptingNetworkChat;

    @Setter private volatile Staff2faService.@Nullable AuthState authState;
    @Setter private volatile boolean twoFaNotified;

    private final List<PendingNotification> pendingNotifications = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong lastChatMessageTime = new AtomicLong(0L);
    private final CooldownTracker cooldowns = new CooldownTracker();

    public CachedProfile(UUID uuid) {
        this.uuid = uuid;
    }

    public long getLastChatMessageTime() {
        return lastChatMessageTime.get();
    }

    public boolean compareAndSetLastChatMessageTime(long expect, long update) {
        return lastChatMessageTime.compareAndSet(expect, update);
    }

    public long getSessionDuration() {
        return System.currentTimeMillis() - joinTime;
    }

    public boolean isMuted() {
        SimplePunishment mute = activeMute;
        if (mute == null) return false;
        if (mute.isExpired()) {
            activeMute = null;
            return false;
        }
        return true;
    }

    public boolean isBanned() {
        SimplePunishment ban = activeBan;
        if (ban == null) return false;
        if (ban.isExpired()) {
            activeBan = null;
            return false;
        }
        return true;
    }

    public void endPunishment(String punishmentId) {
        if (isPunishment(activeMute, punishmentId)) activeMute = null;
        if (isPunishment(activeBan, punishmentId)) activeBan = null;
    }

    public void changePunishmentDuration(String punishmentId, @Nullable Long duration, long changedAt) {
        Long expiration = duration != null && duration > 0 ? changedAt + duration : null;
        SimplePunishment mute = activeMute;
        SimplePunishment ban = activeBan;
        if (mute != null && isPunishment(mute, punishmentId)) mute.setExpiration(expiration);
        if (ban != null && isPunishment(ban, punishmentId)) ban.setExpiration(expiration);
    }

    private static boolean isPunishment(@Nullable SimplePunishment cached, String punishmentId) {
        return cached != null && punishmentId.equals(cached.getId());
    }

    public void addNotification(SyncResponse.PlayerNotification notification) {
        pendingNotifications.add(new PendingNotification(
            notification.getId(), notification.getMessage(), notification.getType(),
            notification.getTimestamp(), notification.getData(), System.currentTimeMillis()
        ));
    }

    public boolean removeNotification(String notificationId) {
        return pendingNotifications.removeIf(n -> n.getId().equals(notificationId));
    }

    public void cleanupExpiredNotifications() {
        pendingNotifications.removeIf(PendingNotification::isExpired);
    }

    @Getter
    public static class PendingNotification {
        private static final long NOTIFICATION_EXPIRY_MS = 24 * 60 * 60 * 1000L;

        private final String id, message, type;
        private final @Nullable Long timestamp;
        private final Map<String, Object> data;
        private final long cachedTime;

        public PendingNotification(String id, String message, String type, @Nullable Long timestamp,
                                   @Nullable Map<String, Object> data, long cachedTime) {
            this.id = id;
            this.message = message;
            this.type = type;
            this.timestamp = timestamp;
            this.data = data != null ? new HashMap<>(data) : new HashMap<>();
            this.cachedTime = cachedTime;
        }

        public boolean isExpired() {
            return (System.currentTimeMillis() - cachedTime) > NOTIFICATION_EXPIRY_MS;
        }
    }

    public static class CooldownTracker {
        private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

        public boolean isOnCooldown(String key, long durationMs) {
            Long lastUsed = cooldowns.get(key);
            if (lastUsed == null) return false;
            return (System.currentTimeMillis() - lastUsed) < durationMs;
        }

        public long getRemainingMs(String key, long durationMs) {
            Long lastUsed = cooldowns.get(key);
            if (lastUsed == null) return 0;
            long remaining = durationMs - (System.currentTimeMillis() - lastUsed);
            return Math.max(0, remaining);
        }

        public void set(String key) {
            cooldowns.put(key, System.currentTimeMillis());
        }
    }
}
