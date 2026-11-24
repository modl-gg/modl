package gg.modl.backend.log.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ImportedLogRepository {
    Set<Long> takenIds(ImportedLogTable table, Collection<Long> ids);

    void insertAudit(List<ImportedAuditEntry> entries);
}
