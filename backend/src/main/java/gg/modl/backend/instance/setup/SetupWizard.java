package gg.modl.backend.instance.setup;

import gg.modl.backend.server.data.SetupStep;
import gg.modl.backend.server.data.SetupStepStatus;
import java.util.Map;

public record SetupWizard(boolean completed, Map<SetupStep, SetupStepStatus> steps, Environment environment) {

    public record Environment(SetupHost host, boolean reverseProxySuspected) {
    }
}
