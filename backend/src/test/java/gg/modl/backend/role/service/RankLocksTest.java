package gg.modl.backend.role.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.repository.StaffRoleRepository;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RankLocksTest {
    private static final String EMAIL = "manager@example.com";
    private static final PerformerAuthority PERFORMER = new PerformerAuthority(EMAIL, "manager", false, true);

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final StaffRoleRepository roleRepository = mock(StaffRoleRepository.class);
    private final RankLocks rankLocks = new RankLocks(staffRepository, roleRepository);
    private final Server server = TestServers.server("server", "owner@example.com");

    @Test
    void thePerformerAndTheTargetsAreLockedInIdOrderStaffBeforeRoles() {
        givenPerformer("m", "manager");

        rankLocks.lockStaff(server, PERFORMER, "z", "a", "m");
        rankLocks.lockRoles(PERFORMER, "z-role", null, "a-role", "manager");

        InOrder order = inOrder(staffRepository, roleRepository);
        order.verify(staffRepository).findByIdForUpdate("a");
        order.verify(staffRepository).findByIdForUpdate("m");
        order.verify(staffRepository).findByIdForUpdate("z");
        order.verify(roleRepository).findByIdForUpdate("a-role");
        order.verify(roleRepository).findByIdForUpdate("manager");
        order.verify(roleRepository).findByIdForUpdate("z-role");
        order.verifyNoMoreInteractions();
    }

    @Test
    void aPerformerWhoseRoleChangedSinceAuthorisationIsRefused() {
        givenPerformer("m", "helper");

        assertThrows(ForbiddenException.class, () -> rankLocks.lockStaff(server, PERFORMER));
    }

    @Test
    void aPerformerWhoIsNoLongerStaffIsRefused() {
        assertThrows(ForbiddenException.class, () -> rankLocks.lockStaff(server, PERFORMER));
    }

    private void givenPerformer(String id, String lockedRoleId) {
        when(staffRepository.findByEmail(EMAIL)).thenReturn(Optional.of(Staff.builder().id(id).email(EMAIL).roleId("manager").build()));
        when(staffRepository.findByIdForUpdate(id))
            .thenReturn(Optional.of(Staff.builder().id(id).email(EMAIL).roleId(lockedRoleId).build()));
    }
}
