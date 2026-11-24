package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;
import java.util.List;
import java.util.Map;

public sealed interface LinkedAccountsResult permits LinkedAccountsResult.Found, LinkedAccountsResult.NotFound {

    record Found(
        List<Map<String, Object>> linkedAccounts,
        @Nullable Integer totalCount,
        @Nullable Integer page,
        @Nullable Boolean hasMore,
        boolean truncated
    ) implements LinkedAccountsResult {
    }

    record NotFound(String message) implements LinkedAccountsResult {
    }
}
