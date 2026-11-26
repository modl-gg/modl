package gg.modl.backend.player.data.punishment;

import org.jspecify.annotations.Nullable;

public final class PlayerFacingReason {
    private static final int LAST_ORDINAL_WRITTEN_FOR_THE_PLAYER = 2;

    private PlayerFacingReason() {
    }

    public static boolean isWrittenForThePlayer(int typeOrdinal) {
        return typeOrdinal >= 0 && typeOrdinal <= LAST_ORDINAL_WRITTEN_FOR_THE_PLAYER;
    }

    @Nullable
    public static String of(int typeOrdinal, @Nullable String issuedReason, @Nullable String playerDescription) {
        if (isWrittenForThePlayer(typeOrdinal) && issuedReason != null && !issuedReason.isBlank()) {
            return issuedReason;
        }
        return playerDescription != null && !playerDescription.isBlank() ? playerDescription : null;
    }
}
