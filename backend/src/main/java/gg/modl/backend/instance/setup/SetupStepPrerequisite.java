package gg.modl.backend.instance.setup;

import gg.modl.backend.server.data.SetupStep;

public interface SetupStepPrerequisite {
    SetupStep step();

    void require();
}
