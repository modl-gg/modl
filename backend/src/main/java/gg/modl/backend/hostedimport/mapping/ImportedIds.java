package gg.modl.backend.hostedimport.mapping;

import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ImportedIds {
    public static final String DERIVED_PREFIX = "imp-";
    private static final int DERIVED_HEX = 28;
    private static final char SEPARATOR = '\u001f';

    private final String exportId;

    public ImportedIds(String exportId) {
        this.exportId = exportId;
    }

    public String adopt(IdKind kind, String scope, @Nullable String sourceId, int limit) {
        if (sourceId == null) {
            return derive(kind, scope, "");
        }
        return isAdoptable(sourceId, limit, kind) ? sourceId : derive(kind, scope, sourceId);
    }

    public String derive(IdKind kind, String scope, String sourceId) {
        String input = exportId + SEPARATOR + kind.name() + SEPARATOR + scope + SEPARATOR + sourceId;
        byte[] hash = sha256().digest(input.getBytes(StandardCharsets.UTF_8));
        return DERIVED_PREFIX + HexFormat.of().formatHex(hash).substring(0, DERIVED_HEX);
    }

    public static boolean isAdoptable(@Nullable String sourceId, int limit, IdKind kind) {
        if (sourceId == null || sourceId.isBlank() || sourceId.codePointCount(0, sourceId.length()) > limit) {
            return false;
        }
        if (sourceId.endsWith(" ") || sourceId.startsWith(DERIVED_PREFIX) || kind.reserves(sourceId)) {
            return false;
        }
        return sourceId.codePoints().noneMatch(Character::isISOControl);
    }

    public static long hash64(String value) {
        byte[] hash = sha256().digest(value.getBytes(StandardCharsets.UTF_8));
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (hash[i] & 0xff);
        }
        return result;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public enum IdKind {
        PLAYER,
        PUNISHMENT,
        PUNISHMENT_NOTE,
        MODIFICATION,
        PLAYER_NOTE,
        NOTIFICATION,
        TICKET,
        REPLY,
        REPLAY,
        ROLE,
        STAFF,
        CATEGORY,
        ARTICLE,
        CARD;

        private static final String RESERVED_TICKET_ID = "new";

        boolean reserves(String sourceId) {
            return this == TICKET && RESERVED_TICKET_ID.equals(sourceId.trim().toLowerCase(Locale.ROOT));
        }
    }
}
