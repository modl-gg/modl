package gg.modl.backend.hostedimport.service;

import gg.modl.backend.hostedimport.mapping.StaffPlan;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.model.RetentionPlan;
import org.jspecify.annotations.Nullable;
import java.util.List;

public record ImportPreview(
    ImportJob job,
    ImportTally tally,
    List<RetentionPlan> retention,
    @Nullable String archivedOwnerEmail,
    String localOwnerEmail,
    boolean ownerEmailsMatch,
    @Nullable String archivedOwnerMinecraftUsername,
    boolean archivedOwnerHasStaffRecord,
    long availableBytes,
    List<StaffPlan.Candidate> staff
) {
}
