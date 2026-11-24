package gg.modl.backend.staff.service;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OwnerStaffRecords {
    private final StaffRepository staffRepository;
    private final Clock clock;

    Optional<Staff> create(Server server, String email) {
        return Optional.ofNullable(SuperAdminStaffSynthesizer.synthesizeIfAdminEmail(server, email, clock.instant()))
            .map(this::store);
    }

    Staff store(Staff owner) {
        StaffInsertOutcome outcome = StaffUsernames.insertWithFreeUsername(staffRepository, owner, owner.getUsername());
        if (outcome != StaffInsertOutcome.INSERTED) {
            throw new ConcurrentUpdateException("The owner's staff record could not be stored: " + outcome);
        }
        return owner;
    }
}
