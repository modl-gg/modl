package gg.modl.minecraft.core.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendReplayUploadAuthorityTest {
    @Test
    void theAdvisedDelayIsUsedWithinItsBounds() {
        assertEquals(5_000L, BackendReplayUploadAuthority.retryAfterMs("5"));
        assertEquals(1_000L, BackendReplayUploadAuthority.retryAfterMs("0"));
        assertEquals(30_000L, BackendReplayUploadAuthority.retryAfterMs("3600"));
    }

    @Test
    void aMissingOrUnreadableDelayWaitsTheDefault() {
        assertEquals(5_000L, BackendReplayUploadAuthority.retryAfterMs(null));
        assertEquals(5_000L, BackendReplayUploadAuthority.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"));
    }
}
