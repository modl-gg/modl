package gg.modl.backend.infrastructure.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class AccountRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitGate rateLimitGate;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return rateLimitGate.isExempt(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        if (rateLimitGate.admitAccount(request, response)) {
            filterChain.doFilter(request, response);
        }
    }
}
