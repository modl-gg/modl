package gg.modl.backend.log.repository;

import gg.modl.backend.audit.data.AuditLog;

public interface AuditLogObserver {
    void committed(AuditLog entry);
}
