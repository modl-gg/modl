package gg.modl.backend.staff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StaffServiceOffboardingTest {
    private final Server server = TestServers.server("Server", "new-owner@example.com");
    private StaffRepository staffRepository;
    private InvitationRepository invitationRepository;
    private AccountOffboarding accountOffboarding;
    private StaffService service;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        invitationRepository = mock(InvitationRepository.class);
        accountOffboarding = mock(AccountOffboarding.class);
        service = new StaffService(invitationRepository, staffRepository, mock(PermissionService.class), mock(RoleAuthorization.class),
            mock(RankLocks.class), accountOffboarding, mock(GeneralSettingsService.class), mock(StaffChanges.class), new ImmediateUnitOfWork(), Clock.systemUTC());
    }

    @Test
    void removingAStaffMemberRevokesEveryWayTheyCouldStillGetIn() {
        Staff moderator = staff("staff-1", "mod@example.com", "moderator");
        when(invitationRepository.findById("staff-1")).thenReturn(Optional.empty());
        when(staffRepository.findByIdForUpdate("staff-1")).thenReturn(Optional.of(moderator));
        when(staffRepository.deleteById("staff-1")).thenReturn(true);

        assertThat(service.deleteStaff(server, "staff-1", RoleAuthorization.PerformerAuthority.unidentified())).isTrue();

        verify(staffRepository).deleteById("staff-1");
        verify(accountOffboarding).revokeAllAccess(server, "mod@example.com");
    }

    @Test
    void aFormerOwnerWithoutAStaffRoleLosesEveryAccessAndTheirOwnerRecord() {
        when(staffRepository.findByEmail("old-owner@example.com"))
            .thenReturn(Optional.of(staff("owner-record", "old-owner@example.com", RoleAuthorization.SUPER_ADMIN_ROLE_ID)));

        service.offboardPreviousAdminEmail(server, "old-owner@example.com");

        verify(staffRepository).deleteById("owner-record");
        verify(accountOffboarding).revokeAllAccess(server, "old-owner@example.com");
    }

    @Test
    void aFormerOwnerWithoutAnyRecordIsStillSignedOutEverywhere() {
        when(staffRepository.findByEmail("old-owner@example.com")).thenReturn(Optional.empty());

        service.offboardPreviousAdminEmail(server, "old-owner@example.com");

        verify(staffRepository, never()).deleteById(anyString());
        verify(accountOffboarding).revokeAllAccess(server, "old-owner@example.com");
    }

    @Test
    void aFormerOwnerWhoIsAlsoStaffKeepsTheirStaffAccessButLosesTheirOwnerSessions() {
        when(staffRepository.findByEmail("old-owner@example.com"))
            .thenReturn(Optional.of(staff("staff-2", "old-owner@example.com", "admin")));

        service.offboardPreviousAdminEmail(server, "old-owner@example.com");

        verify(staffRepository, never()).deleteById(anyString());
        verify(accountOffboarding).endSessions(server, "old-owner@example.com");
        verify(accountOffboarding, never()).revokeAllAccess(any(), anyString());
    }

    @Test
    void thereIsNothingToOffboardWhenTheInstanceHadNoOwner() {
        service.offboardPreviousAdminEmail(server, null);

        verify(accountOffboarding, never()).revokeAllAccess(any(), any());
        verify(accountOffboarding, never()).endSessions(any(), any());
    }

    private static Staff staff(String id, String email, String roleId) {
        return Staff.builder().id(id).email(email).roleId(roleId).build();
    }
}
