package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.EmailChangeService;
import gg.modl.backend.auth.LoginCodeRequests;
import gg.modl.backend.auth.link.PanelLoginLinkService;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.PanelSessionIssuer;
import gg.modl.backend.auth.session.SessionPublicId;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.SecondFactorPolicy;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.role.service.RoleAuthorization;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.SupportedLanguages;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.service.StaffLookupCache;
import gg.modl.backend.staff.service.StaffProfileService;
import gg.modl.backend.staff.service.SuperAdminStaffSynthesizer;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.PanelConsumeLoginLinkRequest;
import gg.modl.proto.modl.v1.PanelPermissionsResponse;
import gg.modl.proto.modl.v1.PanelSendEmailCodeRequest;
import gg.modl.proto.modl.v1.PanelUpdateEmailRequest;
import gg.modl.proto.modl.v1.PanelUpdateEmailWithCodeRequest;
import gg.modl.proto.modl.v1.PanelUpdateProfileRequest;
import gg.modl.proto.modl.v1.PanelVerifyEmailCodeRequest;
import gg.modl.proto.modl.v1.SecondFactorState;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(RESTMappingV1.PANEL_AUTH)
@RequiredArgsConstructor
@Slf4j
public class PanelAuthController {
    private final AuthService authService;
    private final SessionService sessionService;
    private final StaffProfileService staffProfileService;
    private final StaffLookupCache staffLookupCache;
    private final PermissionService permissionService;
    private final RoleAuthorization roleAuthorization;
    private final PanelSessionIssuer panelSessionIssuer;
    private final SignInResponder signInResponder;
    private final SignInClientResolver signInClientResolver;
    private final SignInSettingsService signInSettings;
    private final SecondFactorPolicy secondFactorPolicy;
    private final EmailChangeService emailChangeService;
    private final PanelLoginLinkService panelLoginLinkService;
    private final LoginCodeRequests loginCodeRequests;

    @PostMapping("/send-email-code")
    public ResponseEntity<PanelAuthResponse> sendEmailCode(
        HttpServletRequest request,
        @RequestBody PanelSendEmailCodeRequest requestData) {

        signInSettings.requireMethod(SignInMethod.EMAIL_CODE);
        loginCodeRequests.requestLoginCode(RequestUtil.getRequestServer(request), requestData.getEmail());
        return verificationCodeSentResponse();
    }

    @PostMapping("/verify-email-code")
    public ResponseEntity<PanelAuthResponse> verifyEmailCode(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody PanelVerifyEmailCodeRequest requestData) {

        signInSettings.requireMethod(SignInMethod.EMAIL_CODE);
        Server server = RequestUtil.getRequestServer(request);
        boolean valid = authService.verifyCode(server, requestData.getEmail(), requestData.getCode());
        if (!valid || !permissionService.isAuthorizedEmail(server, requestData.getEmail())) {
            return invalidCodeResponse();
        }

        return ResponseEntity.ok(signInResponder.signIn(server, SignInAttempt.of(requestData.getEmail(), AuthMethod.EMAIL_CODE),
            AuthResponseMessage.LOGIN_SUCCESS, request, response));
    }

    @PostMapping("/link")
    public ResponseEntity<PanelAuthResponse> consumeLoginLink(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody PanelConsumeLoginLinkRequest requestData) {

        Server server = RequestUtil.getRequestServer(request);
        String email = panelLoginLinkService.consume(server, requestData.getToken())
            .orElseThrow(() -> new UnauthorizedException("The login link is invalid or has expired"));

        return ResponseEntity.ok(signInResponder.signIn(server, SignInAttempt.of(email, AuthMethod.LOGIN_LINK),
            AuthResponseMessage.LOGIN_SUCCESS, request, response));
    }

    @PostMapping("/logout")
    public ResponseEntity<PanelAuthResponse> logout(HttpServletRequest request, HttpServletResponse response) {
        AuthSessionData session = RequestUtil.getSession(request);
        if (session != null) {
            sessionService.invalidateSession(RequestUtil.getRequestServer(request), session.getId());
        }

        panelSessionIssuer.expire(request, response);

        return ResponseEntity.ok(PanelAuthProtoMapper.toAuthResponse(true, AuthResponseMessage.LOGOUT_SUCCESS));
    }

    @PatchMapping("/profile")
    public ResponseEntity<?> updateProfile(
        HttpServletRequest request,
        @RequestBody PanelUpdateProfileRequest requestData) {

        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        boolean isSuperAdmin = permissionService.isSuperAdmin(server, email);

        String username = requestData.hasUsername() ? requestData.getUsername() : null;
        String language = requestData.hasLanguage() ? requestData.getLanguage() : null;
        String dateFormat = requestData.hasDateFormat() ? requestData.getDateFormat() : null;

        Optional<Staff> result = staffProfileService.updateProfile(server, email, username, language, dateFormat);
        if (result.isEmpty()) {
            if (isSuperAdmin) {
                return superAdminProfileResponse(email, SuperAdminStaffSynthesizer.SUPER_ADMIN_USERNAME,
                    SupportedLanguages.DEFAULT, Staff.DEFAULT_DATE_FORMAT);
            }
            return ResponseEntity.status(404).body(PanelAuthProtoMapper.toAuthResponse(false, "Staff member not found"));
        }
        return staffProfileResponse(server, result.get(), isSuperAdmin);
    }

    @PostMapping("/email/send-code")
    public ResponseEntity<PanelAuthResponse> sendEmailChangeCode(
        HttpServletRequest request,
        @RequestBody PanelUpdateEmailRequest requestData) {

        String currentEmail = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        emailChangeService.sendChangeCode(server, currentEmail, requestData.getNewEmail());

        return ResponseEntity.ok(PanelAuthProtoMapper.toAuthResponse(true, "Verification code sent to new email."));
    }

