package gg.modl.backend.infrastructure.filter;

import gg.modl.backend.infrastructure.authorization.PanelAccessPolicy;
import gg.modl.backend.infrastructure.authorization.PanelAccessPolicyResolver;
import gg.modl.backend.infrastructure.authorization.PanelAccessRequest;
import gg.modl.backend.infrastructure.authorization.PanelPrincipalPermissions;
import gg.modl.backend.infrastructure.exception.FilterErrorResponse;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.infrastructure.rest.RouteGroups;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class PanelPermissionFilter extends OncePerRequestFilter {
    private static final String FORBIDDEN_MESSAGE = "Insufficient permissions";

    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final PanelAccessPolicyResolver policyResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RouteGroups.classify(request).requiresPanelPermission();
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Server server = (Server) request.getAttribute(RequestAttribute.SERVER);
        String email = RequestUtil.getSessionEmail(request);
        if (email == null) {
            UnauthenticatedResponse.write(response);
            return;
        }
        if (server == null) {
            deny(response);
            return;
        }

        boolean superAdmin = permissionService.isSuperAdmin(server, email);
        if (superAdmin) {
            filterChain.doFilter(request, response);
            return;
        }

        List<PanelAccessPolicy> policies = policyResolver.resolvePolicies(request);
        if (policies.isEmpty()) {
            deny(response);
            return;
        }

        PanelAccessRequest accessRequest = new PanelAccessRequest(request.getMethod(), RouteGroups.classify(request).path());
        if (policies.stream().anyMatch(policy -> policy.permitsWithoutRole(accessRequest))) {
            filterChain.doFilter(request, response);
            return;
        }

        String roleId = roleAuthorization.panelRoleId(server, email);
        PanelPrincipalPermissions permissions = new PanelPrincipalPermissions(server, roleId, superAdmin, permissionService);

        if (policies.stream().anyMatch(policy -> policy.permitsWithRole(accessRequest, permissions))) {
            filterChain.doFilter(request, response);
            return;
        }

        deny(response);
    }

    private void deny(HttpServletResponse response) throws IOException {
        FilterErrorResponse.writeJson(response, HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE);
    }
}
