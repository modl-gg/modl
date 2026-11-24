package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.dto.request.AddModificationRequest;
import gg.modl.backend.role.service.MinecraftIssuer;
import gg.modl.backend.role.service.MinecraftIssuerAuthorizer;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PunishmentModificationAuthorizer {
    public static final String APPROVAL_NEEDS_PARDON = "APPEAL_APPROVAL_NEEDS_PARDON";

    private static final String UNSUPPORTED_MODIFICATION = "This modification type cannot be applied from the panel";
    private static final String NOT_PERMITTED = "You do not have permission to make this change to a punishment";
    private static final String APPEAL_NOT_PERMITTED = "You do not have permission to decide appeals";
    private static final String APPROVAL_NOT_PERMITTED = "Approving an appeal needs the permission to pardon punishments";

    private final RoleAuthorization roleAuthorization;
    private final PermissionService permissionService;
    private final MinecraftIssuerAuthorizer issuerAuthorizer;

    public void requirePermitted(Server server, @Nullable String staffEmail, AddModificationRequest request) {
        PunishmentModificationType type = PunishmentModificationType.fromName(request.type());
        if (type == null) {
            throw new ValidationException(UNSUPPORTED_MODIFICATION, "INVALID_ARGUMENT");
        }
        String permission = requiredPermission(type)
            .orElseThrow(() -> new ValidationException(UNSUPPORTED_MODIFICATION, "INVALID_ARGUMENT"));

        PanelPrincipalPermissions permissions = identifiedPermissions(server, staffEmail);
        if (!permissions.has(permission)) {
            throw type == PunishmentModificationType.APPEAL_ACCEPT ? approvalNeedsPardon() : new ForbiddenException(NOT_PERMITTED);
        }
        boolean decidesAppeal = request.appealTicketId() != null && type.appealOutcome() != null;
        if (decidesAppeal && !mayDecideAppeals(permissions)) {
            throw new ForbiddenException(APPEAL_NOT_PERMITTED);
        }
    }

    public void requireAppealApproval(Server server, @Nullable String staffEmail) {
        if (!identifiedPermissions(server, staffEmail).has(PermissionService.PUNISHMENT_MODIFY_PARDON)) {
            throw approvalNeedsPardon();
        }
    }

    public PunishmentShortening shorteningBy(Server server, @Nullable String staffEmail) {
        return PunishmentShortening.grantedBy(() -> identifiedPermissions(server, staffEmail)::has);
    }

    public PunishmentShortening shorteningBy(Server server, MinecraftIssuer issuer) {
        return PunishmentShortening.grantedBy(() -> issuerAuthorizer.permissionsOf(server, issuer));
    }

    private PanelPrincipalPermissions identifiedPermissions(Server server, @Nullable String staffEmail) {
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.panelPerformer(server, staffEmail);
        if (!performer.identified()) {
            throw new ForbiddenException(NOT_PERMITTED);
        }
        return new PanelPrincipalPermissions(server, performer.roleId(), performer.superAdmin(), permissionService);
    }

    private static ForbiddenException approvalNeedsPardon() {
        return new ForbiddenException(APPROVAL_NOT_PERMITTED, APPROVAL_NEEDS_PARDON);
    }

    private static Optional<String> requiredPermission(PunishmentModificationType type) {
        return switch (type) {
            case MANUAL_PARDON, APPEAL_ACCEPT -> Optional.of(PermissionService.PUNISHMENT_MODIFY_PARDON);
            case MANUAL_DURATION_CHANGE, APPEAL_DURATION_CHANGE -> Optional.of(PermissionService.PUNISHMENT_MODIFY_DURATION);
            case SET_ALT_BLOCKING_TRUE, SET_ALT_BLOCKING_FALSE, SET_WIPING_TRUE, SET_WIPING_FALSE ->
                Optional.of(PermissionService.PUNISHMENT_MODIFY_OPTIONS);
            case APPEAL_REJECT -> Optional.of(PermissionService.PUNISHMENT_MODIFY);
            case SYSTEM_PARDON, ROLLBACK -> Optional.empty();
        };
    }

    private static boolean mayDecideAppeals(PanelPrincipalPermissions permissions) {
        return permissions.has(PermissionService.PUNISHMENT_MODIFY)
            || permissions.has(PermissionService.APPEAL_MODIFY)
            || permissions.has(PermissionService.TICKET_REPLY_ALL);
    }
}
