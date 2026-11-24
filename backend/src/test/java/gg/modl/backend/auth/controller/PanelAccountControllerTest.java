package gg.modl.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.link.MinecraftAccountLinkService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.staff.service.StaffTwoFactorService;
import gg.modl.backend.support.TestServers;
import gg.modl.proto.modl.v1.Staff2faVerifyRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PanelAccountControllerTest {
    private final Server server = TestServers.server("Server", "owner@example.com");
    private final StaffTwoFactorService staffTwoFactorService = mock(StaffTwoFactorService.class);
    private final PanelAccountController controller = new PanelAccountController(mock(MinecraftAccountLinkService.class), staffTwoFactorService);

    @Test
    void theSignedInStaffMemberVerifiesTheirOwnLink() {
        when(staffTwoFactorService.verify(server, "token", "mod@example.com")).thenReturn(true);

        assertThat(controller.verifyTwoFactor(body(), request("mod@example.com")).getStatus()).isEqualTo("verified");
    }

    @Test
    void everyRejectionLooksTheSame() {
        assertThatThrownBy(() -> controller.verifyTwoFactor(body(), request("someone@example.com")))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("TWO_FACTOR_LINK_INVALID");
    }

    private static Staff2faVerifyRequest body() {
        return Staff2faVerifyRequest.newBuilder().setToken("token").build();
    }

    private MockHttpServletRequest request(String sessionEmail) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/account/two-factor/verify");
        request.setAttribute(RequestAttribute.SERVER, server);
        AuthSessionData session = new AuthSessionData();
        session.setEmail(sessionEmail);
        request.setAttribute(RequestAttribute.SESSION, session);
        return request;
    }
}
