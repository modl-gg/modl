package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

final class UnauthenticatedResponse implements AuthenticationEntryPoint {
    private static final String MESSAGE = "Authentication required";

    static void write(HttpServletResponse response) throws IOException {
        FilterErrorResponse.writeJson(response, HttpStatus.UNAUTHORIZED, MESSAGE);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
        throws IOException {
        write(response);
    }
}
