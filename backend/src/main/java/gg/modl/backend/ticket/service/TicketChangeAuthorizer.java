package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketChangeAuthorizer {
    private static final String NOT_PERMITTED = "You do not have permission to make this change to a ticket";

    private final RoleAuthorization roleAuthorization;
    private final PermissionService permissionService;

    public void requirePermitted(Server server, @Nullable String staffEmail, Set<TicketChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.panelPerformer(server, staffEmail);
        if (!performer.identified()) {
            throw new ForbiddenException(NOT_PERMITTED);
        }
        PanelPrincipalPermissions permissions =
            new PanelPrincipalPermissions(server, performer.roleId(), performer.superAdmin(), permissionService);
        if (!changes.stream().map(TicketChange::permission).allMatch(permissions::has)) {
            throw new ForbiddenException(NOT_PERMITTED);
        }
    }
}
