package gg.modl.backend.update.commit;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdateTransaction;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UpdateCommitGateFilterTest {
    private final UpdateGate gate = new UpdateGate();

    @Test
    void holdsRequestsAndTheWebSocketHandshakeUntilCommit() throws Exception {
        holdGate();

        for (String path : new String[] {"/v1/panel/players", "/v1/realtime/ws", "/v2/minecraft/startup", "/"}) {
            MockHttpServletResponse response = call(path);
            assertThat(response.getStatus()).as(path).isEqualTo(503);
            assertThat(response.getHeader("Retry-After")).isEqualTo("2");
            assertThat(response.getContentAsString()).contains("UPDATE_STARTING");
        }
    }

    @Test
    void healthStaysReachable() throws Exception {
        holdGate();

        assertThat(call("/actuator/health").getStatus()).isEqualTo(200);
        assertThat(call("/v1/health").getStatus()).isEqualTo(200);
        assertThat(call("/actuator/health/../../v1/panel/players").getStatus()).isEqualTo(503);
        assertThat(call("/v1/healthz").getStatus()).isEqualTo(503);
    }

    @Test
    void anOpenGateServesEverything() throws Exception {
        assertThat(call("/v1/panel/players").getStatus()).isEqualTo(200);
        holdGate();
        gate.open();
        assertThat(call("/v1/panel/players").getStatus()).isEqualTo(200);
    }

    private void holdGate() {
        gate.hold(UpdateTransaction.builder("id", TransactionKind.NONE, ReleaseVersion.parse("3.0.0"), ReleaseVersion.parse("3.0.1"),
            Instant.EPOCH).build());
    }

    private MockHttpServletResponse call(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new UpdateCommitGateFilter(gate).doFilter(new MockHttpServletRequest("GET", path), response, new MockFilterChain());
        return response;
    }
}
