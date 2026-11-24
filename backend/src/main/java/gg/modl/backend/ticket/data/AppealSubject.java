package gg.modl.backend.ticket.data;

import gg.modl.backend.infrastructure.util.UuidUtils;
import org.jspecify.annotations.Nullable;
import java.util.Map;
import java.util.Optional;

public record AppealSubject(String punishmentId, String playerUuid) {
    static final String PUNISHMENT_ID_KEY = "punishmentId";
    static final String PLAYER_UUID_KEY = "playerUuid";

    public AppealSubject {
        playerUuid = UuidUtils.normalize(playerUuid);
    }

    public static Optional<AppealSubject> from(@Nullable Map<String, Object> data) {
        return punishmentIdOf(data).flatMap(punishmentId -> nonBlankString(data, PLAYER_UUID_KEY)
            .map(playerUuid -> new AppealSubject(punishmentId, playerUuid)));
    }

    public static Optional<String> punishmentIdOf(@Nullable Map<String, Object> data) {
        return nonBlankString(data, PUNISHMENT_ID_KEY);
    }

    public void writeTo(Map<String, Object> data) {
        data.put(PUNISHMENT_ID_KEY, punishmentId);
        data.put(PLAYER_UUID_KEY, playerUuid);
    }

    private static Optional<String> nonBlankString(@Nullable Map<String, Object> data, String key) {
        if (data != null && data.get(key) instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        return Optional.empty();
    }
}
