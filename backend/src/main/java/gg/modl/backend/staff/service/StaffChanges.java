package gg.modl.backend.staff.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.service.ServerTimestampService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StaffChanges {
    private final UnitOfWork unitOfWork;
    private final StaffLookupCache staffLookupCache;
    private final ServerTimestampService serverTimestampService;

    void profileChanged(Server server, String... emails) {
        List<String> changedEmails = List.of(emails);
        unitOfWork.afterCommit(() -> changedEmails.forEach(email -> staffLookupCache.evict(server, email)));
    }

    void accessChanged(Server server, String email) {
        profileChanged(server, email);
        serverTimestampService.updateStaffPermissionsTimestamp(server);
    }
}
