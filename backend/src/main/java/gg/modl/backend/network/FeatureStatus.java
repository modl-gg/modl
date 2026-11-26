package gg.modl.backend.network;

import gg.modl.proto.modl.v1.ServerFeatureStatus;
import java.util.List;

public record FeatureStatus(boolean replaysSupported, boolean replaysActive, boolean anticheatIntegrationActive,
                            List<String> detectedAnticheats, int replayMemoryBudgetMb) {
    public FeatureStatus {
        detectedAnticheats = List.copyOf(detectedAnticheats);
    }

    static FeatureStatus of(ServerFeatureStatus reported) {
        return new FeatureStatus(reported.getReplaysSupported(), reported.getReplaysActive(),
            reported.getAnticheatIntegrationActive(), reported.getDetectedAnticheatsList(), reported.getReplayMemoryBudgetMb());
    }

    public ServerFeatureStatus toProto() {
        return ServerFeatureStatus.newBuilder()
            .setReplaysSupported(replaysSupported)
            .setReplaysActive(replaysActive)
            .setAnticheatIntegrationActive(anticheatIntegrationActive)
            .addAllDetectedAnticheats(detectedAnticheats)
            .setReplayMemoryBudgetMb(replayMemoryBudgetMb)
            .build();
    }
}
