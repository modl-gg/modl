package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.password.AccountPasswords;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.auth.AccountOffboarding;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ServiceUnavailableException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.GeneralSettings;
import gg.modl.backend.settings.service.GeneralSettingsService;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationServiceAcceptTest {
    private static final String TOKEN = "invitation-token-0123456789";
    private static final String EMAIL = "helper@example.com";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final InvitationRepository invitationRepository = mock(InvitationRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final GeneralSettingsService generalSettingsService = mock(GeneralSettingsService.class);
    private final StaffChanges staffChanges = mock(StaffChanges.class);
    private final PanelActionAuditor panelActionAuditor = mock(PanelActionAuditor.class);
    private final OneTimeCodeCodec oneTimeCodeCodec = new OneTimeCodeCodec(() -> "invitation-test-secret");
    private final StaffService staffService = new StaffService(
        invitationRepository,
        staffRepository,
        permissionService,
        mock(RoleAuthorization.class),
        mock(RankLocks.class),
        mock(AccountOffboarding.class),
        generalSettingsService,
        staffChanges,
        new ImmediateUnitOfWork(),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final InvitationService service = new InvitationService(
        staffRepository,
        invitationRepository,
        staffService,
        mock(EmailService.class),
        mock(IdGenerator.class),
        oneTimeCodeCodec,
        mock(PublicUrls.class),
        mock(EmailCapability.class),
        permissionService,
        mock(RoleAuthorization.class),
        mock(RankLocks.class),
        panelActionAuditor,
        mock(StaffCredentialRepository.class), mock(AccountPasswords.class), SignInSettingsStub.emailCode(),
        new ImmediateUnitOfWork(),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final Server server = TestServers.server("Test", "owner@example.com");

    @BeforeEach
    void stubCollaborators() {
        when(permissionService.getRoleById(server, "helper")).thenReturn(Optional.of(helperRole()));
        when(generalSettingsService.getGeneralSettings(server))
            .thenReturn(GeneralSettings.builder().defaultLanguage("en").build());
        when(staffRepository.insert(any(Staff.class))).thenReturn(StaffInsertOutcome.INSERTED);
    }

    @Test
    void acceptConsumesTheHashedTokenAndCreatesTheStaffMemberThroughTheSharedCreationPath() {
        givenActiveInvitation();

        Staff staff = service.acceptInvitation(server, TOKEN, null);

        assertEquals(EMAIL, staff.getEmail());
        assertEquals("helper", staff.getUsername());
        assertEquals("helper", staff.getRoleId());
        assertEquals("en", staff.getLanguage());
        verify(invitationRepository, never()).consumeActive(eq(TOKEN), any(Instant.class));
        verify(staffChanges).accessChanged(server, EMAIL);
        verify(panelActionAuditor).recordStaffAction(server, EMAIL, "Accepted a staff invitation and signed in");
    }

    @Test
    void acceptRejectsATokenThatIsUnknownExpiredOrAlreadyUsed() {
        when(invitationRepository.consumeActive(oneTimeCodeCodec.hash(TOKEN), NOW)).thenReturn(Optional.empty());

        assertThrows(ValidationException.class, () -> service.acceptInvitation(server, TOKEN, null));

        verify(staffRepository, never()).insert(any(Staff.class));
        verifyNoInteractions(panelActionAuditor);
    }

    @Test
    void acceptRejectsBlankAndOversizedTokensWithoutTouchingTheStore() {
        String oversized = "a".repeat(RequestValidationLimits.STAFF_INVITATION_TOKEN_MAX_LENGTH + 1);

        assertThrows(ValidationException.class, () -> service.acceptInvitation(server, " ", null));
        assertThrows(ValidationException.class, () -> service.acceptInvitation(server, oversized, null));

        verifyNoInteractions(invitationRepository);
    }

    @Test
    void acceptRetriesTheUsernameWhenAConcurrentAcceptTookIt() {
        givenActiveInvitation();
        when(staffRepository.existsByUsername("helper")).thenReturn(false, true);
        when(staffRepository.insert(any(Staff.class)))
            .thenReturn(StaffInsertOutcome.USERNAME_TAKEN, StaffInsertOutcome.INSERTED);

        Staff staff = service.acceptInvitation(server, TOKEN, null);

        assertEquals("helper1", staff.getUsername());
        verify(staffRepository, times(2)).insert(any(Staff.class));
    }

    @Test
    void acceptFailsItsUnitWhenNoFreeUsernameCanBeClaimed() {
        givenActiveInvitation();
        when(staffRepository.insert(any(Staff.class))).thenReturn(StaffInsertOutcome.USERNAME_TAKEN);

        assertThrows(ServiceUnavailableException.class, () -> service.acceptInvitation(server, TOKEN, null));

        verify(staffRepository, times(StaffUsernames.ATTEMPTS)).insert(any(Staff.class));
        verify(staffChanges, never()).accessChanged(any(Server.class), anyString());
        verifyNoInteractions(panelActionAuditor);
    }

    @Test
    void acceptStopsAtOnceWhenTheEmailBecameStaffDuringCreation() {
        givenActiveInvitation();
        when(staffRepository.insert(any(Staff.class))).thenReturn(StaffInsertOutcome.EMAIL_TAKEN);

        assertThrows(ConflictException.class, () -> service.acceptInvitation(server, TOKEN, null));

        verify(staffRepository, times(1)).insert(any(Staff.class));
        verify(staffChanges, never()).accessChanged(any(Server.class), anyString());
    }

    private void givenActiveInvitation() {
        when(invitationRepository.consumeActive(oneTimeCodeCodec.hash(TOKEN), NOW)).thenReturn(Optional.of(Invitation.builder()
            .id("invitation-id")
            .email(EMAIL)
            .roleId("helper")
            .tokenHash(oneTimeCodeCodec.hash(TOKEN))
            .expiresAt(NOW.plusSeconds(3600))
            .build()));
    }

    private static StaffRole helperRole() {
        return StaffRole.builder()
            .id("helper")
            .name("Helper")
            .order(3)
            .permissions(List.of())
            .build();
    }
}
