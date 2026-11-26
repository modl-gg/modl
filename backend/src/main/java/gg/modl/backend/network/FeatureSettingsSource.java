package gg.modl.backend.network;

import gg.modl.proto.modl.v1.FeatureSettingsEvent;

public interface FeatureSettingsSource {
    FeatureSettingsEvent current();
}
