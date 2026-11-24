package gg.modl.backend.auth.controller;

import gg.modl.backend.auth.AuthResponseMessage;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.auth.signin.SignInCompletion;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import gg.modl.backend.infrastructure.rest.RequestUtil;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.CompleteSecondFactorRequest;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SecondFactorSignInController {
    private final SignInCompletion signInCompletion;
    private final SignInClientResolver signInClientResolver;
    private final SignInResponder signInResponder;

    @PostMapping(RESTMappingV1.PANEL_AUTH_SECOND_FACTOR_SIGN_IN)
    public PanelAuthResponse complete(@RequestBody CompleteSecondFactorRequest body, HttpServletRequest request,
                                      HttpServletResponse response) {
        Server server = RequestUtil.getRequestServer(request);
        IssuedSession issued = signInCompletion.completeChallenge(server, body.getChallenge(), body.getCode(),
            signInClientResolver.resolve(request));
        return signInResponder.issue(server, issued, AuthResponseMessage.LOGIN_SUCCESS, request, response);
    }
}
