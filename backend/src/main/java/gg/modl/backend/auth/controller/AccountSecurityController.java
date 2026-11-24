package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.password.PasswordChangeService;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.AccountSecurityService;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.auth.totp.TotpEnrollmentService;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.proto.modl.v1.AccountSecurityResponse;
import gg.modl.proto.modl.v1.ConfirmTotpRequest;
import gg.modl.proto.modl.v1.DisableTotpRequest;
import gg.modl.proto.modl.v1.RecoveryCodesResponse;
import gg.modl.proto.modl.v1.RegenerateRecoveryCodesRequest;
import gg.modl.proto.modl.v1.SetPasswordRequest;
import gg.modl.proto.modl.v1.StartTotpEnrollmentRequest;
import gg.modl.proto.modl.v1.TotpEnrollmentResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AccountSecurityController {
    private final AccountSecurityService accountSecurityService;
    private final PasswordChangeService passwordChangeService;
    private final TotpEnrollmentService totpEnrollmentService;
    private final SignInClientResolver signInClientResolver;

    @GetMapping(RESTMappingV1.PANEL_AUTH_SECURITY)
    public AccountSecurityResponse security(HttpServletRequest request) {
        return describe(request);
    }

    @PutMapping(RESTMappingV1.PANEL_AUTH_PASSWORD)
    public AccountSecurityResponse setPassword(@RequestBody SetPasswordRequest body, HttpServletRequest request) {
        passwordChangeService.setOwnPassword(RequestUtil.getRequestServer(request), RequestUtil.requireSession(request),
            body.getNewPassword(), SignInProtoMapper.fromProto(body.getProof()), client(request));
        return describe(request);
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_TOTP)
    public TotpEnrollmentResponse startTotp(@RequestBody StartTotpEnrollmentRequest body, HttpServletRequest request) {
        return SignInProtoMapper.toProto(totpEnrollmentService.start(RequestUtil.getRequestServer(request), RequestUtil.requireSession(request),
            SignInProtoMapper.fromProto(body.getProof()), client(request)));
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_TOTP_CONFIRM)
    public RecoveryCodesResponse confirmTotp(@RequestBody ConfirmTotpRequest body, HttpServletRequest request) {
        return SignInProtoMapper.toRecoveryCodes(totpEnrollmentService.confirm(RequestUtil.getRequestServer(request),
            RequestUtil.requireSession(request), body.getCode()));
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_TOTP_DISABLE)
    public AccountSecurityResponse disableTotp(@RequestBody DisableTotpRequest body, HttpServletRequest request) {
        totpEnrollmentService.disable(RequestUtil.getRequestServer(request), RequestUtil.requireSession(request),
            SignInProtoMapper.fromProto(body.getProof()), client(request));
        return describe(request);
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_TOTP_RECOVERY_CODES)
    public RecoveryCodesResponse regenerateRecoveryCodes(@RequestBody RegenerateRecoveryCodesRequest body, HttpServletRequest request) {
        return SignInProtoMapper.toRecoveryCodes(totpEnrollmentService.regenerateRecoveryCodes(RequestUtil.getRequestServer(request),
            RequestUtil.requireSession(request), SignInProtoMapper.fromProto(body.getProof()), client(request)));
    }

    private AccountSecurityResponse describe(HttpServletRequest request) {
        AuthSessionData session = RequestUtil.requireSession(request);
        return SignInProtoMapper.toProto(accountSecurityService.describe(session));
    }

    private SignInClient client(HttpServletRequest request) {
        return signInClientResolver.resolve(request);
    }
}
