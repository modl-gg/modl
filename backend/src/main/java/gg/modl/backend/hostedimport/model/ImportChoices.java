package gg.modl.backend.hostedimport.model;

import java.util.Set;

public record ImportChoices(Set<String> staffIds, boolean mergeArchivedOwner, RetentionPlan retention) {
    public ImportChoices {
        staffIds = Set.copyOf(staffIds);
    }

    public boolean selects(String sourceStaffId) {
        return staffIds.contains(sourceStaffId);
    }
}
