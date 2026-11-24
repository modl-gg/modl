package gg.modl.backend.player.service;

import static gg.modl.backend.player.service.LifecycleHarness.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationResult;
import gg.modl.backend.player.service.PunishmentQueryService.PunishmentOperationStatus;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatWipeAcknowledgementTest {
    private static final int MANUAL_BAN = 2;
    private static final long DAY = Duration.ofDays(1).toMillis();

    private final LifecycleHarness harness = new LifecycleHarness();
    private final Server server = TestServers.server("Server", "owner@example.com");

    @Test
    void aStatWipeOfAPunishmentStillInForceCannotBeMarkedDone() {
        harness.store(punishment("ACTIVE01", NOW.minus(Duration.ofHours(1)), true));

        PunishmentOperationResult result = harness.mutations.acknowledgeStatWipe(server, "ACTIVE01");

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.NO_OP);
        assertThat(result.message()).isEqualTo(PunishmentMutationService.STAT_WIPE_NOT_DUE);
        verify(harness.punishments, never()).patch(anyString(), anyInt(), any());
    }

    @Test
    void aDueStatWipeIsMarkedDoneOnce() {
        harness.store(punishment("EXPIRED1", NOW.minus(Duration.ofDays(2)), true));

        PunishmentOperationResult result = harness.mutations.acknowledgeStatWipe(server, "EXPIRED1");

        assertThat(result.status()).isEqualTo(PunishmentOperationStatus.SUCCESS);
        verify(harness.punishments).patch(eq("EXPIRED1"), eq(4), any());
    }

    @Test
    void aPunishmentWithoutAStatWipeIsLeftAlone() {
        harness.store(punishment("NOWIPE01", NOW.minus(Duration.ofDays(2)), false));

        assertThat(harness.mutations.acknowledgeStatWipe(server, "NOWIPE01").status()).isEqualTo(PunishmentOperationStatus.NO_OP);
        verify(harness.punishments, never()).patch(anyString(), anyInt(), any());
    }

    private static Punishment punishment(String id, Instant started, boolean wipeAfterExpiry) {
        Map<String, Object> data = new HashMap<>(Map.of(
            "enforcementCategory", "BAN",
            "duration", DAY,
            "wipeAfterExpiry", wipeAfterExpiry));
        return new Punishment(id, MANUAL_BAN, "Mod", null, PunishmentIssuerKind.STAFF, started, started,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), data);
    }
}
