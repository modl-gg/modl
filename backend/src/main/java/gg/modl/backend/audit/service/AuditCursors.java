package gg.modl.backend.audit.service;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.repository.PunishmentListCursor;
import gg.modl.backend.player.repository.PunishmentListQuery;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class AuditCursors {
    private static final String SEPARATOR = ":";
    private static final String ASCENDING = "a";
    private static final String DESCENDING = "d";
    private static final String INVALID = "Invalid cursor";

    private AuditCursors() {
    }

    static String encode(PunishmentListQuery query, PunishmentListCursor cursor) {
        String value = orderOf(query) + SEPARATOR + cursor.key() + SEPARATOR + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    static PunishmentListCursor decode(PunishmentListQuery query, @Nullable String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            return parse(orderOf(query), new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new ValidationException(INVALID, malformed);
        }
    }

    private static String orderOf(PunishmentListQuery query) {
        return query.sortKey().name() + SEPARATOR + (query.ascending() ? ASCENDING : DESCENDING);
    }

    private static PunishmentListCursor parse(String order, String value) {
        String prefix = order + SEPARATOR;
        if (!value.startsWith(prefix)) {
            throw new ValidationException(INVALID);
        }
        String position = value.substring(prefix.length());
        int separator = position.indexOf(SEPARATOR);
        if (separator <= 0) {
            throw new ValidationException(INVALID);
        }
        long key = Long.parseLong(position.substring(0, separator));
        String id = position.substring(separator + 1);
        if (id.isEmpty() || !TextLimits.canMatch(PUNISHMENTS.ID, id)) {
            throw new ValidationException(INVALID);
        }
        return new PunishmentListCursor(key, id);
    }
}
