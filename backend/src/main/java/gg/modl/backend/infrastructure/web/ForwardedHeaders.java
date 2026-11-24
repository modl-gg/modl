package gg.modl.backend.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

public final class ForwardedHeaders {
    private static final List<String> NAMES = List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host");

    private ForwardedHeaders() {
    }

    public static boolean present(HttpServletRequest request) {
        return NAMES.stream().anyMatch(name -> request.getHeader(name) != null);
    }
}
