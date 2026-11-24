package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.realtime.publish.RealtimeEventPublisher;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.SyncStaffNotification;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PunishmentStaffNoticePushTest {
    private static final int MANUAL_BAN = 2;
    private static final Instant ISSUED = Instant.parse("2026-11-20T10:00:00Z");

    private final Server server = TestServers.server("server", "admin@example.com");
    private final RealtimeEventPublisher events = mock(RealtimeEventPublisher.class);
    private final PunishmentTypeService types = mock(PunishmentTypeService.class);
    private final IssuerNameResolver issuers = mock(IssuerNameResolver.class);
    private PunishmentRealtimePublisher publisher;

    @BeforeEach
    void setUp() {
        when(types.getPunishmentTypes(server)).thenReturn(List.of(
            PunishmentType.builder().ordinal(MANUAL_BAN).name("Manual Ban").category("Administrative").build()));
        when(types.getPunishmentTypeName(eq(server), anyInt())).thenReturn("Manual Ban");
        when(issuers.resolveForPunishments(any(), any())).thenReturn(Map.of());
        Clock clock = Clock.systemUTC();
        PlayerStatusCalculator calculator = new PlayerStatusCalculator(types, mock(OffenderThresholdSettingsService.class), clock);
        publisher = new PunishmentRealtimePublisher(events, new SyncProtoFactory(), types, calculator, issuers,
            new StaffNotices(clock, mock(PublicUrls.class), calculator));
    }

    @Test
    void anIssuedBanTellsStaffWhoBannedWhom() {
        publisher.punishmentIssued(server, record(ban()));

        SyncStaffNotification notice = pushedNotice();
        assertThat(notice.getId()).isEqualTo("punishment_PUNISH01");
        assertThat(notice.getType()).isEqualTo(StaffNoticeType.PUNISHMENT_ISSUED.name());
        assertThat(notice.getMessage()).isEqualTo("Mod: banned Steve (Manual Ban)");
        assertThat(notice.getTimestamp()).isEqualTo(ISSUED.toEpochMilli());
    }

    @Test
    void aQueuedBanIsAnnouncedWithoutBeingPushedForEnforcement() {
        publisher.punishmentQueued(server, record(ban()));

        assertThat(pushedNotice().getId()).isEqualTo("punishment_PUNISH01");
        verify(events, never()).pushPunishments(any(), any(), any());
    }

    @Test
    void aPardonTellsStaffWhoPardonedWhat() {
        Punishment pardoned = ban();
        pardoned.getModifications().add(modification(PunishmentModificationType.MANUAL_PARDON));

        publisher.punishmentPardoned(server, record(pardoned));

        SyncStaffNotification notice = pushedNotice();
        assertThat(notice.getId()).isEqualTo("pardon_PUNISH01");
        assertThat(notice.getType()).isEqualTo(StaffNoticeType.PUNISHMENT_PARDONED.name());
        assertThat(notice.getMessage()).isEqualTo("Admin: pardoned Steve's Manual Ban");
    }

    @Test
    void aChangeAfterThePardonDoesNotAnnounceItAgain() {
        Punishment changed = ban();
        changed.getModifications().add(modification(PunishmentModificationType.MANUAL_PARDON));
        changed.getModifications().add(modification(PunishmentModificationType.SET_WIPING_TRUE));

        publisher.punishmentsModified(server, List.of(record(changed)));

        verify(events).pushPunishments(eq(server), eq(List.of()), any());
        verify(events, never()).pushStaffNotifications(any(), any());
    }

    @Test
    void aModificationOfAnAlreadyPardonedPunishmentIsNotAPardon() {
        Punishment pardonedBefore = ban();
        pardonedBefore.getModifications().add(modification(PunishmentModificationType.MANUAL_PARDON));

        publisher.punishmentModified(server, record(pardonedBefore));

        verify(events, never()).pushStaffNotifications(any(), any());
    }

    private SyncStaffNotification pushedNotice() {
        ArgumentCaptor<List<SyncStaffNotification>> pushed = ArgumentCaptor.captor();
        verify(events).pushStaffNotifications(eq(server), pushed.capture());
        assertThat(pushed.getValue()).hasSize(1);
        return pushed.getValue().getFirst();
    }

    private static Punishment ban() {
        return new Punishment("PUNISH01", MANUAL_BAN, "Mod", null, PunishmentIssuerKind.STAFF, ISSUED, ISSUED,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
            new HashMap<>(Map.of("enforcementCategory", "BAN")));
    }

    private static PunishmentModification modification(PunishmentModificationType type) {
        return PunishmentEntries.modification(type, ISSUED.plusSeconds(60), "Admin", null, "appeal accepted", null);
    }

    private static PunishmentRecord record(Punishment punishment) {
        return new PunishmentRecord("player", "11111111-2222-3333-4444-555555555555", "Steve", punishment, 1);
    }
}
