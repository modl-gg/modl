package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TicketDataKeys {
    public static final String CREATOR_EMAIL = "creatorEmail";
    public static final String CONTACT_EMAIL = "contactEmail";
    public static final String CREATOR_IDENTIFIER = "creatorIdentifier";
    public static final String EMAIL_AUTH_ENABLED = "emailAuthEnabled";
    public static final String RESOLUTION = "resolution";

    private static final String PUNISHMENT_ID = "punishmentId";
    private static final String PLAYER_UUID = "playerUuid";
    private static final Set<String> PROTECTED = Set.of(
        CREATOR_EMAIL, CONTACT_EMAIL, CREATOR_IDENTIFIER, EMAIL_AUTH_ENABLED, RESOLUTION, PUNISHMENT_ID, PLAYER_UUID);

    private TicketDataKeys() {
    }

    static void requireProtectedUnchanged(@Nullable Map<String, Object> stored, Map<String, Object> changes) {
        changes.forEach((key, value) -> {
            if (PROTECTED.contains(key) && !Objects.equals(stored == null ? null : stored.get(key), value)) {
                throw new ValidationException("Ticket data field '" + key + "' cannot be changed");
            }
        });
    }
}
