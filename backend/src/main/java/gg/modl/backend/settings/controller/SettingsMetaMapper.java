package gg.modl.backend.settings.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.toTimestamp;

import gg.modl.backend.settings.service.VersionedSettings;
import gg.modl.proto.modl.v1.SettingsMeta;

public final class SettingsMetaMapper {
    private SettingsMetaMapper() {
    }

    public static SettingsMeta toSettingsMeta(VersionedSettings<?> settings) {
        SettingsMeta.Builder builder = SettingsMeta.newBuilder()
            .setVersion(settings.version());
        if (settings.updatedAt() != null) {
            builder.setUpdatedAt(toTimestamp(settings.updatedAt()));
        }
        return builder.build();
    }
}
