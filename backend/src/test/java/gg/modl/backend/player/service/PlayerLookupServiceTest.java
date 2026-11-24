package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.support.TestServers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.PlayerService;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.dto.response.LinkedAccountsResult;
import gg.modl.backend.player.dto.response.PlayerLookupResult;
import gg.modl.backend.player.dto.response.PunishmentView;
import gg.modl.backend.player.repository.PlayerParts;
import gg.modl.backend.player.repository.AddressSharers;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerLookupServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private PlayerLookupService service;
    private PlayerRepository playerRepository;
    private PlayerService playerService;
    private PunishmentTypeService punishmentTypeService;
    private PlayerStatusCalculator statusCalculator;
    private Server server;

    @BeforeEach
    void setUp() {
        playerRepository = mock(PlayerRepository.class);
        playerService = mock(PlayerService.class);
        punishmentTypeService = mock(PunishmentTypeService.class);
        statusCalculator = new PlayerStatusCalculator(
            punishmentTypeService, mock(OffenderThresholdSettingsService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new PlayerLookupService(
            playerRepository,
            punishmentTypeService,
            mock(MojangApiService.class),
            statusCalculator,
            mock(IssuerNameResolver.class),
            playerService,
            mock(PublicUrls.class),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        server = TestServers.server("Demo", "admin@example.com");
    }

    @Test
    void getPlayerByUuidLowercasesUuidBeforeQueryingPlayerRepository() {
        when(playerRepository.findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PlayerParts.values()))
            .thenReturn(Optional.empty());

        service.getPlayerByUuid(server, "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", null, null);

        verify(playerRepository).findByMinecraftUuid("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", PlayerParts.values());
    }

    @Test
    void toPlayerProfileAppliesLimitsToEmbeddedCollectionsAndKeepsTotals() {
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .usernames(List.of(new UsernameEntry("Byteful", Instant.ofEpochMilli(1_000L))))
            .notes(List.of(
                note("note-1", 1_000L),
                note("note-2", 2_000L),
                note("note-3", 3_000L),
                note("note-4", 4_000L)
            ))
            .punishments(List.of(
                punishment("punishment-1", 1_000L),
                punishment("punishment-2", 2_000L),
                punishment("punishment-3", 3_000L),
                punishment("punishment-4", 4_000L),
                punishment("punishment-5", 5_000L)
            ))
            .data(new HashMap<>())
            .build();

        Map<String, Object> profile = service.toPlayerProfile(server, player, PunishmentTypeLookup.ofLiveTypes(Map.of()), 3, 2);

        List<?> punishments = (List<?>) profile.get("punishments");
        List<?> notes = (List<?>) profile.get("notes");
        assertEquals(3, punishments.size());
        assertEquals(2, notes.size());
        assertEquals(5, profile.get("punishmentCount"));
        assertEquals(4, profile.get("noteCount"));
        assertEquals("punishment-5", ((PunishmentView) punishments.get(0)).id());
        assertEquals("punishment-4", ((PunishmentView) punishments.get(1)).id());
        assertEquals("punishment-3", ((PunishmentView) punishments.get(2)).id());
        assertEquals("note-4", ((Map<?, ?>) notes.get(0)).get("id"));
        assertEquals("note-3", ((Map<?, ?>) notes.get(1)).get("id"));
        assertEquals(PlayerNoteKind.WARNING, ((Map<?, ?>) notes.get(0)).get("kind"));
    }

    @Test
    void lookupListsEveryNameButTheCurrentOneAndNamesDeletedTypes() {
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .usernames(List.of(
                new UsernameEntry("First", Instant.ofEpochMilli(1_000L)),
                new UsernameEntry("Second", Instant.ofEpochMilli(2_000L)),
                new UsernameEntry("Current", Instant.ofEpochMilli(3_000L))))
            .punishments(List.of(punishment("punishment-1", 4_000L)))
            .build();
        when(playerService.findBestByUsername(any(), any(), any(PlayerParts[].class))).thenReturn(Optional.of(player));
        when(punishmentTypeService.getPunishmentTypeName(server, 1)).thenReturn("Griefing (deleted)");

        Map<String, Object> data = ((PlayerLookupResult.Found) service.lookupPlayer(server, "Current", false)).data();

        assertEquals(List.of("First", "Second"), data.get("previousUsernames"));
        assertEquals("Griefing (deleted)", ((Map<?, ?>) ((List<?>) data.get("recentPunishments")).getFirst()).get("type"));
    }

    @Test
    void linkedAccountsLoadFullProfilesOnlyForTheRequestedPage() {
        UUID uuid = UUID.randomUUID();
        Player player = Player.builder().id("self").minecraftUuid(uuid).build();
        Player first = Player.builder().id("first").minecraftUuid(UUID.randomUUID()).build();
        Player second = Player.builder().id("second").minecraftUuid(UUID.randomUUID()).build();
        when(playerRepository.findByMinecraftUuid(uuid.toString(), PlayerParts.IPS, PlayerParts.LINKS))
            .thenReturn(Optional.of(player));
        when(playerRepository.findRecentByIps(List.of(), "self", 20)).thenReturn(new AddressSharers(List.of(first, second), true));
        when(playerRepository.findByIds(List.of("second"), PlayerParts.values())).thenReturn(List.of(second));

        LinkedAccountsResult.Found found =
            (LinkedAccountsResult.Found) service.getLinkedAccounts(server, uuid.toString(), 2, 1);

        assertEquals(2, found.totalCount().intValue());
        assertTrue(found.truncated());
        assertEquals("second", found.linkedAccounts().getFirst().get("id"));
        verify(playerRepository).findByIds(List.of("second"), PlayerParts.values());
    }

    @Test
    void profileCarriesTheEnforcementStateTheModificationsAndDeletedTypeNames() {
        Punishment enforced = punishment("enforced", 3_000L);
        Punishment pardoned = endedBy(punishment("pardoned", 2_000L), PunishmentModificationType.MANUAL_PARDON);
        Punishment rolledBack = endedBy(punishment("rolled-back", 1_000L), PunishmentModificationType.ROLLBACK);
        rolledBack.data().asMap().put(PlayerLookupService.ACTIVE_DATA_KEY, true);
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .punishments(List.of(enforced, pardoned, rolledBack))
            .build();
        when(punishmentTypeService.getPunishmentTypeName(server, 1)).thenReturn("Griefing (deleted)");

        Map<String, Object> profile =
            service.toPlayerProfile(server, player, PunishmentTypeLookup.of(server, punishmentTypeService), 3, null);

        List<PunishmentView> views = ((List<?>) profile.get("punishments")).stream().map(PunishmentView.class::cast).toList();
        assertEquals(List.of("enforced", "pardoned", "rolled-back"), views.stream().map(PunishmentView::id).toList());
        assertEquals(List.of(true, false, false),
            views.stream().map(view -> view.data().get(PlayerLookupService.ACTIVE_DATA_KEY)).toList());
        assertEquals("ROLLBACK", views.get(2).modifications().getFirst().get("type"));
        assertEquals("Griefing (deleted)", views.getFirst().type());
    }

    @Test
    void profileCarriesTheDisplayExpiryAndAppealabilityFromOneTypeSnapshot() {
        Punishment timed = punishment("timed", 2_000L);
        timed.setStarted(NOW);
        timed.data().setDuration(60_000L);
        Punishment permanent = punishment("permanent", 1_000L);
        permanent.setTypeOrdinal(2);
        permanent.data().asMap().put(PlayerLookupService.EXPIRES_DATA_KEY, 1L);
        Player player = Player.builder()
            .id("player-id")
            .minecraftUuid(UUID.randomUUID())
            .punishments(List.of(timed, permanent))
            .build();
        when(punishmentTypeService.getPunishmentTypes(server))
            .thenReturn(List.of(PunishmentType.builder().ordinal(1).name("Mute").appealable(true).build()));

        Map<String, Object> profile =
            service.toPlayerProfile(server, player, PunishmentTypeLookup.of(server, punishmentTypeService));

        List<PunishmentView> views = ((List<?>) profile.get("punishments")).stream().map(PunishmentView.class::cast).toList();
        assertEquals(NOW.plusMillis(60_000L).toEpochMilli(), views.getFirst().data().get(PlayerLookupService.EXPIRES_DATA_KEY));
        assertEquals(true, views.getFirst().data().get(PlayerLookupService.APPEALABLE_DATA_KEY));
        assertFalse(views.get(1).data().containsKey(PlayerLookupService.EXPIRES_DATA_KEY));
        assertEquals(false, views.get(1).data().get(PlayerLookupService.APPEALABLE_DATA_KEY));
        verify(punishmentTypeService).getPunishmentTypes(server);
        verify(punishmentTypeService, never()).isAppealable(server, 1);
    }

    private static Punishment endedBy(Punishment punishment, PunishmentModificationType type) {
        punishment.getModifications().add(new PunishmentModification(
            "modification", type.name(), NOW, "Mod", null, "", null, null, null));
        return punishment;
    }

    private NoteEntry note(String id, long dateMillis) {
        return NoteEntry.builder()
            .id(id)
            .text(id + " text")
            .date(Instant.ofEpochMilli(dateMillis))
            .issuerName("Mod")
            .kind(PlayerNoteKind.WARNING)
            .build();
    }

    private Punishment punishment(String id, long issuedMillis) {
        return new Punishment(
            id,
            1,
            "Mod",
            null,
            PunishmentIssuerKind.STAFF,
            Instant.ofEpochMilli(issuedMillis),
            null,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            new HashMap<>()
        );
    }
}
