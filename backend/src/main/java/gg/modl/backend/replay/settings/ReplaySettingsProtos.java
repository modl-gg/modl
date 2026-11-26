package gg.modl.backend.replay.settings;

import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.proto.modl.v1.FeatureSettingsEvent;

public final class ReplaySettingsProtos {
    private ReplaySettingsProtos() {
    }

    public static gg.modl.proto.modl.v1.ReplaySettings toProto(ReplaySettings settings) {
        return gg.modl.proto.modl.v1.ReplaySettings.newBuilder()
            .setEnabled(settings.enabled())
            .setRecordEveryPlayer(settings.recordEveryPlayer())
            .setRecordOnAnticheatFlag(settings.recordOnAnticheatFlag())
            .setAnticheatIntegration(settings.anticheatIntegration())
            .setWindowSeconds(settings.windowSeconds())
            .setRadiusBlocks(settings.radiusBlocks())
            .setMemoryBudgetMb(settings.memoryBudgetMb())
            .setAnticheatName(settings.anticheatName())
            .build();
    }

    public static FeatureSettingsEvent toFeatureSettings(VersionedSettings<ReplaySettings> settings) {
        return FeatureSettingsEvent.newBuilder()
            .setReplays(toProto(settings.data()))
            .setVersion(settings.version())
            .build();
    }
}
