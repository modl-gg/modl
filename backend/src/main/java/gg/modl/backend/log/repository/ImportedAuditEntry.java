package gg.modl.backend.log.repository;

import java.time.Instant;
import java.util.Map;

public record ImportedAuditEntry(long id, String level, String source, String description, Map<String, Object> metadata,
                                 boolean canRollback, Instant createdAt) {
}
