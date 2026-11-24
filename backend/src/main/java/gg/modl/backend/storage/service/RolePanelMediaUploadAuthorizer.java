package gg.modl.backend.storage.service;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.MediaCategory;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RolePanelMediaUploadAuthorizer implements PanelMediaUploadAuthorizer {
    private static final String INVALID_UPLOAD_TYPE_MESSAGE = "Invalid upload type";
    private static final String UNRECOGNIZED_KEY_MESSAGE = "Access denied";
    private static final String NOT_PERMITTED_MESSAGE = "You do not have permission to upload this type of file";

    private final RoleAuthorization roleAuthorization;
    private final PermissionService permissionService;

    @Override
    public void authorizePresign(Server server, @Nullable String staffEmail, String uploadType) {
        MediaCategory category = MediaCategory.fromUploadType(uploadType)
            .orElseThrow(() -> new ValidationException(INVALID_UPLOAD_TYPE_MESSAGE));
        requirePermitted(server, staffEmail, category);
    }

    @Override
    public void authorizeConfirm(Server server, @Nullable String staffEmail, String key) {
        MediaCategory category = StorageKeyUtils.mediaCategoryOf(key)
            .orElseThrow(() -> new ForbiddenException(UNRECOGNIZED_KEY_MESSAGE));
        requirePermitted(server, staffEmail, category);
    }

    private void requirePermitted(Server server, @Nullable String staffEmail, MediaCategory category) {
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.panelPerformer(server, staffEmail);
        if (!performer.identified() || !permits(principalPermissions(server, performer), category)) {
            throw new ForbiddenException(NOT_PERMITTED_MESSAGE);
        }
    }

    private PanelPrincipalPermissions principalPermissions(Server server, RoleAuthorization.PerformerAuthority performer) {
        return new PanelPrincipalPermissions(server, performer.roleId(), performer.superAdmin(), permissionService);
    }

    private static boolean permits(PanelPrincipalPermissions permissions, MediaCategory category) {
        return switch (category) {
            case EVIDENCE -> permissions.has(PermissionService.PUNISHMENT_MODIFY_EVIDENCE)
                || permissions.hasAnyWithPrefix(PermissionService.PUNISHMENT_APPLY_PREFIX);
            case TICKET -> permissions.has(PermissionService.TICKET_REPLY_ALL);
            case APPEAL -> permissions.has(PermissionService.APPEAL_MODIFY)
                || permissions.has(PermissionService.TICKET_REPLY_ALL);
            case ARTICLE -> permissions.has(PermissionService.ADMIN_SETTINGS_MODIFY_CONTENT);
            case SERVER_ICON -> permissions.has(PermissionService.ADMIN_SETTINGS_MODIFY);
            case REPLAY -> false;
        };
    }
}
