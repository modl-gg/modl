package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.support.TestServers;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SetupStatusServiceTest {
    private InstanceContext instanceContext;

    @BeforeEach
    void setUp() {
        instanceContext = mock(InstanceContext.class);
    }

    @Test
    void anUnclaimedInstanceReportsItsPhaseAndTheEmbeddedHost() {
        givenInstance(server(null, null));

        SetupStatus status = service(new InstanceProperties(null, null, null, null, "internal", null)).status();

        assertThat(status.claimed()).isFalse();
        assertThat(status.phase()).isEqualTo(SetupPhase.UNCLAIMED);
        assertThat(status.host()).isEqualTo(SetupHost.PLUGIN);
    }

    @Test
    void aReadyInstanceReportsTheStandaloneHost() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), Instant.now(), null, null)));

        SetupStatus status = service(properties()).status();

        assertThat(status.phase()).isEqualTo(SetupPhase.READY);
        assertThat(status.host()).isEqualTo(SetupHost.STANDALONE);
    }

    @Test
    void aPresetOwnerWhoNeverSignedInIsInTheOwnerSignInPhase() {
        givenInstance(server("owner@example.com", new SetupProgress(Instant.now(), null, null, null)));

        assertThat(service(properties()).status().phase()).isEqualTo(SetupPhase.OWNER_SIGN_IN);
    }

    private void givenInstance(Server server) {
        when(instanceContext.instance()).thenReturn(server);
    }

    private SetupStatusService service(InstanceProperties properties) {
        return new SetupStatusService(instanceContext, properties);
    }

    private static InstanceProperties properties() {
        return new InstanceProperties(null, null, null, null, null, null);
    }

    private static Server server(String adminEmail, SetupProgress setup) {
        Server server = TestServers.server("Server", adminEmail);
        server.setSetup(setup);
        return server;
    }
}
