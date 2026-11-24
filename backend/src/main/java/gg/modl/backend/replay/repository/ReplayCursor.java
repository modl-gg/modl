package gg.modl.backend.replay.repository;

import java.time.Instant;

public record ReplayCursor(Instant createdAt, String id) {
}
