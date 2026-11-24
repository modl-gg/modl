package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.staffsecurity.StaffSecurityService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.PasswordResetLinkResponse;
import gg.modl.proto.modl.v1.StaffSecurityListResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_STAFF_SECURITY)
@RequiresPanelPermission(rule = PanelAccessRule.SUPER_ADMIN)
@RequiredArgsConstructor
public class PanelStaffSecurityController {
    private final StaffSecurityService staffSecurityService;

    @GetMapping
    public StaffSecurityListResponse list(HttpServletRequest request) {
        return SignInProtoMapper.toStaffSecurity(staffSecurityService.list(RequestUtil.getRequestServer(request)));
    }

    @PostMapping("/{staffId}/password-reset")
    public PasswordResetLinkResponse createPasswordReset(@PathVariable String staffId, HttpServletRequest request) {
        return SignInProtoMapper.toProto(staffSecurityService.createPasswordResetLink(RequestUtil.getRequestServer(request),
            RequestUtil.requireSessionEmail(request), staffId));
    }

    @DeleteMapping("/{staffId}/second-factor")
    public ResponseEntity<Void> resetSecondFactor(@PathVariable String staffId, HttpServletRequest request) {
        staffSecurityService.resetSecondFactor(RequestUtil.getRequestServer(request), RequestUtil.requireSessionEmail(request), staffId);
        return ResponseEntity.noContent().build();
    }
}
