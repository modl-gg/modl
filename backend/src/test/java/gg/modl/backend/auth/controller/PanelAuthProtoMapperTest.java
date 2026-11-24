package gg.modl.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.proto.modl.v1.PanelSessionInfoResponse;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PanelAuthProtoMapperTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void sessionAddressesAreShownInTheirDisplayForm() {
        List<PanelSessionInfoResponse> sessions = PanelAuthProtoMapper.toSessionsResponse(List.of(
            session("current", "0:0:0:0:0:0:0:1"),
            session("other", "203.0.113.7"),
            session("unknown", null)), "current").getSessionsList();

        assertThat(sessions).extracting(PanelSessionInfoResponse::getIpAddress).containsExactly("::1", "203.0.113.7", "");
        assertThat(sessions).extracting(PanelSessionInfoResponse::getIsCurrent).containsExactly(true, false, false);
    }

    private static PanelSession session(String publicId, @Nullable String ipAddress) {
        return new PanelSession(publicId, "owner@example.com", "hash", ipAddress, "agent", CREATED, CREATED.plusSeconds(3600),
            AuthMethod.EMAIL_CODE, true);
    }
}
