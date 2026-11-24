package gg.modl.backend.log.repository;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.UnitOfWork;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

@Primary
@Repository
public class ObservedAuditLogRepository implements AuditLogRepository {
    private final JooqAuditLogRepository delegate;
    private final UnitOfWork unitOfWork;
    private final List<AuditLogObserver> observers;

    public ObservedAuditLogRepository(JooqAuditLogRepository delegate, UnitOfWork unitOfWork, List<AuditLogObserver> observers) {
        this.delegate = delegate;
        this.unitOfWork = unitOfWork;
        this.observers = List.copyOf(observers);
    }

    @Override
    public void insert(AuditLog auditLog) {
        unitOfWork.write(() -> {
            delegate.insert(auditLog);
            unitOfWork.afterCommit(() -> observers.forEach(observer -> observer.committed(auditLog)));
        });
    }

    @Override
    public List<AuditLog> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public long countEvidenceUploads(String username, @Nullable Instant startDate) {
        return delegate.countEvidenceUploads(username, startDate);
    }

    @Override
    public long deleteOldestBatch(Instant createdBefore, int batchSize) {
        return delegate.deleteOldestBatch(createdBefore, batchSize);
    }
}
