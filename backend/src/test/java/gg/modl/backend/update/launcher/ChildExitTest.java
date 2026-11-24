package gg.modl.backend.update.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.update.EmbeddedUpdateContract;
import gg.modl.update.HostDescriptor;
import gg.modl.update.ReleaseVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChildExitTest {
    @Test
    void aRestartRequestIsNotAnUpdateRestart() {
        SupervisedServer.ChildExit restart = new SupervisedServer.ChildExit(EmbeddedUpdateContract.RESTART_EXIT_CODE, false);
        SupervisedServer.ChildExit update = new SupervisedServer.ChildExit(EmbeddedUpdateContract.RESTART_FOR_UPDATE_EXIT_CODE, false);

        assertThat(restart.restartRequested()).isTrue();
        assertThat(restart.restartForUpdate()).isFalse();
        assertThat(update.restartRequested()).isFalse();
        assertThat(update.restartForUpdate()).isTrue();
    }

    @Test
    void onlyALauncherAsNewAsTheServerIsTrustedToRestartIt() {
        ReleaseVersion running = ReleaseVersion.parse("3.1.0");

        assertThat(UpdateLauncher.restartsChildren(null, running)).as("no launcher: the server supervises itself").isTrue();
        assertThat(UpdateLauncher.restartsChildren(launcher("3.1.0"), running)).isTrue();
        assertThat(UpdateLauncher.restartsChildren(launcher("3.1.2"), running)).isTrue();
        assertThat(UpdateLauncher.restartsChildren(launcher("3.0.9"), running)).isFalse();
        assertThat(UpdateLauncher.restartsChildren("not a descriptor", running)).isFalse();
    }

    private static String launcher(String version) {
        return new HostDescriptor(EmbeddedUpdateContract.LAUNCHER_API, ReleaseVersion.parse(version), List.of()).toString();
    }
}
