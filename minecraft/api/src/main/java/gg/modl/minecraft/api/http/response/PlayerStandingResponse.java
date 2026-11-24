package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class PlayerStandingResponse {
    private @Nullable String socialStatus;
    private @Nullable String gameplayStatus;
    private int socialPoints;
    private int gameplayPoints;
    private @Nullable List<Entry> punishments;

    public enum State {
        PARDONED,
        ROLLED_BACK,
        QUEUED,
        ACTIVE,
        INACTIVE
    }

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class Entry {
        private static final String CATEGORY_BAN = "BAN", CATEGORY_MUTE = "MUTE";

        private String id;
        private String type;
        private String category;
        private long issuedAt;
        private @Nullable Long durationMs;
        private State state;
        private @Nullable Long endedAt;
        private @Nullable Long expiresAt;
        private @Nullable String reason;

        public boolean isBan() {
            return CATEGORY_BAN.equals(category);
        }

        public boolean isMute() {
            return CATEGORY_MUTE.equals(category);
        }
    }
}
