package gg.modl.backend.replay.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.replay.settings.ReplaySettings;
import gg.modl.backend.replay.settings.ReplaySettingsProtos;
import gg.modl.backend.settings.controller.SettingsMetaMapper;
import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.proto.modl.v1.ReplaySettingsEnvelope;

final class ReplaySettingsProtoMapper {
    private ReplaySettingsProtoMapper() {
    }

    static ReplaySettingsEnvelope toEnvelope(VersionedSettings<ReplaySettings> settings) {
        ReplaySettingsEnvelope.Builder envelope = ReplaySettingsEnvelope.newBuilder()
            .setData(ReplaySettingsProtos.toProto(settings.data()))
            .setMeta(SettingsMetaMapper.toSettingsMeta(settings));
        Long chosenAt = settings.data().choiceMadeAt();
        if (chosenAt != null) {
            envelope.setChoiceMadeAt(toTimestamp(chosenAt));
        }
        return envelope.build();
    }

    static ReplaySettings fromProto(gg.modl.proto.modl.v1.ReplaySettings settings) {
        return new ReplaySettings(settings.getEnabled(), settings.getRecordEveryPlayer(), settings.getRecordOnAnticheatFlag(),
            settings.getAnticheatIntegration(), saturated(settings.getWindowSeconds()), saturated(settings.getRadiusBlocks()),
            saturated(settings.getMemoryBudgetMb()), settings.getAnticheatName(), null);
    }

    private static int saturated(int unsigned) {
        return unsigned < 0 ? Integer.MAX_VALUE : unsigned;
    }
}
