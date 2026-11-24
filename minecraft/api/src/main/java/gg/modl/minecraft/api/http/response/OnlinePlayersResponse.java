package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

@Getter @NoArgsConstructor @AllArgsConstructor
public class OnlinePlayersResponse extends StatusResponse {
    private List<OnlinePlayer> players;
    private int status;
    private @Nullable String nextCursor;

    @Getter @NoArgsConstructor @AllArgsConstructor
    public static class OnlinePlayer {
        private String uuid, username;
        private @Nullable Instant joinedAt;
        private Long totalPlaytimeMs;
    }
}