    @PatchMapping("/email")
    public ResponseEntity<PanelAuthResponse> updateEmail(
        HttpServletRequest request,
        HttpServletResponse response,
        @RequestBody PanelUpdateEmailWithCodeRequest requestData) {

        AuthSessionData session = RequestUtil.requireSession(request);

        Server server = RequestUtil.getRequestServer(request);
        IssuedSession newSession = emailChangeService.changeEmail(
            server, session, requestData.getNewEmail(), requestData.getCode(), SignInProtoMapper.fromProto(requestData.getProof()),
            signInClientResolver.resolve(request));
        panelSessionIssuer.attach(newSession, request, response);

        return ResponseEntity.ok(PanelAuthProtoMapper.toIssuedSessionResponse(AuthResponseMessage.EMAIL_UPDATED, newSession));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest request) {
        AuthSessionData session = RequestUtil.requireSession(request);
        String email = session.getEmail();

        Server server = RequestUtil.getRequestServer(request);
        boolean isSuperAdmin = permissionService.isSuperAdmin(server, email);
        SecondFactorState state = SignInProtoMapper.toProto(secondFactorPolicy.state(session));

        Optional<Staff> staffOpt = staffLookupCache.findByEmail(server, email);

        if (staffOpt.isPresent()) {
            String role = isSuperAdmin ? RoleAuthorization.SUPER_ADMIN_ROLE_NAME : permissionService.effectiveRoleName(server, staffOpt.get());
            return ResponseEntity.ok(PanelAuthProtoMapper.toProfileResponse(staffOpt.get(), role).toBuilder()
                .setSecondFactorState(state).build());
        }

        if (isSuperAdmin) {
            return ResponseEntity.ok(PanelAuthProtoMapper.toSuperAdminProfileResponse(email, SuperAdminStaffSynthesizer.SUPER_ADMIN_USERNAME,
                SupportedLanguages.DEFAULT, Staff.DEFAULT_DATE_FORMAT).toBuilder().setSecondFactorState(state).build());
        }

        return ResponseEntity.status(404).body(PanelAuthProtoMapper.toAuthResponse(false, "Staff member not found"));
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessions(HttpServletRequest request) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        List<PanelSession> sessions = sessionService.findAllSessionsForEmail(server, email);

        return ResponseEntity.ok(PanelAuthProtoMapper.toSessionsResponse(sessions, currentPublicId(request)));
    }

    @DeleteMapping("/sessions/{publicId}")
    public ResponseEntity<PanelAuthResponse> revokeSession(
        HttpServletRequest request,
        HttpServletResponse response,
        @PathVariable String publicId) {

        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        if (!sessionService.revokeOwnSession(server, email, publicId)) {
            return ResponseEntity.status(404).body(PanelAuthProtoMapper.toAuthResponse(false, "Session not found"));
        }

        expireCookiesIfCurrentSession(request, response, publicId);

        return ResponseEntity.ok(PanelAuthProtoMapper.toAuthResponse(true, AuthResponseMessage.LOGOUT_SUCCESS));
    }

    @DeleteMapping("/sessions")
    public ResponseEntity<PanelAuthResponse> revokeAllSessions(HttpServletRequest request, HttpServletResponse response) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        sessionService.invalidateAllSessionsForEmail(server, email);
        panelSessionIssuer.expire(request, response);

        return ResponseEntity.ok(PanelAuthProtoMapper.toAuthResponse(true, AuthResponseMessage.LOGOUT_SUCCESS));
    }

    private void expireCookiesIfCurrentSession(HttpServletRequest request, HttpServletResponse response, String publicId) {
        if (publicId.equals(currentPublicId(request))) {
            panelSessionIssuer.expire(request, response);
        }
    }

    private static String currentPublicId(HttpServletRequest request) {
        return SessionPublicId.of(RequestUtil.requireSession(request).getId());
    }

    private ResponseEntity<PanelAuthResponse> verificationCodeSentResponse() {
        return ResponseEntity.ok(PanelAuthProtoMapper.toAuthResponse(true, AuthResponseMessage.VERIFICATION_CODE_SENT));
    }

    private ResponseEntity<PanelAuthResponse> invalidCodeResponse() {
        return ResponseEntity.badRequest().body(PanelAuthProtoMapper.toAuthResponse(false, AuthResponseMessage.INVALID_CODE));
    }

    private ResponseEntity<?> superAdminProfileResponse(String email, String username, String language, String dateFormat) {
        return ResponseEntity.ok(PanelAuthProtoMapper.toSuperAdminProfileResponse(email, username, language, dateFormat));
    }

    private ResponseEntity<?> staffProfileResponse(Server server, Staff staff, boolean superAdmin) {
        String role = superAdmin ? RoleAuthorization.SUPER_ADMIN_ROLE_NAME : permissionService.effectiveRoleName(server, staff);
        return ResponseEntity.ok(PanelAuthProtoMapper.toProfileResponse(staff, role));
    }

    @GetMapping("/permissions")
    public ResponseEntity<PanelPermissionsResponse> getUserPermissions(HttpServletRequest request) {
        String email = RequestUtil.requireSessionEmail(request);

        Server server = RequestUtil.getRequestServer(request);
        List<String> permissions = roleAuthorization.effectivePermissionIds(server, roleAuthorization.panelPerformer(server, email));

        return ResponseEntity.ok(PanelAuthProtoMapper.toPermissionsResponse(permissions));
    }
}
