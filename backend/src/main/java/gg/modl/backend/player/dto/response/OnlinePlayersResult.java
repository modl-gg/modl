package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public record OnlinePlayersResult(List<Map<String, Object>> players, @Nullable String nextCursor) {
}
