package gg.modl.backend.replay.data;

import java.time.Instant;

public record ReplayUploadClaim(String replayId, Instant leaseExpiresAt) {
}
