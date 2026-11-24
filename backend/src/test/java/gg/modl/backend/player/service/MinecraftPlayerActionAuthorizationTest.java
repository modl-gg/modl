package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.iplookup.service.AddressForwardingMonitor;
import gg.modl.backend.iplookup.service.IpLookupService;
import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.dto.response.CreateNoteResult;
import gg.modl.backend.player.dto.response.PardonResult;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.role.service.RoleAuthorization.PerformerAuthority;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.ticket.repository.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MinecraftPlayerActionAuthorizationTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String STAFF = "staff-1";
    private static final String ROLE = "role";

    private final Server server = TestServers.server("Demo", "owner@example.com");
    private final PlayerService playerService = mock(PlayerService.class);
    private final PlayerRepository playerRepository = mock(PlayerRepository.class);
    private final PunishmentLifecycleService lifecycle = mock(PunishmentLifecycleService.class);
    private final RoleAuthorization roles = mock(RoleAuthorization.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final MinecraftPlayerService players = new MinecraftPlayerService(
        playerService,
        playerRepository,
        mock(PunishmentRepository.class),
        mock(TicketRepository.class),
        mock(PlayerStatusCalculator.class),
        mock(PunishmentTypeService.class),
        lifecycle,
        mock(AccountLinkingService.class),
        mock(IssuerNameResolver.class),
        mock(IpLookupService.class),
        mock(AddressForwardingMonitor.class),
        new MinecraftIssuerAuthorizer(roles, permissions),
        new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

    @ParameterizedTest
    @EnumSource(PlayerNoteKind.class)
    void thePermissionOfTheKindIsEnoughToWriteIt(PlayerNoteKind kind) {
        staffHolding(PlayerNotePermission.requiredFor(kind));
        knownPlayer();

        CreateNoteResult result = players.createNote(server, PLAYER.toString(), kind, "text", "Mod", staff());

        assertThat(result).isEqualTo(new CreateNoteResult.Created("Note added"));
        verify(playerRepository).addNote(eq("player-id"), any());
    }

    @ParameterizedTest
    @EnumSource(PlayerNoteKind.class)
    void thePermissionOfTheOtherKindDoesNotHelp(PlayerNoteKind kind) {
        staffHolding(kind == PlayerNoteKind.NOTE ? PermissionService.PLAYER_WARN : PermissionService.PLAYER_NOTE);

        assertThatThrownBy(() -> players.createNote(server, PLAYER.toString(), kind, "text", "Mod", staff()))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(playerRepository);
    }

    @ParameterizedTest
    @EnumSource(PlayerNoteKind.class)
    void punishmentModifyGrantsNeitherKind(PlayerNoteKind kind) {
        staffHolding(PermissionService.PUNISHMENT_MODIFY, PermissionService.PUNISHMENT_VIEW);

        assertThatThrownBy(() -> players.createNote(server, PLAYER.toString(), kind, "text", "Mod", staff()))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(playerRepository);
    }

    @ParameterizedTest
    @EnumSource(PlayerNoteKind.class)
    void theConsoleMayWriteBothKindsUnderItsGivenName(PlayerNoteKind kind) {
        knownPlayer();

        players.createNote(server, PLAYER.toString(), kind, "text", "Console", MinecraftIssuer.of(null, true));

        verify(playerRepository).addNote(eq("player-id"),
            argThat(note -> note.getIssuerId() == null && "Console".equals(note.getIssuerName())));
        verifyNoInteractions(roles, permissions);
    }

    @Test
    void aStaffNoteKeepsTheStaffIdAndDropsTheTypedName() {
        staffHolding(PermissionService.PLAYER_NOTE);
        knownPlayer();

        players.createNote(server, PLAYER.toString(), PlayerNoteKind.NOTE, "text", "Spoofed", staff());

        verify(playerRepository).addNote(eq("player-id"),
            argThat(note -> STAFF.equals(note.getIssuerId()) && note.getIssuerName() == null));
    }

    @Test
    void aStaffMemberWithoutThePardonPermissionPardonsNothingAndLooksNothingUp() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY_DURATION);

        assertThatThrownBy(() -> players.pardonPlayer(server, "Steve", "ban", "Mod", staff(), "appeal"))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(playerService, lifecycle);
    }

    @Test
    void theParentPermissionAllowsAPlayerPardon() {
        staffHolding(PermissionService.PUNISHMENT_MODIFY);
        when(playerService.findBestByUsername(server, "Steve", PlayerParts.PUNISHMENTS))
            .thenReturn(Optional.of(Player.builder().id("player-id").minecraftUuid(PLAYER).punishments(List.of()).build()));

        PardonResult result = players.pardonPlayer(server, "Steve", null, "Mod", staff(), null);

        assertThat(result).isEqualTo(new PardonResult.Pardoned(false, 0, "No punishments found to pardon"));
    }

    @Test
    void anUnknownStaffIdIsRefused() {
        when(roles.minecraftPerformer(server, STAFF)).thenReturn(PerformerAuthority.unidentified());

        assertThatThrownBy(() -> players.pardonPlayer(server, "Steve", null, "Mod", staff(), null))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(playerService, lifecycle);
    }

    private static MinecraftIssuer staff() {
        return MinecraftIssuer.of(STAFF, null);
    }

    private void knownPlayer() {
        when(playerRepository.findByMinecraftUuid(PLAYER.toString()))
            .thenReturn(Optional.of(Player.builder().id("player-id").minecraftUuid(PLAYER).build()));
    }

    private void staffHolding(String... held) {
        List<String> granted = List.of(held);
        when(roles.minecraftPerformer(server, STAFF)).thenReturn(new PerformerAuthority("mod@example.com", ROLE, false, true));
        when(permissions.hasPermission(eq(server), eq(ROLE), anyString())).thenAnswer(call ->
            granted.stream().anyMatch(permission -> RoleAuthorization.permissionImplies(permission, call.getArgument(2))));
    }
}
