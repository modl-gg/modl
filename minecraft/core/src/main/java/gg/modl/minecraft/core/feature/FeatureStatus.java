package gg.modl.minecraft.core.feature;

import gg.modl.proto.modl.v1.ServerFeatureStatus;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Value
public class FeatureStatus {
    static final int MAX_DETECTED = 8;
    static final int MAX_NAME_LENGTH = 32;

    boolean replaysSupported;
    boolean replaysActive;
    boolean anticheatIntegrationActive;
    List<String> detectedAnticheats;
    int replayMemoryBudgetMb;

    public FeatureStatus(boolean replaysSupported, boolean replaysActive, boolean anticheatIntegrationActive,
                         List<String> detectedAnticheats, int replayMemoryBudgetMb) {
        this.replaysSupported = replaysSupported;
        this.replaysActive = replaysActive;
        this.anticheatIntegrationActive = anticheatIntegrationActive;
        this.detectedAnticheats = Collections.unmodifiableList(bounded(detectedAnticheats));
        this.replayMemoryBudgetMb = Math.max(0, replayMemoryBudgetMb);
    }

    ServerFeatureStatus toProto() {
        return ServerFeatureStatus.newBuilder()
                .setReplaysSupported(replaysSupported)
                .setReplaysActive(replaysActive)
                .setAnticheatIntegrationActive(anticheatIntegrationActive)
                .addAllDetectedAnticheats(detectedAnticheats)
                .setReplayMemoryBudgetMb(replayMemoryBudgetMb)
                .build();
    }

    private static List<String> bounded(List<String> names) {
        List<String> kept = new ArrayList<>();
        for (String name : names) {
            if (kept.size() >= MAX_DETECTED) break;
            if (!name.isEmpty() && name.length() <= MAX_NAME_LENGTH) kept.add(name);
        }
        return kept;
    }
}
