package gg.modl.backend.hostedimport.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

public interface ImportTargetRepository {
    boolean hasPunishments();

    boolean hasArticlesBesidesStarters();

    boolean hasStaffOtherThan(String ownerEmail);

    boolean hasActiveInvitations(Instant now);

    Set<String> existingTicketIds(Collection<String> ticketIds);

    Set<String> roleNamesLowerExcept(Collection<String> replacedRoleIds);
}
