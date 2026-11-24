package gg.modl.backend.log.repository;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.retention.repository.AgeRetainedRecords;
import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends AgeRetainedRecords {
    void insert(AuditLog auditLog);

    List<AuditLog> findRecent(int limit);

    long countEvidenceUploads(String username, Instant startDate);
}
