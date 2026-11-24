package gg.modl.backend.update.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerProcessCommandTest {
    @Test
    void keepsArgumentsWithSpacesAndDropsPortsTheParentHolds() {
        List<String> command = ServerProcessCommand.build("/opt/java/bin/java",
            List.of("-Xmx2g", "-Dmodl.note=with spaces", "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                "-Xrunjdwp:transport=dt_socket", "-Dcom.sun.management.jmxremote.port=9010",
                "-Dcom.sun.management.jmxremote.rmi.port=9011", "-Dcom.sun.management.jmxremote.ssl=false"),
            Path.of("/srv/modl/data/updates/versions/3.0.1/modl-server-3.0.1.jar"), new String[] {"--modl.data-dir=/srv/my data"});

        assertThat(command).containsExactly("/opt/java/bin/java", "-Xmx2g", "-Dmodl.note=with spaces",
            "-Dcom.sun.management.jmxremote.ssl=false", "-jar",
            Path.of("/srv/modl/data/updates/versions/3.0.1/modl-server-3.0.1.jar").toAbsolutePath().toString(),
            "--modl.data-dir=/srv/my data");
    }

    @Test
    void fallsBackToTheRunningJavaHome() {
        assertThat(ServerProcessCommand.javaFromHome()).startsWith(System.getProperty("java.home"));
        assertThat(ServerProcessCommand.javaCommand()).isNotBlank();
    }
}
