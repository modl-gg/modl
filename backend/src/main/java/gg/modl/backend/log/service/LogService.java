package gg.modl.backend.log.service;

import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.dto.response.SystemLogResponse;
import gg.modl.backend.log.repository.AuditLogRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogService {
    private static final int MAX_LIMIT = 500;
    private static final String LEVEL_MODERATION = "moderation";
    private static final String LEVEL_INFO = "info";

    private final AuditLogRepository auditLogRepository;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public List<SystemLogResponse> getLogs(Server server, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return auditLogRepository.findRecent(safeLimit).stream()
            .map(entry -> new SystemLogResponse(
                entry.getId(),
                entry.getDescription(),
                entry.getLevel(),
                entry.getSource(),
                entry.getCreated()
            ))
            .toList();
    }

    public void recordModerationAction(Server server, AuditActor actor, String description) {
        AuditLog entry = entry(description, LEVEL_MODERATION, actor);
        unitOfWork.write(() -> auditLogRepository.insert(entry));
    }

    public void recordStaffAction(Server server, AuditActor actor, String description) {
        AuditLog entry = entry(description, LEVEL_INFO, actor);
        unitOfWork.write(() -> unitOfWork.afterCommit(() -> insertQuietly(entry)));
    }

    private AuditLog entry(String description, String level, AuditActor actor) {
        return AuditLog.builder()
            .description(StorableText.strip(description))
            .level(level)
            .source(StorableText.strip(actor.displayName()))
            .created(clock.instant())
            .build();
    }

    private void insertQuietly(AuditLog entry) {
        try {
            auditLogRepository.insert(entry);
        } catch (RuntimeException e) {
            log.error("Failed to record audit log entry [{}]", entry.getDescription(), e);
        }
    }
}
