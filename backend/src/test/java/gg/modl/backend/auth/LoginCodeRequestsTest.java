package gg.modl.backend.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import org.junit.jupiter.api.Test;

class LoginCodeRequestsTest {
    private final Server server = TestServers.server("Server", "owner@example.com");
    private final PermissionService permissionService = mock(PermissionService.class);
    private final AuthService authService = mock(AuthService.class);
    private final LoginCodeRequests requests = new LoginCodeRequests(permissionService, authService);

    @Test
    void aKnownAccountGetsACode() {
        when(permissionService.isAuthorizedEmail(server, "staff@example.com")).thenReturn(true);

        requests.requestLoginCode(server, "staff@example.com");

        verify(authService).sendUserLoginCode(server, "staff@example.com");
    }

    @Test
    void anUnknownAccountGetsNothing() {
        requests.requestLoginCode(server, "nobody@example.com");

        verify(authService, never()).sendUserLoginCode(any(), anyString());
    }

    @Test
    void aFailureIsContainedInTheBackgroundTask() {
        when(permissionService.isAuthorizedEmail(server, "staff@example.com")).thenReturn(true);
        doThrow(new IllegalStateException("database unavailable")).when(authService).sendUserLoginCode(server, "staff@example.com");

        requests.requestLoginCode(server, "staff@example.com");
    }
}
