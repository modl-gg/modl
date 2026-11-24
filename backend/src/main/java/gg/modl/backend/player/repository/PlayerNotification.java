package gg.modl.backend.player.repository;

import java.time.Instant;
import java.util.Map;

public record PlayerNotification(String id, Map<String, Object> payload, Instant createdAt, Instant expiresAt) {
}
