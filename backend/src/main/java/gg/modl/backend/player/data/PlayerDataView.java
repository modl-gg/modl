package gg.modl.backend.player.data;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class PlayerDataView {
    @Nullable
    private Map<String, Object> data;
    @Nullable
    private final Consumer<Map<String, Object>> installer;

    private PlayerDataView(@Nullable Map<String, Object> data, @Nullable Consumer<Map<String, Object>> installer) {
        this.data = data;
        this.installer = installer;
    }

    static PlayerDataView ownedBy(@Nullable Map<String, Object> data, Consumer<Map<String, Object>> installer) {
        return new PlayerDataView(data, installer);
    }

    @Nullable
    public Map<String, Object> asMap() {
        return data;
    }

    private Map<String, Object> mutable() {
        if (data == null) {
            data = new HashMap<>();
            if (installer != null) {
                installer.accept(data);
            }
        }
        return data;
    }

    public boolean isOnline() {
        return data != null && Boolean.TRUE.equals(data.get(PlayerDataKeys.IS_ONLINE));
    }

    public void setOnline(boolean online) {
        mutable().put(PlayerDataKeys.IS_ONLINE, online);
    }

    @Nullable
    public Instant lastLogin() {
        return data != null ? StoredInstants.of(data.get(PlayerDataKeys.LAST_LOGIN)) : null;
    }

    public void setLastLogin(Instant lastLogin) {
        mutable().put(PlayerDataKeys.LAST_LOGIN, lastLogin);
    }

    @Nullable
    public String lastServer() {
        return data != null && data.get(PlayerDataKeys.LAST_SERVER) instanceof String value ? value : null;
    }

    public void setLastServer(String lastServer) {
        mutable().put(PlayerDataKeys.LAST_SERVER, lastServer);
    }

    @Nullable
    public Number totalPlaytimeSeconds() {
        return data != null && data.get(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS) instanceof Number value ? value : null;
    }

    public long playtimeSecondsAt(Instant now) {
        Number stored = totalPlaytimeSeconds();
        long total = stored != null ? Math.max(0L, stored.longValue()) : 0L;
        Instant joined = lastLogin();
        if (!isOnline() || joined == null) {
            return total;
        }
        return total + PlayerSessions.countedSeconds(Duration.between(joined, now));
    }

    @Nullable
    public Map<String, Object> asMapAt(Instant now) {
        if (data == null || !isOnline()) {
            return data;
        }
        Map<String, Object> shown = new LinkedHashMap<>(data);
        shown.put(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, playtimeSecondsAt(now));
        return shown;
    }

    public void setTotalPlaytimeSeconds(long totalPlaytimeSeconds) {
        mutable().put(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS, totalPlaytimeSeconds);
    }

    @Nullable
    public Instant lastLogout() {
        return data != null ? StoredInstants.of(data.get(PlayerDataKeys.LAST_LOGOUT)) : null;
    }

    public void setLastLogout(Instant lastLogout) {
        mutable().put(PlayerDataKeys.LAST_LOGOUT, lastLogout);
    }

    @Nullable
    public String lastSkinHash() {
        return data != null && data.get(PlayerDataKeys.LAST_SKIN_HASH) instanceof String value ? value : null;
    }

    public void setLastSkinHash(String lastSkinHash) {
        mutable().put(PlayerDataKeys.LAST_SKIN_HASH, lastSkinHash);
    }

    @Nullable
    public Instant firstJoin() {
        return data != null ? StoredInstants.of(data.get(PlayerDataKeys.FIRST_JOIN)) : null;
    }

    public boolean hasFirstJoin() {
        return data != null && data.containsKey(PlayerDataKeys.FIRST_JOIN);
    }

    public void setFirstJoin(Instant firstJoin) {
        mutable().put(PlayerDataKeys.FIRST_JOIN, firstJoin);
    }

    @Nullable
    public Instant lastLinkedUpdate() {
        return data != null ? StoredInstants.of(data.get(PlayerDataKeys.LAST_LINKED_UPDATE)) : null;
    }

    public void setLastLinkedUpdate(Instant lastLinkedUpdate) {
        mutable().put(PlayerDataKeys.LAST_LINKED_UPDATE, lastLinkedUpdate);
    }

    public Map<String, Object> extra() {
        if (data == null) {
            return Map.of();
        }
        Map<String, Object> extra = new HashMap<>(data);
        PlayerDataKeys.STRUCTURED_KEYS.forEach(extra::remove);
        return extra;
    }

    public List<String> linkedAccountUuids() {
        if (data == null || !(data.get(PlayerDataKeys.LINKED_ACCOUNTS) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
    }

    public List<Map<String, Object>> pendingNotifications() {
        if (data == null || !(data.get(PlayerDataKeys.PENDING_NOTIFICATIONS) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(Map.class::isInstance)
            .map(entry -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> notification = (Map<String, Object>) entry;
                return notification;
            })
            .toList();
    }
}
