package gg.modl.backend.update.commit;

import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.update.UpdateErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class UpdateCommitGateFilter extends OncePerRequestFilter {
    static final String RETRY_AFTER_SECONDS = "2";

    private final UpdateGate gate;

    public UpdateCommitGateFilter(UpdateGate gate) {
        this.gate = gate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (gate.closed() && !RouteGroups.classify(request).isOperational()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            FilterErrorResponse.writeJson(response, HttpStatus.SERVICE_UNAVAILABLE, "modl is finishing an update; try again shortly",
                UpdateErrors.STARTING);
            return;
        }
        chain.doFilter(request, response);
    }
}
