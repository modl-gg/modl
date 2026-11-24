package gg.modl.backend.instance.setup;

import gg.modl.backend.server.data.SetupStep;
import java.util.Optional;

public interface SetupBlocker {
    Optional<String> blockingCompletion();

    Optional<String> blockingStep(SetupStep step);
}
