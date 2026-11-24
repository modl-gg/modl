package gg.modl.backend.log.service;

import gg.modl.backend.server.data.Server;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PanelActionAuditor {
    private final LogService logService;
    private final AuditActors auditActors;

    public void recordStaffAction(Server server, @Nullable String actorEmail, String description) {
        logService.recordStaffAction(server, auditActors.account(server, actorEmail), description);
    }

    public void recordModerationAction(Server server, @Nullable String actorEmail, String description) {
        logService.recordModerationAction(server, auditActors.account(server, actorEmail), description);
    }
}
