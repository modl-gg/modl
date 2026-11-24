package gg.modl.backend.staff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.dto.request.AssignMinecraftPlayerRequest;
import gg.modl.backend.staff.dto.response.StaffResponse;
import gg.modl.backend.staff.repository.StaffInsertOutcome;
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinecraftStaffServiceSuperAdminAssignmentTest {

    private static final String ADMIN_EMAIL = "owner@example.com";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final RoleAuthorization.PerformerAuthority SUPER_ADMIN =
        new RoleAuthorization.PerformerAuthority(ADMIN_EMAIL, null, true, true);

    private StaffRepository staffRepository;
    private PlayerRepository playerRepository;
    private MinecraftStaffService service;
    private Server server;

    @BeforeEach
    void setUp() {
        staffRepository = mock(StaffRepository.class);
        playerRepository = mock(PlayerRepository.class);
        service = MinecraftStaffServices.unlocked(staffRepository, playerRepository, mock(PunishmentRepository.class),
            mock(PermissionService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        server = TestServers.server("Server", ADMIN_EMAIL);
        server.setId("server-id");
        server.setCreatedAt(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Test
    void assignForAdminEmailWithoutStaffRecordMaterializesOwnerStaffRecord() {
        UUID playerUuid = UUID.fromString("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86");
        Player player = Player.builder()
            .minecraftUuid(playerUuid)
            .usernames(new ArrayList<>(List.of(new UsernameEntry("byteful", Instant.now()))))
            .build();
        when(staffRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());
        when(playerRepository.findByMinecraftUuid(playerUuid.toString(), PlayerParts.USERNAMES)).thenReturn(Optional.of(player));
        when(staffRepository.findByAssignedMinecraftUuidExcludingId(any(), any())).thenReturn(Optional.empty());
        when(staffRepository.insert(any(Staff.class))).thenAnswer(invocation -> {
            invocation.<Staff>getArgument(0).setId("owner-staff");
            return StaffInsertOutcome.INSERTED;
        });

        Optional<StaffResponse> result = service.assignMinecraftPlayer(server, ADMIN_EMAIL,
            new AssignMinecraftPlayerRequest(playerUuid.toString(), null), SUPER_ADMIN);

        assertTrue(result.isPresent());
        ArgumentCaptor<Staff> saved = ArgumentCaptor.forClass(Staff.class);
        verify(staffRepository).insert(saved.capture());
        assertEquals(ADMIN_EMAIL, saved.getValue().getEmail());
        assertEquals("Admin", saved.getValue().getUsername());
        assertEquals(RoleAuthorization.SUPER_ADMIN_ROLE_ID, saved.getValue().getRoleId());
        assertEquals(playerUuid.toString(), saved.getValue().getAssignedMinecraftUuid());
        assertEquals("byteful", saved.getValue().getAssignedMinecraftUsername());
    }

    @Test
    void clearForUnsavedOwnerReturnsSuccessWithoutPersisting() {
        when(staffRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());

        Optional<StaffResponse> result = service.assignMinecraftPlayer(server, ADMIN_EMAIL,
            new AssignMinecraftPlayerRequest(null, null), SUPER_ADMIN);

        assertTrue(result.isPresent());
        verify(staffRepository, never()).insert(any(Staff.class));
        verify(staffRepository, never()).updateMinecraftAssignment(any(), any(), any(), any());
    }

    @Test
    void assignForUnknownEmailReturnsEmpty() {
        when(staffRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        Optional<StaffResponse> result = service.assignMinecraftPlayer(server, "ghost@example.com",
            new AssignMinecraftPlayerRequest(null, null), SUPER_ADMIN);

        assertTrue(result.isEmpty());
    }

    @Test
    void clearForExistingOwnerRecordPersistsClearedAssignment() {
        Staff ownerRecord = Staff.builder()
            .id("owner-id")
            .email(ADMIN_EMAIL)
            .username("Admin")
            .roleId(RoleAuthorization.SUPER_ADMIN_ROLE_ID)
            .assignedMinecraftUuid("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86")
            .assignedMinecraftUsername("byteful")
            .build();
        when(staffRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.of(ownerRecord));
        when(staffRepository.findByIdForUpdate("owner-id")).thenReturn(Optional.of(ownerRecord));
        when(staffRepository.updateMinecraftAssignment("owner-id", null, null, NOW)).thenReturn(true);
        when(staffRepository.findById("owner-id")).thenReturn(Optional.of(ownerRecord));

        Optional<StaffResponse> result = service.assignMinecraftPlayer(server, ADMIN_EMAIL,
            new AssignMinecraftPlayerRequest(null, null), SUPER_ADMIN);

        assertTrue(result.isPresent());
        verify(staffRepository).updateMinecraftAssignment("owner-id", null, null, NOW);
    }

    @Test
    void linkingTheOwnerAccountMaterializesASuperAdminStaffRecordSoInGameStaffSyncSeesIt() {
        when(staffRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());
        when(staffRepository.findByAssignedMinecraftUuidExcludingId(any(), any())).thenReturn(Optional.empty());
        when(staffRepository.insert(any(Staff.class))).thenAnswer(invocation -> {
            invocation.<Staff>getArgument(0).setId("owner-staff");
            return StaffInsertOutcome.INSERTED;
        });

        MinecraftLinkOutcome outcome = service.linkOwnMinecraftAccount(
            server, ADMIN_EMAIL, "DFC1D5FE-106E-47B2-93B0-F2A9B0DC1F86", "byteful");

        assertEquals(MinecraftLinkOutcome.LINKED, outcome);
        ArgumentCaptor<Staff> saved = ArgumentCaptor.forClass(Staff.class);
        verify(staffRepository).insert(saved.capture());
        assertEquals(ADMIN_EMAIL, saved.getValue().getEmail());
        assertEquals(RoleAuthorization.SUPER_ADMIN_ROLE_ID, saved.getValue().getRoleId());
        assertEquals("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", saved.getValue().getAssignedMinecraftUuid());
        assertEquals("byteful", saved.getValue().getAssignedMinecraftUsername());
    }

    @Test
    void linkingAnAccountAssignedToAnotherStaffMemberIsRefused() {
        Staff other = Staff.builder().id("other-id").email("mod@example.com").username("Mod").build();
        when(staffRepository.findByEmail(ADMIN_EMAIL)).thenReturn(Optional.empty());
        when(staffRepository.findByAssignedMinecraftUuidExcludingId(any(), any())).thenReturn(Optional.of(other));

        MinecraftLinkOutcome outcome = service.linkOwnMinecraftAccount(
            server, ADMIN_EMAIL, "dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", "byteful");

        assertEquals(MinecraftLinkOutcome.ASSIGNED_TO_OTHER, outcome);
        verify(staffRepository, never()).insert(any(Staff.class));
        verify(staffRepository, never()).updateMinecraftAssignment(any(), any(), any(), any());
    }

    @Test
    void linkingForAnIdentityThatNoLongerExistsIsRefused() {
        when(staffRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertEquals(MinecraftLinkOutcome.IDENTITY_NOT_FOUND,
            service.linkOwnMinecraftAccount(server, "ghost@example.com", "dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", "byteful"));
    }

    @Test
    void theConflictCheckComparesStaffIdentitiesById() {
        Staff self = Staff.builder().id("self-id").email("mod@example.com").build();
        Staff other = Staff.builder().id("other-id").email("other@example.com").build();
        when(staffRepository.findByEmail("mod@example.com")).thenReturn(Optional.of(self));
        when(staffRepository.findByIdForUpdate("self-id")).thenReturn(Optional.of(self));
        when(staffRepository.findByAssignedMinecraftUuidExcludingId("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86", "self-id"))
            .thenReturn(Optional.of(other));

        assertTrue(service.isAssignedToAnotherIdentity(server, "mod@example.com", "DFC1D5FE-106E-47B2-93B0-F2A9B0DC1F86"));
    }

    @Test
    void anAccountIsAssignedOnlyToTheStaffRecordHoldingIt() {
        Staff self = Staff.builder().id("self-id").email("mod@example.com").build();
        when(staffRepository.findByEmail("mod@example.com")).thenReturn(Optional.of(self));
        when(staffRepository.findByAssignedMinecraftUuid("dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86"))
            .thenReturn(Optional.of(Staff.builder().id("self-id").build()))
            .thenReturn(Optional.of(Staff.builder().id("other-id").build()));

        assertTrue(service.isAssignedTo(server, "mod@example.com", "dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86"));
        assertFalse(service.isAssignedTo(server, "mod@example.com", "dfc1d5fe-106e-47b2-93b0-f2a9b0dc1f86"));
    }
}
