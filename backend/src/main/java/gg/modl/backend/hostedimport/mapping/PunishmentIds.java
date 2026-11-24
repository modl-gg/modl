package gg.modl.backend.hostedimport.mapping;

import gg.modl.backend.hostedimport.mapping.ImportedIds.IdKind;
import org.jspecify.annotations.Nullable;

public final class PunishmentIds {
    public static final int LIMIT = 64;

    private final ImportedIds ids;
    private final PunishmentKeepers keepers;

    public PunishmentIds(ImportedIds ids, PunishmentKeepers keepers) {
        this.ids = ids;
        this.keepers = keepers;
    }

    public String of(String sourceId, String playerUuid) {
        if (keepers.isKeptBy(sourceId, playerUuid)) {
            return ids.adopt(IdKind.PUNISHMENT, "", sourceId, LIMIT);
        }
        return ids.derive(IdKind.PUNISHMENT, playerUuid, sourceId);
    }

    @Nullable
    public String reference(@Nullable String sourceId, @Nullable String scopeUuid) {
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        return scopeUuid == null ? ids.adopt(IdKind.PUNISHMENT, "", sourceId, LIMIT) : of(sourceId, scopeUuid);
    }

    public String withinPlayerDuplicate(String sourceId, String playerUuid, int index) {
        return ids.derive(IdKind.PUNISHMENT, playerUuid + "#" + index, sourceId);
    }
}
