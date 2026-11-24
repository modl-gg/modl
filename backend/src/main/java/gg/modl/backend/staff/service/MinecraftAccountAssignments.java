package gg.modl.backend.staff.service;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MinecraftAccountAssignments {
    private final StaffRepository staffRepository;
    private final OwnerStaffRecords ownerStaffRecords;
    private final AccountOffboarding accountOffboarding;
    private final StaffChanges staffChanges;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    Optional<Staff> store(Server server, Staff staff, @Nullable String minecraftUuid, @Nullable String minecraftUsername,
                          @Nullable String actorEmail) {
        boolean accountChanged = !Objects.equals(staff.getAssignedMinecraftUuid(), minecraftUuid);
        Optional<Staff> saved = staff.getId() == null
            ? Optional.of(storeOwner(staff, minecraftUuid, minecraftUsername))
            : update(staff.getId(), minecraftUuid, minecraftUsername);
        saved.ifPresent(stored -> {
            if (accountChanged) {
                endAccessThroughPreviousAccount(server, stored);
                audit(server, actorEmail, describe(stored.getEmail(), minecraftUuid));
            }
            staffChanges.accessChanged(server, stored.getEmail());
        });
        return saved;
    }

    private void endAccessThroughPreviousAccount(Server server, Staff staff) {
        staffRepository.clearTwoFactor(staff.requireId());
        accountOffboarding.revokeLoginLinks(server, staff.getEmail());
    }

    private void audit(Server server, @Nullable String actorEmail, String description) {
        unitOfWork.afterCommit(() -> panelActionAuditor.recordStaffAction(server, actorEmail, description));
    }

    private static String describe(String email, @Nullable String minecraftUuid) {
        return minecraftUuid == null
            ? "Removed the Minecraft account of " + email
            : "Assigned Minecraft account " + minecraftUuid + " to " + email;
    }

    private Optional<Staff> update(String staffId, @Nullable String minecraftUuid, @Nullable String minecraftUsername) {
        return staffRepository.updateMinecraftAssignment(staffId, minecraftUuid, minecraftUsername, clock.instant())
            ? staffRepository.findById(staffId)
            : Optional.empty();
    }

    private Staff storeOwner(Staff owner, @Nullable String minecraftUuid, @Nullable String minecraftUsername) {
        owner.setAssignedMinecraftUuid(minecraftUuid);
        owner.setAssignedMinecraftUsername(minecraftUsername);
        return ownerStaffRecords.store(owner);
    }
}
