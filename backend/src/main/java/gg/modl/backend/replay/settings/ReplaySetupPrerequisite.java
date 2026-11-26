package gg.modl.backend.replay.settings;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.instance.setup.SetupStepPrerequisite;
import gg.modl.backend.server.data.SetupStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReplaySetupPrerequisite implements SetupStepPrerequisite {
    static final String NOT_CHOSEN = "SETUP_REPLAYS_NOT_CHOSEN";

    private final ReplaySettingsStore store;

    @Override
    public SetupStep step() {
        return SetupStep.REPLAYS;
    }

    @Override
    public void require() {
        if (!store.versioned().data().choiceMade()) {
            throw new ConflictException("Choose whether to record replays and use the anticheat integration first", NOT_CHOSEN);
        }
    }
}
