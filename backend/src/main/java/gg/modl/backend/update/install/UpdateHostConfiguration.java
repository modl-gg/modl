package gg.modl.backend.update.install;

import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.guard.UpdatePhaseReporter;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import gg.modl.backend.update.launcher.UpdateLauncher;
import gg.modl.backend.update.launcher.UpdateRestart;
import gg.modl.update.ReleaseKeys;
import gg.modl.update.UpdateFiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UpdateHostConfiguration {
    @Bean
    UpdateHosts updateHosts(DeploymentDetector deployment, ObjectProvider<EmbeddedUpdateInputs> embedded,
                            ConfigurableApplicationContext context, UpdateFiles files, ReleaseBuild build, ReleaseKeys keys) {
        if (deployment.deployment() == Deployment.EMBEDDED) {
            EmbeddedUpdateInputs inputs = embedded.getIfAvailable();
            if (inputs == null) {
                return UpdateHosts.none();
            }
            EmbeddedHostBridge bridge = new EmbeddedHostBridge(inputs, files, build);
            return new UpdateHosts(bridge, bridge);
        }
        if (deployment.deployment() == Deployment.JAR) {
            return new UpdateHosts(new JarUpdateHost(context, files, build, keys), UpdatePhaseReporter.NONE);
        }
        return UpdateHosts.none();
    }

    @Bean
    InstanceRestart instanceRestart(DeploymentDetector deployment, ObjectProvider<EmbeddedPanelRestart> embedded,
                                    ConfigurableApplicationContext context, ReleaseBuild build) {
        return switch (deployment.deployment()) {
            case EMBEDDED -> {
                EmbeddedPanelRestart panel = embedded.getIfAvailable();
                yield panel != null ? new AutomaticRestart(Mode.IN_PLACE, panel.request()) : InstanceRestart.MANUAL;
            }
            case JAR -> UpdateLauncher.restartsChildren(build.version())
                ? new AutomaticRestart(Mode.IN_PLACE, () -> UpdateRestart.restartJar(context))
                : InstanceRestart.MANUAL;
            case DOCKER -> new AutomaticRestart(Mode.EXIT, () -> UpdateRestart.exitToBeRestarted(context));
            case UNMANAGED -> InstanceRestart.MANUAL;
        };
    }
}
