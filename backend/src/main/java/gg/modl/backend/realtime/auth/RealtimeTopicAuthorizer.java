package gg.modl.backend.realtime.auth;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.network.NetworkRolePolicy;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.Topic;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeTopicAuthorizer {
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;

    public boolean canSubscribe(RealtimePrincipal principal, Topic topic) {
        if (topic == null || topic == Topic.TOPIC_UNSPECIFIED) {
            return false;
        }

        if (principal.clientKind() == RealtimeClientKind.MINECRAFT) {
            return isMinecraftTopic(principal, topic);
        }

        if (principal.clientKind() == RealtimeClientKind.PANEL) {
            return switch (topic) {
                case TOPIC_PANEL_TICKETS, TOPIC_PANEL_ASSIGNED_TICKETS,
                     TOPIC_PANEL_NOTIFICATIONS, TOPIC_PANEL_APPEALS
                    -> hasPanelPermission(principal, PermissionService.TICKET_VIEW_ALL);
                case TOPIC_PANEL_PLAYERS, TOPIC_PANEL_PUNISHMENTS -> panelPermissions(principal)
                    .map(PanelPrincipalPermissions::canViewPlayers)
                    .orElse(false);
                case TOPIC_PANEL_STAFF -> hasPanelPermission(principal, PermissionService.ADMIN_STAFF_MANAGE_MEMBERS);
                case TOPIC_PANEL_ROLES -> hasPanelPermission(principal, PermissionService.ADMIN_STAFF_MANAGE_ROLES);
                case TOPIC_PANEL_PUNISHMENT_TYPES -> hasPanelPermission(principal, PermissionService.ADMIN_SETTINGS_VIEW_PUNISHMENTS);
                case TOPIC_PANEL_KNOWLEDGEBASE, TOPIC_PANEL_HOMEPAGE
                    -> hasPanelPermission(principal, PermissionService.ADMIN_SETTINGS_VIEW_CONTENT);
                case TOPIC_PANEL_SETTINGS -> panelPermissions(principal)
                    .map(RealtimeTopicAuthorizer::viewsAnySettings)
                    .orElse(false);
                case TOPIC_PANEL_AUDIT -> hasPanelPermission(principal, PermissionService.ADMIN_AUDIT_VIEW_LOGS);
                case TOPIC_PANEL_DASHBOARD -> hasPanelPermission(principal, PermissionService.ADMIN_AUDIT_VIEW_DASHBOARD);
                default -> false;
            };
        }

        return false;
    }

    private static boolean viewsAnySettings(PanelPrincipalPermissions permissions) {
        return permissions.has(PermissionService.ADMIN_SETTINGS_VIEW)
            || permissions.hasAnyWithPrefix(PermissionService.ADMIN_SETTINGS_VIEW + ".");
    }

    private boolean hasPanelPermission(RealtimePrincipal principal, String permission) {
        return panelPermissions(principal).map(permissions -> permissions.has(permission)).orElse(false);
    }

    private Optional<PanelPrincipalPermissions> panelPermissions(RealtimePrincipal principal) {
        Server server = principal.server();
        String email = principal.email();
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        if (permissionService.isSuperAdmin(server, email)) {
            return Optional.of(new PanelPrincipalPermissions(server, null, true, permissionService));
        }
        String roleId = roleAuthorization.panelRoleId(server, email);
        return roleId == null
            ? Optional.empty()
            : Optional.of(new PanelPrincipalPermissions(server, roleId, false, permissionService));
    }

    private static boolean isMinecraftTopic(RealtimePrincipal principal, Topic topic) {
        return NetworkRolePolicy.grants(principal.role(), topic);
    }
}
