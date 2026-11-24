package gg.modl.backend.ticket.service;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.ticket.dto.response.TicketResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TicketNoteVisibility {
    private final RoleAuthorization roleAuthorization;
    private final PermissionService permissionService;

    public boolean mayView(Server server, @Nullable String staffEmail) {
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.panelPerformer(server, staffEmail);
        return performer.identified()
            && new PanelPrincipalPermissions(server, performer.roleId(), performer.superAdmin(), permissionService)
                .has(PermissionService.TICKET_VIEW_ALL_NOTES);
    }

    public TicketResponse visibleTo(Server server, @Nullable String staffEmail, TicketResponse ticket) {
        return mayView(server, staffEmail) ? ticket : ticket.withoutNotes();
    }

    public List<TicketResponse> visibleTo(Server server, @Nullable String staffEmail, List<TicketResponse> tickets) {
        if (mayView(server, staffEmail)) {
            return tickets;
        }
        return tickets.stream().map(TicketResponse::withoutNotes).toList();
    }
}
