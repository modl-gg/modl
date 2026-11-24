package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.password.PasswordResetService;
import gg.modl.backend.auth.password.PasswordSignInService;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.auth.signin.SignInOutcome;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.InspectPasswordResetRequest;
import gg.modl.proto.modl.v1.InspectPasswordResetResponse;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import gg.modl.proto.modl.v1.PanelPasswordLoginRequest;
import gg.modl.proto.modl.v1.ResetPasswordRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PasswordAuthController {
    private final PasswordSignInService passwordSignInService;
    private final PasswordResetService passwordResetService;
    private final SignInResponder signInResponder;
    private final SignInClientResolver signInClientResolver;

    @PostMapping(RESTMappingV1.PANEL_AUTH_PASSWORD_LOGIN)
    public PanelAuthResponse login(@RequestBody PanelPasswordLoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Server server = RequestUtil.getRequestServer(request);
        SignInOutcome outcome = passwordSignInService.signIn(server, body.getEmail(), body.getPassword(), signInClientResolver.resolve(request));
        return signInResponder.respond(server, outcome, AuthResponseMessage.LOGIN_SUCCESS, request, response);
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_PASSWORD_RESET_INSPECT)
    public InspectPasswordResetResponse inspectReset(@RequestBody InspectPasswordResetRequest body, HttpServletRequest request) {
        return SignInProtoMapper.toProto(passwordResetService.inspect(RequestUtil.getRequestServer(request), body.getToken()));
    }

    @PostMapping(RESTMappingV1.PANEL_AUTH_PASSWORD_RESET)
    public PanelAuthResponse reset(@RequestBody ResetPasswordRequest body, HttpServletRequest request) {
        passwordResetService.reset(RequestUtil.getRequestServer(request), body.getToken(), body.getPassword());
        return PanelAuthProtoMapper.toAuthResponse(true, AuthResponseMessage.PASSWORD_RESET);
    }
}
