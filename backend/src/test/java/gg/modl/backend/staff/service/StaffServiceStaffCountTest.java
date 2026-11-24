package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffServiceStaffCountTest {

    private static final String ADMIN_EMAIL = "owner@example.com";

    private StaffRepository staffRepository;
    private StaffService service;
    private Server server;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        service = new StaffService(
            mock(InvitationRepository.class),
            staffRepository,
            mock(PermissionService.class),
            mock(RoleAuthorization.class),
            mock(RankLocks.class),
            mock(AccountOffboarding.class),
            mock(GeneralSettingsService.class),
            mock(StaffChanges.class),
            new ImmediateUnitOfWork(),
            Clock.systemUTC()
        );
        server = TestServers.server("Server", ADMIN_EMAIL);
        server.setId("server-id");
        server.setCreatedAt(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void countsSuperAdminWithoutStaffRecord() {
        when(staffRepository.countAll()).thenReturn(0L);
        when(staffRepository.existsByEmail(ADMIN_EMAIL)).thenReturn(false);

        assertEquals(1L, service.countStaffIncludingSuperAdmin(server));
    }

    @Test
    void doesNotDoubleCountSuperAdminWithStaffRecord() {
        when(staffRepository.countAll()).thenReturn(3L);
        when(staffRepository.existsByEmail(ADMIN_EMAIL)).thenReturn(true);

        assertEquals(3L, service.countStaffIncludingSuperAdmin(server));
    }

    @Test
    void countsOnlyStaffRecordsWhenAdminEmailMissing() {
        Server serverWithoutAdminEmail = mock(Server.class);
        when(serverWithoutAdminEmail.getAdminEmail()).thenReturn(null);
        when(staffRepository.countAll()).thenReturn(2L);

        assertEquals(2L, service.countStaffIncludingSuperAdmin(serverWithoutAdminEmail));
        verify(staffRepository, never()).existsByEmail(any());
    }
}
