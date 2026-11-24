package gg.modl.backend.auth.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.addressValue;

import gg.modl.backend.auth.link.LinkCodeIssue;
import gg.modl.backend.auth.link.MinecraftAccountLinkService;
import gg.modl.backend.infrastructure.authorization.PanelAccessRule;
import gg.modl.backend.infrastructure.authorization.RequiresPanelPermission;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.staff.service.StaffTwoFactorService;
import gg.modl.proto.modl.v1.PanelMinecraftLinkCodeResponse;
import gg.modl.proto.modl.v1.Staff2faPendingApprovalResponse;
import gg.modl.proto.modl.v1.Staff2faVerifyRequest;
import gg.modl.proto.modl.v1.Staff2faVerifyResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_ACCOUNT)
@RequiresPanelPermission(rule = PanelAccessRule.PERMIT_ALL)
@RequiredArgsConstructor
public class PanelAccountController {
    private static final String VERIFIED = "verified";

    private final MinecraftAccountLinkService minecraftAccountLinkService;
    private final StaffTwoFactorService staffTwoFactorService;

    @PostMapping("/minecraft-link")
    public PanelMinecraftLinkCodeResponse createMinecraftLinkCode(HttpServletRequest request) {
        LinkCodeIssue issue = minecraftAccountLinkService.issueCode(RequestUtil.getRequestServer(request), RequestUtil.requireSessionEmail(request));
        return PanelMinecraftLinkCodeResponse.newBuilder()
            .setCode(issue.code())
            .setExpiresInSeconds(issue.expiresInSeconds())
            .setCommand(issue.command())
            .build();
    }

    @PostMapping("/two-factor/request")
    public Staff2faPendingApprovalResponse getTwoFactorRequest(@RequestBody Staff2faVerifyRequest body, HttpServletRequest request) {
        StaffTwoFactorService.PendingApproval pending = staffTwoFactorService
            .pendingApproval(RequestUtil.getRequestServer(request), body.getToken(), RequestUtil.requireSessionEmail(request))
            .orElseThrow(PanelAccountController::invalidTwoFactorLink);
        return Staff2faPendingApprovalResponse.newBuilder()
            .setIp(addressValue(pending.ip()))
            .setRequestedAt(pending.requestedAt())
            .build();
    }

    @PostMapping("/two-factor/verify")
    public Staff2faVerifyResponse verifyTwoFactor(@RequestBody Staff2faVerifyRequest body, HttpServletRequest request) {
        if (!staffTwoFactorService.verify(RequestUtil.getRequestServer(request), body.getToken(), RequestUtil.requireSessionEmail(request))) {
            throw invalidTwoFactorLink();
        }
        return Staff2faVerifyResponse.newBuilder().setStatus(VERIFIED).build();
    }

    @DeleteMapping("/minecraft-link")
    public ResponseEntity<Void> unlinkMinecraftAccount(HttpServletRequest request) {
        minecraftAccountLinkService.unlink(RequestUtil.getRequestServer(request), RequestUtil.requireSessionEmail(request));
        return ResponseEntity.noContent().build();
    }

    private static ResourceNotFoundException invalidTwoFactorLink() {
        return new ResourceNotFoundException(
            "This verification link is invalid, expired, already used, or belongs to another staff account", "TWO_FACTOR_LINK_INVALID");
    }
}
