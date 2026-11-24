package gg.modl.backend.storage.settings;

import java.util.List;

public record StorageUsage(List<GroupUsage> groups, long usedBytes, long capacityBytes) {
    public StorageUsage {
        groups = List.copyOf(groups);
    }

    public record GroupUsage(StorageUsageGroup group, long files, long bytes) {
    }
}
