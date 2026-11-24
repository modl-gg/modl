package gg.modl.backend.role.service;

import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.server.data.Server;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MinecraftIssuerAuthorizer {
    private static final String NOT_PERMITTED = "You do not have permission to do this";

    private final RoleAuthorization roleAuthorization;
    private final PermissionService permissionService;

    public void requirePermission(Server server, MinecraftIssuer issuer, String permissionId) {
        if (!permits(server, issuer, permissionId)) {
            throw new ForbiddenException(NOT_PERMITTED);
        }
    }

    public boolean permits(Server server, MinecraftIssuer issuer, String permissionId) {
        return permissionsOf(server, issuer).test(permissionId);
    }

    public Predicate<String> permissionsOf(Server server, MinecraftIssuer issuer) {
        if (issuer.isConsole()) {
            return permissionId -> true;
        }
        RoleAuthorization.PerformerAuthority performer = identifiedPerformer(server, issuer);
        return new PanelPrincipalPermissions(server, performer.roleId(), performer.superAdmin(), permissionService)::has;
    }

    private RoleAuthorization.PerformerAuthority identifiedPerformer(Server server, MinecraftIssuer issuer) {
        RoleAuthorization.PerformerAuthority performer = roleAuthorization.minecraftPerformer(server, issuer.staffId());
        if (!performer.identified()) {
            throw new ForbiddenException(NOT_PERMITTED);
        }
        return performer;
    }
}
