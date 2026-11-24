package gg.modl.backend.infrastructure.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.PublicUrls;
import org.junit.jupiter.api.Test;

class SameOriginPolicyTest {
    private static final HttpOrigin REQUEST_ORIGIN = new HttpOrigin("http", "localhost", 8080);

    private final SameOriginPolicy policy = policyWithPublicOrigin(new HttpOrigin("https", "panel.example.com", -1));

    @Test
    void trustsTheEffectiveRequestOriginAndThePublicOrigin() {
        assertTrue(policy.isTrustedOrigin("http://localhost:8080", REQUEST_ORIGIN));
        assertTrue(policy.isTrustedOrigin("https://panel.example.com", REQUEST_ORIGIN));
        assertTrue(policy.isTrustedOrigin("https://panel.example.com/panel/tickets", REQUEST_ORIGIN));
    }

    @Test
    void rejectsOtherOriginsAndMissingValues() {
        assertFalse(policy.isTrustedOrigin("https://evil.example.com", REQUEST_ORIGIN));
        assertFalse(policy.isTrustedOrigin("http://localhost:8081", REQUEST_ORIGIN));
        assertFalse(policy.isTrustedOrigin("null", REQUEST_ORIGIN));
        assertFalse(policy.isTrustedOrigin(null, REQUEST_ORIGIN));
    }

    private static SameOriginPolicy policyWithPublicOrigin(HttpOrigin publicOrigin) {
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.publicOrigin()).thenReturn(publicOrigin);
        return new SameOriginPolicy(publicUrls);
    }
}
