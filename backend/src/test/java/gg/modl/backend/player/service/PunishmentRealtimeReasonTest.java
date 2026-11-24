package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.SyncPendingPunishment;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class PunishmentRealtimeReasonTest {
    private final Server server = TestServers.server("server", "admin@example.com");
    private final RealtimeEventPublisher events = mock(RealtimeEventPublisher.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final IssuerNameResolver issuers = mock(IssuerNameResolver.class);

    @ParameterizedTest
    @CsvSource({"2, reported by Alex", "10, Please change your username"})
    void aPushCarriesOnlyTheReasonMeantForThePlayer(int ordinal, String shown) {
        List<PunishmentType> configured = List.of(PunishmentType.builder()
            .ordinal(ordinal).name("Type").category("Social").playerDescription("Please change your username").build());
        when(types.getPunishmentTypes(server)).thenReturn(configured);
        when(issuers.resolveForPunishments(any(), any())).thenReturn(Map.of());
        PlayerStatusCalculator calculator = new PlayerStatusCalculator(types,
            mock(OffenderThresholdSettingsService.class), Clock.systemUTC());
        Punishment punishment = new Punishment("PUNISH01", ordinal, "Mod", null, PunishmentIssuerKind.STAFF, Instant.now(), Instant.now(),
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
            new HashMap<>(Map.of("reason", "reported by Alex")));

        new PunishmentRealtimePublisher(events, new SyncProtoFactory(), types, calculator, issuers,
            new StaffNotices(Clock.systemUTC(), mock(PublicUrls.class), calculator)).punishmentIssued(server,
            new PunishmentRecord("player", "11111111-2222-3333-4444-555555555555", "Steve", punishment, 1));

        ArgumentCaptor<List<SyncPendingPunishment>> pushed = ArgumentCaptor.captor();
        verify(events).pushPunishments(any(), pushed.capture(), any());
        assertThat(pushed.getValue().getFirst().getPunishment().getDescription()).isEqualTo(shown);
    }
}
