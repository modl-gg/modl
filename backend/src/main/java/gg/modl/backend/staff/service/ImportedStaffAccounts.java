package gg.modl.backend.staff.service;

import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ImportedStaffAccounts {
    private final StaffRepository staffRepository;
    private final OwnerStaffRecords ownerStaffRecords;
    private final Clock clock;

    public Optional<Staff> ownerRecord(Server server) {
        String ownerEmail = server.getAdminEmail();
        return ownerEmail == null ? Optional.empty() : staffRepository.findByEmail(ownerEmail);
    }

    public Staff createOwnerRecord(Server server, @Nullable Staff archived) {
        String ownerEmail = server.getAdminEmail();
        if (ownerEmail == null) {
            throw new IllegalStateException("An owner record needs a configured owner email");
        }
        Staff owner = SuperAdminStaffSynthesizer.synthesizeFor(server, ownerEmail, clock.instant());
        if (archived != null) {
            owner.setId(archived.getId());
            if (archived.getUsername() != null && !archived.getUsername().isBlank()) {
                owner.setUsername(archived.getUsername());
            }
            owner.setLanguage(archived.getLanguage());
            owner.setDateFormat(archived.getDateFormat());
            owner.setTicketSubscriptionSettings(archived.getTicketSubscriptionSettings());
            owner.setCreatedAt(archived.getCreatedAt());
            owner.setLastSeen(archived.getLastSeen());
        }
        return ownerStaffRecords.store(owner);
    }

    public StaffInsertOutcome insertRestored(Staff staff) {
        String base = staff.getUsername() == null || staff.getUsername().isBlank()
            ? StaffUsernames.fromEmail(staff.getEmail())
            : staff.getUsername();
        return StaffUsernames.insertWithFreeUsername(staffRepository, staff, base);
    }
}
