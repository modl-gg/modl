package gg.modl.backend.realtime.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.infrastructure.web.SameOriginPolicy;
import gg.modl.backend.instance.PublicUrls;
import org.junit.jupiter.api.Test;

class RealtimeOriginValidatorTest {
    private static final HttpOrigin REQUEST_ORIGIN = new HttpOrigin("http", "203.0.113.7", 8080);
    private static final HttpOrigin PUBLIC_ORIGIN = new HttpOrigin("https", "panel.example.com", -1);

    private final OperatorNotice operatorNotice = mock(OperatorNotice.class);
    private final RealtimeOriginValidator validator = new RealtimeOriginValidator(policy(), operatorNotice);

    @Test
    void acceptsTheRequestOriginWithoutNotice() {
        assertTrue(validator.isAllowedPanelOrigin("http://203.0.113.7:8080", REQUEST_ORIGIN));
        assertTrue(validator.isAllowedPanelOrigin("https://panel.example.com", REQUEST_ORIGIN));

        verify(operatorNotice, never()).publish(anyString(), any(Object[].class));
    }

    @Test
    void firstSameHostMismatchPublishesOneNoticeNamingTheExpectedPanelUrl() {
        assertFalse(validator.isAllowedPanelOrigin("https://203.0.113.7", REQUEST_ORIGIN));
        assertFalse(validator.isAllowedPanelOrigin("https://203.0.113.7", REQUEST_ORIGIN));
        assertFalse(validator.isAllowedPanelOrigin("http://203.0.113.7:9090", REQUEST_ORIGIN));

        verify(operatorNotice, times(1)).publish(eq(RealtimeOriginValidator.MISCONFIGURED_PANEL_URL_NOTICE),
            eq("https://203.0.113.7"), eq("https://panel.example.com"));
    }

    @Test
    void foreignOriginsNeverReachTheConsole() {
        assertFalse(validator.isAllowedPanelOrigin("https://other.example.net", REQUEST_ORIGIN));
        assertFalse(validator.isAllowedPanelOrigin("javascript:alert(1)", REQUEST_ORIGIN));
        assertFalse(validator.isAllowedPanelOrigin(null, REQUEST_ORIGIN));
        assertFalse(validator.isAllowedPanelOrigin("http://203.0.113.7:8080", null));

        verify(operatorNotice, never()).publish(anyString(), any(Object[].class));
    }

    private static SameOriginPolicy policy() {
        PublicUrls publicUrls = mock(PublicUrls.class);
        when(publicUrls.publicOrigin()).thenReturn(PUBLIC_ORIGIN);
        return new SameOriginPolicy(publicUrls);
    }
}
