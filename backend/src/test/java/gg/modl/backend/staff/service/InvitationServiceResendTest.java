package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.password.AccountPasswords;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.settings.SignInSettingsStub;
import gg.modl.backend.auth.ImmediateUnitOfWork;
import gg.modl.backend.email.EmailCapability;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.email.ValidityPeriod;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.data.StaffRole;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.role.service.RankLocks;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Invitation;
import gg.modl.backend.staff.dto.response.InviteResultResponse;
import gg.modl.backend.staff.repository.InvitationRepository;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationServiceResendTest {
    private static final String INVITATION_ID = "invitation-id";
    private static final String INVITEE = "admin-candidate@example.com";
    private static final String FRESH_TOKEN = "fresh-invitation-token";
    private static final String FRESH_LINK = "https://panel.example.com/accept-invitation?token=" + FRESH_TOKEN;
    private static final String PREVIOUS_HASH = "previous-token-hash";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant PREVIOUS_EXPIRY = NOW.plusSeconds(3600);
    private static final PerformerAuthority OWNER = new PerformerAuthority("owner@example.com", null, true, true);
    private static final PerformerAuthority MODERATOR = new PerformerAuthority("mod@example.com", "moderator", false, true);

    private final InvitationRepository invitationRepository = mock(InvitationRepository.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final EmailService emailService = mock(EmailService.class);
    private final IdGenerator idGenerator = mock(IdGenerator.class);
    private final PublicUrls publicUrls = mock(PublicUrls.class);
    private final OneTimeCodeCodec oneTimeCodeCodec = new OneTimeCodeCodec(() -> "invitation-test-secret");
    private final StaffRepository staffRepository = mock(StaffRepository.class);
    private final InvitationService service = new InvitationService(
        staffRepository,
        invitationRepository,
        mock(StaffService.class),
        emailService,
        idGenerator,
        oneTimeCodeCodec,
        publicUrls,
        mock(EmailCapability.class),
        permissionService,
        new RoleAuthorization(permissionService, staffRepository, mock(StaffLookupCache.class)),
        mock(RankLocks.class),
        mock(PanelActionAuditor.class),
        mock(StaffCredentialRepository.class), mock(AccountPasswords.class), SignInSettingsStub.emailCode(),
        new ImmediateUnitOfWork(),
        Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final Server server = TestServers.server("Test", "owner@example.com");

    @BeforeEach
    void stubCollaborators() {
        when(invitationRepository.findById(INVITATION_ID)).thenReturn(Optional.of(Invitation.builder()
            .id(INVITATION_ID)
            .email(INVITEE)
            .roleId("admin")
            .tokenHash(PREVIOUS_HASH)
            .expiresAt(PREVIOUS_EXPIRY)
            .build()));
        when(permissionService.getRoleById(server, "admin")).thenReturn(Optional.of(role("admin", "Admin", 1)));
        when(permissionService.getRoleById(server, "moderator")).thenReturn(Optional.of(role("moderator", "Moderator", 2)));
        when(idGenerator.generateToken()).thenReturn(FRESH_TOKEN);
        when(publicUrls.invitation(FRESH_TOKEN)).thenReturn(FRESH_LINK);
        when(invitationRepository.rotateToken(eq(INVITATION_ID), eq(PREVIOUS_HASH), eq(oneTimeCodeCodec.hash(FRESH_TOKEN)),
            any(Instant.class), any(Instant.class))).thenReturn(true);
    }

    @Test
    void performerWhoCannotGrantTheInvitedRoleIsForbiddenAndTheTokenStaysUnrotated() {
        assertThrows(ForbiddenException.class, () -> service.resendInvitation(server, INVITATION_ID, MODERATOR));

        verify(invitationRepository, never())
            .rotateToken(anyString(), anyString(), anyString(), any(Instant.class), any(Instant.class));
        verifyNoInteractions(idGenerator, emailService);
    }

    @Test
    void invitationsForTheSuperAdminRoleCannotBeResentEvenByTheOwner() {
        when(permissionService.getRoleById(server, "admin"))
            .thenReturn(Optional.of(role(RoleAuthorization.SUPER_ADMIN_ROLE_ID, RoleAuthorization.SUPER_ADMIN_ROLE_NAME, 0)));

        assertThrows(ForbiddenException.class, () -> service.resendInvitation(server, INVITATION_ID, OWNER));

        verify(invitationRepository, never())
            .rotateToken(anyString(), anyString(), anyString(), any(Instant.class), any(Instant.class));
    }

    @Test
    void resendStoresOnlyTheHashOfTheRotatedTokenAndReturnsItsLinkWithoutEmailDelivery() {
        Optional<InviteResultResponse> result = service.resendInvitation(server, INVITATION_ID, OWNER);

        assertTrue(result.isPresent());
        assertEquals(List.of(new InviteResultResponse.InviteLink(INVITEE, FRESH_LINK)), result.get().inviteLinks());
        verify(invitationRepository).rotateToken(eq(INVITATION_ID), eq(PREVIOUS_HASH), eq(oneTimeCodeCodec.hash(FRESH_TOKEN)),
            any(Instant.class), any(Instant.class));
        verify(invitationRepository, never())
            .rotateToken(anyString(), anyString(), eq(FRESH_TOKEN), any(Instant.class), any(Instant.class));
        verify(emailService).sendStaffInviteEmail(eq(INVITEE), any(), eq("Admin"), eq(FRESH_LINK), any(ValidityPeriod.class));
    }

    @Test
    void failedDeliveryRestoresThePreviousTokenHash() {
        doThrow(new IllegalStateException("smtp down")).when(emailService)
            .sendStaffInviteEmail(anyString(), any(), anyString(), anyString(), any(ValidityPeriod.class));

        assertThrows(IllegalStateException.class, () -> service.resendInvitation(server, INVITATION_ID, OWNER));

        verify(invitationRepository).rotateToken(eq(INVITATION_ID), eq(oneTimeCodeCodec.hash(FRESH_TOKEN)),
            eq(PREVIOUS_HASH), eq(PREVIOUS_EXPIRY), eq(NOW));
    }

    @Test
    void invitationConsumedOrRotatedConcurrentlyIsNotResent() {
        when(invitationRepository.rotateToken(eq(INVITATION_ID), eq(PREVIOUS_HASH), anyString(),
            any(Instant.class), any(Instant.class))).thenReturn(false);

        assertTrue(service.resendInvitation(server, INVITATION_ID, OWNER).isEmpty());

        verifyNoInteractions(emailService);
    }

    @Test
    void unknownInvitationResolvesToNothing() {
        assertTrue(service.resendInvitation(server, "missing-id", OWNER).isEmpty());

        verifyNoInteractions(idGenerator, emailService);
    }

    private static StaffRole role(String id, String name, int order) {
        return StaffRole.builder()
            .id(id)
            .name(name)
            .order(order)
            .permissions(List.of(PermissionService.ADMIN_STAFF_MANAGE_MEMBERS))
            .build();
    }
}
