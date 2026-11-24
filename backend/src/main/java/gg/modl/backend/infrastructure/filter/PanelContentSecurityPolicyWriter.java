package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.storage.StorageOrigins;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.web.header.HeaderWriter;

final class PanelContentSecurityPolicyWriter implements HeaderWriter {
    static final String HEADER = "Content-Security-Policy";

    private final StorageOrigins storageOrigins;
    private final AtomicReference<RenderedPolicy> rendered = new AtomicReference<>();

    PanelContentSecurityPolicyWriter(StorageOrigins storageOrigins) {
        this.storageOrigins = storageOrigins;
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (!response.containsHeader(HEADER)) {
            response.setHeader(HEADER, policy());
        }
    }

    private String policy() {
        Set<String> origins = storageOrigins.origins();
        RenderedPolicy current = rendered.get();
        if (current != null && current.origins().equals(origins)) {
            return current.policy();
        }
        RenderedPolicy next = new RenderedPolicy(Set.copyOf(origins), PanelContentSecurityPolicy.of(origins));
        rendered.set(next);
        return next.policy();
    }

    private record RenderedPolicy(Set<String> origins, String policy) {
    }
}
