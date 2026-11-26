package gg.modl.minecraft.core.feature;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.proto.modl.v1.FeatureSettingsEvent;
import gg.modl.proto.modl.v1.RealtimeEnvelope;
import gg.modl.proto.modl.v1.ReplaySettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureSettingsChannelTest {
    private static final PluginLogger LOGGER = new PluginLogger() {
        @Override public void info(String message) {}
        @Override public void warning(String message) {}
        @Override public void severe(String message) {}
    };

    @Test
    void settingsReceivedBeforeTheFeaturesStartAreAppliedWhenTheyBind() {
        FeatureSettingsChannel channel = new FeatureSettingsChannel(LOGGER);
        RecordingTarget target = new RecordingTarget();

        channel.receive(envelope(ReplaySettings.newBuilder().setEnabled(true).setRecordEveryPlayer(true)
                .setWindowSeconds(90).setRadiusBlocks(48).setMemoryBudgetMb(128).setAnticheatName("Grim").build()));
        channel.bind(target);
        channel.receive(envelope(ReplaySettings.getDefaultInstance()));
        channel.unbind();
        channel.receive(envelope(ReplaySettings.newBuilder().setEnabled(true).build()));

        assertEquals(2, target.applied.size());
        FeatureSettings first = target.applied.get(0);
        assertTrue(first.recordsEveryPlayer());
        assertFalse(first.startsRecordingAtFlags());
        assertEquals(90, first.getWindowSeconds());
        assertEquals("Grim", first.getAnticheatName());
        assertFalse(target.applied.get(1).isReplaysEnabled());
    }

    @Test
    void anOlderVersionArrivingLateIsIgnored() {
        FeatureSettingsChannel channel = new FeatureSettingsChannel(LOGGER);
        RecordingTarget target = new RecordingTarget();
        channel.bind(target);

        channel.receive(versioned(5, ReplaySettings.newBuilder().setEnabled(false).build()));
        channel.receive(versioned(4, ReplaySettings.newBuilder().setEnabled(true).build()));

        assertEquals(1, target.applied.size());
        assertFalse(target.applied.get(0).isReplaysEnabled());
    }

    @Test
    void missingValuesReadAsTheDefaults() {
        FeatureSettings settings = FeatureSettings.fromProto(ReplaySettings.getDefaultInstance());

        assertEquals(FeatureSettings.DEFAULT_WINDOW_SECONDS, settings.getWindowSeconds());
        assertEquals(FeatureSettings.DEFAULT_RADIUS_BLOCKS, settings.getRadiusBlocks());
        assertEquals(FeatureSettings.DEFAULT_MEMORY_BUDGET_MB, settings.getMemoryBudgetMb());
    }

    @Test
    void valuesOutsideTheirRangesAreBoundedOnTheServer() {
        FeatureSettings settings = FeatureSettings.fromProto(ReplaySettings.newBuilder().setEnabled(true)
                .setAnticheatIntegration(true).setRecordOnAnticheatFlag(true)
                .setWindowSeconds(5).setRadiusBlocks(-1).setMemoryBudgetMb(4096).setAnticheatName("  ").build());

        assertEquals(FeatureSettings.MIN_WINDOW_SECONDS, settings.getWindowSeconds());
        assertEquals(FeatureSettings.DEFAULT_RADIUS_BLOCKS, settings.getRadiusBlocks());
        assertEquals(FeatureSettings.MAX_MEMORY_BUDGET_MB, settings.getMemoryBudgetMb());
        assertEquals(FeatureSettings.NONE.getAnticheatName(), settings.getAnticheatName());
        assertTrue(settings.startsRecordingAtFlags());
        assertEquals(1024L * 1024L * 1024L, settings.memoryBudgetBytes(Long.MAX_VALUE));
    }

    @Test
    void negativeValuesReadAsTheDefaults() {
        FeatureSettings settings = FeatureSettings.fromProto(ReplaySettings.newBuilder().setWindowSeconds(-30)
                .setRadiusBlocks(-1).setMemoryBudgetMb(-512).build());

        assertEquals(FeatureSettings.DEFAULT_WINDOW_SECONDS, settings.getWindowSeconds());
        assertEquals(FeatureSettings.DEFAULT_RADIUS_BLOCKS, settings.getRadiusBlocks());
        assertEquals(FeatureSettings.DEFAULT_MEMORY_BUDGET_MB, settings.getMemoryBudgetMb());
    }

    @Test
    void theMemoryBudgetNeverExceedsAQuarterOfTheHeap() {
        FeatureSettings settings = FeatureSettings.fromProto(ReplaySettings.newBuilder().setMemoryBudgetMb(512).build());
        long heap = 1024L * 1024L * 1024L;

        assertEquals(256L * 1024L * 1024L, settings.memoryBudgetBytes(heap));
        assertEquals(512L * 1024L * 1024L, settings.memoryBudgetBytes(8 * heap));
        assertTrue(settings.memoryBudgetBytes() <= Runtime.getRuntime().maxMemory() / 4);
        assertEquals(settings.memoryBudgetBytes() / (1024L * 1024L), settings.effectiveMemoryBudgetMb());
    }

    @Test
    void settingsAreHandedToTheFeaturesInsideTheChannelLockSoTheyArriveInVersionOrder() {
        FeatureSettingsChannel channel = new FeatureSettingsChannel(LOGGER);
        List<Boolean> lockHeld = new ArrayList<>();
        channel.bind(new RecordingTarget() {
            @Override
            public void applyFeatureSettings(FeatureSettings settings) {
                lockHeld.add(Thread.holdsLock(channel));
            }
        });

        channel.receive(versioned(1, ReplaySettings.newBuilder().setEnabled(true).build()));
        channel.receive(versioned(2, ReplaySettings.newBuilder().setEnabled(false).build()));

        assertEquals(Arrays.asList(true, true), lockHeld);
    }

    @Test
    void theStatusCarriesTheEffectiveMemoryBudget() {
        FeatureStatus status = new FeatureStatus(true, true, false, Collections.<String>emptyList(), 48);

        assertEquals(48, status.toProto().getReplayMemoryBudgetMb());
        assertEquals(0, new FeatureStatus(true, true, false, Collections.<String>emptyList(), -1).getReplayMemoryBudgetMb());
    }

    @Test
    void theStatusKeepsOnlyBoundedAnticheatNames() {
        List<String> names = new ArrayList<>(Collections.nCopies(10, "GrimAC"));
        names.add(0, "x".repeat(40));

        FeatureStatus status = new FeatureStatus(true, false, true, names, 64);

        assertEquals(FeatureStatus.MAX_DETECTED, status.getDetectedAnticheats().size());
        assertEquals("GrimAC", status.toProto().getDetectedAnticheats(0));
    }

    private static RealtimeEnvelope versioned(long version, ReplaySettings replays) {
        return RealtimeEnvelope.newBuilder().setFeatureSettings(FeatureSettingsEvent.newBuilder().setVersion(version).setReplays(replays))
                .build();
    }

    private static RealtimeEnvelope envelope(ReplaySettings replays) {
        return RealtimeEnvelope.newBuilder().setFeatureSettings(FeatureSettingsEvent.newBuilder().setReplays(replays)).build();
    }

    private static class RecordingTarget implements FeatureSettingsTarget {
        private final List<FeatureSettings> applied = new ArrayList<>();

        @Override
        public void applyFeatureSettings(FeatureSettings settings) {
            applied.add(settings);
        }

        @Override
        public FeatureStatus featureStatus() {
            return new FeatureStatus(true, false, false, Collections.<String>emptyList(), 64);
        }

        @Override
        public void reportStatusTo(Runnable statusChanged) {
        }
    }
}
