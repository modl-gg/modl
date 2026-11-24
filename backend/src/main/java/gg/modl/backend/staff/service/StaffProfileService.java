package gg.modl.backend.staff.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.validation.RequiredText;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SupportedLanguages;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StaffProfileService {
    private static final List<String> DATE_FORMATS = List.of(Staff.DEFAULT_DATE_FORMAT, "MM/DD/YYYY", "DD/MM/YYYY", "YYYY-MM-DD");

    private final StaffRepository staffRepository;
    private final OwnerStaffRecords ownerStaffRecords;
    private final StaffChanges staffChanges;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public Optional<Staff> updateProfile(Server server, String email, @Nullable String newUsername,
                                         @Nullable String newLanguage, @Nullable String newDateFormat) {
        RequiredText.notBlankIfPresent(newUsername, "A username");
        String language = newLanguage != null && SupportedLanguages.isSupported(newLanguage) ? newLanguage : null;
        String dateFormat = newDateFormat != null && DATE_FORMATS.contains(newDateFormat) ? newDateFormat : null;
        boolean changeRequested = newUsername != null || language != null || dateFormat != null;
        return unitOfWork.write(() -> staffRepository.findByEmail(email)
            .or(() -> changeRequested ? createOwnerRecord(server, email) : Optional.empty())
            .flatMap(staff -> storeProfile(server, staff, newUsername, language, dateFormat)));
    }

    private Optional<Staff> createOwnerRecord(Server server, String email) {
        Optional<Staff> created = ownerStaffRecords.create(server, email);
        created.ifPresent(owner -> staffChanges.accessChanged(server, owner.getEmail()));
        return created;
    }

    private Optional<Staff> storeProfile(Server server, Staff staff, @Nullable String newUsername, @Nullable String language,
                                         @Nullable String dateFormat) {
        String username = changedUsername(staff, newUsername);
        if (username == null && language == null && dateFormat == null) {
            return Optional.of(staff);
        }
        String staffId = staff.requireId();
        if (!staffRepository.updateProfile(staffId, username, language, dateFormat, clock.instant())) {
            return Optional.empty();
        }
        Optional<Staff> stored = staffRepository.findById(staffId);
        stored.ifPresent(changed -> staffChanges.profileChanged(server, changed.getEmail()));
        return stored;
    }

    @Nullable
    private String changedUsername(Staff staff, @Nullable String newUsername) {
        if (newUsername == null || newUsername.equals(staff.getUsername())) {
            return null;
        }
        if (ReservedStaffNames.isReserved(newUsername)) {
            throw new ValidationException(ReservedStaffNames.RESERVED_MESSAGE);
        }
        if (staffRepository.existsByUsernameExcludingId(newUsername, staff.requireId())) {
            throw new ConflictException("Username already in use");
        }
        return newUsername;
    }

    public boolean isStaffEmailInUse(Server server, String newEmail, String excludingCurrentEmail) {
        return staffRepository.existsByEmailExcluding(newEmail, excludingCurrentEmail);
    }

    public Optional<Staff> applyStaffEmailChange(Server server, String currentEmail, String newEmail) {
        return unitOfWork.write(() -> staffRepository.findByEmail(currentEmail).flatMap(staff -> {
            String staffId = staff.requireId();
            if (!staffRepository.updateEmail(staffId, newEmail, clock.instant())) {
                return Optional.empty();
            }
            Optional<Staff> stored = staffRepository.findById(staffId);
            stored.ifPresent(changed -> staffChanges.profileChanged(server, currentEmail, changed.getEmail()));
            return stored;
        }));
    }
}
