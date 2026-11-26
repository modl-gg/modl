package gg.modl.backend.replay.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.network.FeatureSettingsChanged;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ReplaySettingsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final String ACTOR = "owner@example.com";

    private final Server server = TestServers.server("server", ACTOR);
    private final ReplaySettingsStore store = mock(ReplaySettingsStore.class);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ReplaySettingsService service = new ReplaySettingsService(store, auditor, events, new MutableClock(NOW));

    @Test
    void aSaveRecordsTheChoiceAuditsTheChangeAndReachesTheServers() {
        when(store.versioned()).thenReturn(new VersionedSettings<>(ReplaySettings.DEFAULTS, 0, null));
        when(store.updateExpecting(eq(0L), any())).thenAnswer(call -> {
            UnaryOperator<ReplaySettings> change = call.getArgument(1);
            return new VersionedSettings<>(change.apply(ReplaySettings.DEFAULTS), 1, NOW);
        });
        ReplaySettings requested = new ReplaySettings(true, true, true, true, 90, 48, 128, "Grim", null);

        VersionedSettings<ReplaySettings> saved = service.update(server, ACTOR, 0, requested);

        assertThat(saved.data().choiceMadeAt()).isEqualTo(NOW.toEpochMilli());
        assertThat(saved.data().enabled()).isTrue();
        verify(auditor).recordStaffAction(server, ACTOR, "Changed replay settings: replays, anticheat integration, history window,"
            + " radius, memory budget, anticheat name");
        ArgumentCaptor<FeatureSettingsChanged> published = ArgumentCaptor.forClass(FeatureSettingsChanged.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().settings().getVersion()).isEqualTo(1);
        assertThat(published.getValue().settings().getReplays().getWindowSeconds()).isEqualTo(90);
        assertThat(published.getValue().settings().getReplays().getAnticheatIntegration()).isTrue();
    }

    @Test
    void valuesOutsideTheirRangesAreRefusedBeforeAnythingIsWritten() {
        when(store.versioned()).thenReturn(new VersionedSettings<>(ReplaySettings.DEFAULTS, 0, null));

        assertRefused(new ReplaySettings(true, true, true, true, 5, 64, 64, "Anti-cheat", null));
        assertRefused(new ReplaySettings(true, true, true, true, 60, 200, 64, "Anti-cheat", null));
        assertRefused(new ReplaySettings(true, true, true, true, 60, 64, 8, "Anti-cheat", null));
        assertRefused(new ReplaySettings(true, true, true, true, 60, 64, 64, " ", null));
        assertRefused(new ReplaySettings(true, true, true, true, 60, 64, 64, "x".repeat(33), null));
        verify(store, never()).updateExpecting(anyLong(), any());
        verify(auditor, never()).recordStaffAction(any(), anyString(), anyString());
    }

    @Test
    void storedValuesOutsideTheirRangesReadAsDefaults() {
        ReplaySettings stored = new ReplaySettings(true, false, false, false, 0, 4096, 1, "", 5L).normalized();

        assertThat(stored.windowSeconds()).isEqualTo(ReplaySettings.DEFAULT_WINDOW_SECONDS);
        assertThat(stored.radiusBlocks()).isEqualTo(ReplaySettings.MAX_RADIUS_BLOCKS);
        assertThat(stored.memoryBudgetMb()).isEqualTo(ReplaySettings.MIN_MEMORY_BUDGET_MB);
        assertThat(stored.anticheatName()).isEqualTo(ReplaySettings.DEFAULT_ANTICHEAT_NAME);
        assertThat(stored.choiceMadeAt()).isEqualTo(5L);
    }

    @Test
    void theSetupStepWaitsForAChoice() {
        ReplaySetupPrerequisite prerequisite = new ReplaySetupPrerequisite(store);
        when(store.versioned()).thenReturn(new VersionedSettings<>(ReplaySettings.DEFAULTS, 0, null));

        assertThatThrownBy(prerequisite::require).isInstanceOf(ConflictException.class)
            .hasFieldOrPropertyWithValue("machineCode", ReplaySetupPrerequisite.NOT_CHOSEN);

        when(store.versioned()).thenReturn(new VersionedSettings<>(ReplaySettings.DEFAULTS.chosenAt(1L), 1, NOW));
        prerequisite.require();
    }

    private void assertRefused(ReplaySettings requested) {
        assertThatThrownBy(() -> service.update(server, ACTOR, 0, requested)).isInstanceOf(ValidationException.class);
    }
}
