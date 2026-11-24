package gg.modl.backend.status.facts;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.setup.SetupPhase;
import gg.modl.backend.status.StatusContributor;
import gg.modl.backend.status.StatusFact;
import gg.modl.backend.status.StatusItem;
import gg.modl.backend.status.StatusSection;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class InstanceStatusFacts implements StatusContributor {
    private final DeploymentDetector deploymentDetector;
    private final InstanceContext instanceContext;

    @Override
    public StatusSection section() {
        return StatusSection.INSTANCE;
    }

    @Override
    public List<StatusFact> facts() {
        SetupPhase phase = SetupPhase.of(instanceContext.instance());
        return List.of(
            StatusItem.VERSION.ok(ReleaseBuild.current().versionText()),
            StatusItem.DEPLOYMENT.ok(deploymentDetector.deployment().name().toLowerCase(Locale.ROOT)),
            StatusItem.JAVA.ok(Runtime.version() + " (" + System.getProperty("java.vendor", "unknown vendor") + ")"),
            StatusItem.OPERATING_SYSTEM.ok(System.getProperty("os.name") + " " + System.getProperty("os.arch")),
            StatusItem.UPTIME.ok(StatusFormats.duration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()))),
            phase == SetupPhase.READY ? StatusItem.SETUP.ok("complete") : StatusItem.SETUP.warning(phase.name().toLowerCase(Locale.ROOT))
        );
    }
}
