package gg.modl.backend.update.install;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.update.Deployment;
import gg.modl.backend.update.DeploymentDetector;
import gg.modl.backend.update.ReleaseBuild;
import gg.modl.backend.update.install.InstanceRestart.Mode;
import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.ReleaseVersion;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;

class InstanceRestartTest {
    @Test
    void theBuiltInPanelRestartsThroughThePluginWhenThePluginOffersIt() {
        AtomicInteger requested = new AtomicInteger();
        Runnable request = requested::incrementAndGet;
        EmbeddedPanelRestart offered = EmbeddedPanelRestart.from(Map.of(EmbeddedUpdateContract.PANEL_RESTART, request)).orElseThrow();

        InstanceRestart restart = restartFor(Deployment.EMBEDDED, offered);
        restart.restart();

        assertThat(restart.mode()).isEqualTo(Mode.IN_PLACE);
        assertThat(requested).hasValue(1);
        assertThat(EmbeddedPanelRestart.from(Map.of())).isEmpty();
    }

    @Test
    void theImageExitsForItsRestartPolicyAndAJarRestartsItself() {
        assertThat(restartFor(Deployment.DOCKER, null).mode()).isEqualTo(Mode.EXIT);
        assertThat(restartFor(Deployment.JAR, null).mode()).isEqualTo(Mode.IN_PLACE);
    }

    @Test
    void anOlderPluginAndUnmanagedStartsAreRestartedByTheOperator() {
        for (Deployment deployment : new Deployment[] {Deployment.EMBEDDED, Deployment.UNMANAGED}) {
            InstanceRestart restart = restartFor(deployment, null);

            assertThat(restart.mode()).as(deployment.name()).isEqualTo(Mode.MANUAL);
            assertThatThrownBy(restart::restart).isInstanceOf(IllegalStateException.class);
        }
    }

    @SuppressWarnings("unchecked")
    private static InstanceRestart restartFor(Deployment deployment, @Nullable EmbeddedPanelRestart offered) {
        DeploymentDetector detector = mock(DeploymentDetector.class);
        when(detector.deployment()).thenReturn(deployment);
        ObjectProvider<EmbeddedPanelRestart> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(offered);
        ReleaseBuild build = mock(ReleaseBuild.class);
        when(build.version()).thenReturn(ReleaseVersion.parse("3.0.0"));
        return new UpdateHostConfiguration().instanceRestart(detector, provider, mock(ConfigurableApplicationContext.class), build);
    }
}
