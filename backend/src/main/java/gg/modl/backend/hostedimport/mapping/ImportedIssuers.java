package gg.modl.backend.hostedimport.mapping;

import org.jspecify.annotations.Nullable;
import java.util.Map;
import java.util.Objects;

public final class ImportedIssuers {
    private final Map<String, String> restoredStaffIds;
    private final Map<String, String> archivedUsernames;

    public ImportedIssuers(Map<String, String> restoredStaffIds, Map<String, String> archivedUsernames) {
        this.restoredStaffIds = Map.copyOf(restoredStaffIds);
        this.archivedUsernames = Map.copyOf(archivedUsernames);
    }

    @Nullable
    public String staffId(@Nullable String sourceStaffId) {
        return sourceStaffId == null ? null : restoredStaffIds.get(sourceStaffId);
    }

    @Nullable
    public String name(@Nullable String sourceStaffId, @Nullable String snapshot) {
        if (snapshot != null && !snapshot.isBlank()) {
            return snapshot;
        }
        if (sourceStaffId == null) {
            return null;
        }
        return Objects.requireNonNullElse(archivedUsernames.get(sourceStaffId), sourceStaffId);
    }

    public boolean restores(String sourceStaffId) {
        return restoredStaffIds.containsKey(sourceStaffId);
    }
}
