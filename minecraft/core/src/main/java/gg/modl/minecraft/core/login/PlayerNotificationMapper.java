package gg.modl.minecraft.core.login;

import gg.modl.minecraft.api.http.response.SyncResponse;
import gg.modl.minecraft.core.util.PluginLogger;
import org.jspecify.annotations.Nullable;

import java.util.Map;

public final class PlayerNotificationMapper {
    private final PluginLogger logger;

    public PlayerNotificationMapper(PluginLogger logger) {
        this.logger = logger;
    }

    public SyncResponse.@Nullable PlayerNotification mapToPlayerNotification(Map<String, Object> data) {
        try {
            Object id = data.get("id");
            Object message = data.get("message");
            Object type = data.get("type");
            if (!(id instanceof String) || !(message instanceof String) || !(type instanceof String)) {
                logger.warning("Skipped a login notification without an id, message and type");
                return null;
            }
            Object sentAt = data.get("timestamp");
            Long timestamp = sentAt instanceof Number ? ((Number) sentAt).longValue() : null;

            Map<String, Object> dataMap = null;
            Object nestedData = data.get("data");
            if (nestedData instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) nestedData;
                dataMap = casted;
            }

            return new SyncResponse.PlayerNotification(
                    (String) id,
                    (String) message,
                    (String) type,
                    (String) data.get("targetPlayerUuid"),
                    dataMap,
                    timestamp);
        } catch (Exception e) {
            logger.warning("Failed to convert notification data: " + e.getMessage());
            return null;
        }
    }
}
