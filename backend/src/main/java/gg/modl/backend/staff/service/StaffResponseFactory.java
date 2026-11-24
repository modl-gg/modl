package gg.modl.backend.staff.service;

import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.response.StaffResponse;

final class StaffResponseFactory {
    private static final String ACTIVE_STATUS = "Active";

    private StaffResponseFactory() {
    }

    static StaffResponse active(Staff staff, String roleName) {
        return new StaffResponse(
            staff.getId(),
            staff.getEmail(),
            staff.getUsername(),
            roleName,
            ACTIVE_STATUS,
            staff.getAssignedMinecraftUuid(),
            staff.getAssignedMinecraftUsername(),
            staff.getCreatedAt()
        );
    }
}
