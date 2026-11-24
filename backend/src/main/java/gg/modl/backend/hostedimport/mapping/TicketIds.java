package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import org.jspecify.annotations.Nullable;
import java.util.Map;

public final class TicketIds {
    public static final int LIMIT = 64;

    private final ImportedIds ids;
    private final Map<String, String> aliases;

    public TicketIds(ImportedIds ids, Map<String, String> aliases) {
        this.ids = ids;
        this.aliases = Map.copyOf(aliases);
    }

    @Nullable
    public String of(@Nullable String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        String alias = aliases.get(sourceId);
        return alias != null ? alias : ids.adopt(IdKind.TICKET, "", sourceId, LIMIT);
    }

    public String collisionAlias(String sourceId) {
        return ids.derive(IdKind.TICKET, "live-collision", sourceId);
    }
}
