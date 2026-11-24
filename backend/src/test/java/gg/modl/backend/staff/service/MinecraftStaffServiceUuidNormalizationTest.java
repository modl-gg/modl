package gg.modl.backend.staff.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import gg.modl.backend.staff.repository.StaffRepository;
import gg.modl.backend.support.TestServers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftStaffServiceUuidNormalizationTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void markStaffDisconnectedLowercasesUuidBeforeQueryingRepository() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        MinecraftStaffService service = buildService(staffRepository);

        Server server = server();
        service.markStaffDisconnected(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE");

        verify(staffRepository).updateLastSeenByAssignedMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", NOW);
    }

    @Test
    void assignMinecraftPlayerLowercasesUuidBeforeQueryingPlayerRepository() {
        StaffRepository staffRepository = mock(StaffRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        MinecraftStaffService service = buildService(staffRepository, playerRepository);

        Server server = server();
        Staff staff = Staff.builder()
            .id("staff-id")
            .email("target@example.com")
            .username("target")
            .roleId("helper")
            .build();
        Player player = Player.builder()
            .minecraftUuid(UUID.randomUUID())
            .usernames(new ArrayList<>(List.of(new UsernameEntry("PlayerOne", Instant.now()))))
            .build();

        when(staffRepository.findByEmail("target@example.com")).thenReturn(Optional.of(staff));
        when(staffRepository.findByIdForUpdate("staff-id")).thenReturn(Optional.of(staff));
        when(playerRepository.findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PlayerParts.USERNAMES))
            .thenReturn(Optional.of(player));
        when(staffRepository.findByAssignedMinecraftUuidExcludingId(any(), any())).thenReturn(Optional.empty());

        service.assignMinecraftPlayer(server, "target@example.com",
            new AssignMinecraftPlayerRequest("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", null),
            new RoleAuthorization.PerformerAuthority("owner@example.com", null, true, true));

        verify(playerRepository).findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PlayerParts.USERNAMES);
    }

    private MinecraftStaffService buildService(StaffRepository staffRepository) {
        return buildService(staffRepository, mock(PlayerRepository.class));
    }

    private MinecraftStaffService buildService(StaffRepository staffRepository, PlayerRepository playerRepository) {
        return MinecraftStaffServices.unlocked(staffRepository, playerRepository, mock(PunishmentRepository.class),
            mock(PermissionService.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Server server() {
        Server server = TestServers.server("Server", "owner@example.com");
        server.setId("server-id");
        return server;
    }
}
