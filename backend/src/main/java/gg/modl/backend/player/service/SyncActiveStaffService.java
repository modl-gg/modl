package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SyncActiveStaffService {
    private final StaffRepository staffRepository;
    private final StaffRoleRepository staffRoleRepository;
    private final RoleAuthorization roleAuthorization;
    private final Clock clock;

    public List<Map<String, Object>> getActiveStaffMembers(Server server, Map<String, String> onlinePlayerIps) {
        List<Staff> staffWithMinecraft = staffRepository.findAssignedMinecraftStaff();
        Map<String, StaffRole> rolesById = loadRolesById(staffWithMinecraft);
        Function<StaffRole, List<String>> effectivePermissions = roleAuthorization.effectivePermissionsOf(server);
        long now = clock.millis();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Staff staff : staffWithMinecraft) {
            String roleId = RoleAuthorization.effectiveRoleId(server, staff);
            StaffRole role = roleId != null ? rolesById.get(roleId) : null;
            List<String> permissions = role != null ? effectivePermissions.apply(role) : List.of();
            String roleName = role != null ? role.getName() : (roleId != null ? roleId : "");

            String currentIp = onlinePlayerIps.get(staff.getAssignedMinecraftUuid());
            boolean sessionValid = staff.getTwoFactorSessionExpiresAt() != null
                                   && staff.getTwoFactorSessionExpiresAt() > now
                                   && staff.getTwoFactorSessionIp() != null
                                   && staff.getTwoFactorSessionIp().equals(IpAddresses.withoutZoneIfStorable(currentIp));

            Map<String, Object> entry = new HashMap<>();
            entry.put("minecraftUuid", staff.getAssignedMinecraftUuid());
            entry.put("minecraftUsername", staff.getAssignedMinecraftUsername() != null ? staff.getAssignedMinecraftUsername() : "");
            entry.put("staffUsername", staff.getUsername() != null ? staff.getUsername() : "");
            entry.put("staffId", staff.getId());
            entry.put("staffRole", roleName);
            entry.put("permissions", permissions);
            entry.put("email", staff.getEmail() != null ? staff.getEmail() : "");
            entry.put("twoFactorSessionValid", sessionValid);
            result.add(entry);
        }

        return result;
    }

    private Map<String, StaffRole> loadRolesById(List<Staff> staffMembers) {
        Set<String> roleIds = new HashSet<>();
        for (Staff staff : staffMembers) {
            if (staff.getRoleId() != null && !staff.getRoleId().isBlank()) {
                roleIds.add(staff.getRoleId());
            }
        }
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        Map<String, StaffRole> result = new HashMap<>();
        for (StaffRole role : staffRoleRepository.findByIds(roleIds)) {
            result.put(role.getId(), role);
        }
        return result;
    }
}
