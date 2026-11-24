package gg.modl.backend.player.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentStatus;
import gg.modl.backend.settings.service.OffenderThresholdSettingsService;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlayerStatusCalculatorActiveOrUnstartedTest {
    private static final long HOUR = 3_600_000L;

    private final PlayerStatusCalculator calculator =
        new PlayerStatusCalculator(mock(PunishmentTypeService.class), mock(OffenderThresholdSettingsService.class), Clock.systemUTC());

    @Test
    void punishmentsInForceOrWaitingToStartCount() {
        Instant now = Instant.now();
        assertThat(calculator.isPunishmentActiveOrUnstarted(punishment(Instant.ofEpochMilli(now.toEpochMilli() - HOUR), 2 * HOUR, null, List.of())))
            .isTrue();
        assertThat(calculator.isPunishmentActiveOrUnstarted(punishment(null, 2 * HOUR, PunishmentStatus.UNSTARTED, List.of())))
            .isTrue();
    }

    @Test
    void endedOrPardonedPunishmentsDoNotCount() {
        Instant now = Instant.now();
        PunishmentModification pardon = new PunishmentModification("m1", "MANUAL_PARDON", now, "Staff", null, "Pardoned", null, null, null);

        assertThat(calculator.isPunishmentActiveOrUnstarted(punishment(Instant.ofEpochMilli(now.toEpochMilli() - 3 * HOUR), HOUR, null, List.of())))
            .isFalse();
        assertThat(calculator.isPunishmentActiveOrUnstarted(punishment(null, HOUR, PunishmentStatus.UNSTARTED, List.of(pardon))))
            .isFalse();
    }

    @Test
    void aRollbackEndsEnforcementWithoutBeingAPardon() {
        Instant now = Instant.now();
        PunishmentModification rollback = new PunishmentModification("m1", "ROLLBACK", now, "Owner", null, "Mistake", null, null, null);
        Punishment inForce = punishment(Instant.ofEpochMilli(now.toEpochMilli() - HOUR), 2 * HOUR, null, List.of(rollback));

        assertThat(calculator.isPunishmentActiveOrUnstarted(inForce)).isFalse();
        assertThat(calculator.isPunishmentActiveOrUnstarted(punishment(null, HOUR, PunishmentStatus.UNSTARTED, List.of(rollback))))
            .isFalse();
        assertThat(inForce.isPardoned()).isFalse();
        assertThat(inForce.hasRollback()).isTrue();
    }

    private static Punishment punishment(Instant started, long durationMs, String status, List<PunishmentModification> modifications) {
        Map<String, Object> data = new HashMap<>();
        data.put("duration", durationMs);
        if (status != null) {
            data.put("status", status);
        }
        return new Punishment("p1", 2, "Staff", null, PunishmentIssuerKind.STAFF, Instant.now(), started, modifications, List.of(), List.of(), List.of(), data);
    }
}
