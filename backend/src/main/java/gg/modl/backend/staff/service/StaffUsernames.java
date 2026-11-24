package gg.modl.backend.staff.service;

import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;

final class StaffUsernames {
    static final int ATTEMPTS = 5;

    private StaffUsernames() {
    }

    static String fromEmail(String email) {
        return email.split("@")[0].replaceAll("[^a-zA-Z0-9_]", "_");
    }

    static StaffInsertOutcome insertWithFreeUsername(StaffRepository staffRepository, Staff staff, String baseUsername) {
        StaffInsertOutcome outcome = StaffInsertOutcome.USERNAME_TAKEN;
        for (int attempt = 0; attempt < ATTEMPTS && outcome == StaffInsertOutcome.USERNAME_TAKEN; attempt++) {
            staff.setUsername(freeUsername(staffRepository, baseUsername));
            outcome = staffRepository.insert(staff);
        }
        return outcome;
    }

    private static String freeUsername(StaffRepository staffRepository, String baseUsername) {
        String candidate = withSuffix(baseUsername, "");
        for (int suffix = 1; isUnavailable(staffRepository, candidate); suffix++) {
            candidate = withSuffix(baseUsername, Integer.toString(suffix));
        }
        return candidate;
    }

    private static boolean isUnavailable(StaffRepository staffRepository, String candidate) {
        return ReservedStaffNames.isReserved(candidate) || staffRepository.existsByUsername(candidate);
    }

    private static String withSuffix(String baseUsername, String suffix) {
        int room = RequestValidationLimits.STAFF_USERNAME_MAX_LENGTH - suffix.length();
        return baseUsername.substring(0, Math.min(baseUsername.length(), room)) + suffix;
    }
}
