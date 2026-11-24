package gg.modl.backend.auth.controller;

import static gg.modl.backend.infrastructure.proto.ProtoMapperSupport.stringValue;

import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.PanelSessionIssuer;
import gg.modl.backend.auth.signin.SignInAttempt;
import gg.modl.backend.auth.signin.SignInClientResolver;
import gg.modl.backend.auth.signin.SignInCompletion;
import gg.modl.backend.auth.signin.SignInOutcome;
import gg.modl.backend.server.data.Server;
import gg.modl.proto.modl.v1.PanelAuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SignInResponder {
    static final String SECOND_FACTOR_REQUIRED_MESSAGE = "Confirm the sign-in with your second factor.";

    private final SignInCompletion signInCompletion;
    private final SignInClientResolver signInClientResolver;
    private final PanelSessionIssuer panelSessionIssuer;

    public PanelAuthResponse signIn(Server server, SignInAttempt attempt, String message, HttpServletRequest request,
                                    HttpServletResponse response) {
        SignInOutcome outcome = signInCompletion.complete(server, attempt, signInClientResolver.resolve(request));
        return respond(server, outcome, message, request, response);
    }

    public PanelAuthResponse respond(Server server, SignInOutcome outcome, String message, HttpServletRequest request,
                                     HttpServletResponse response) {
        return switch (outcome) {
            case SignInOutcome.Issued issued -> issue(server, issued.session(), message, request, response);
            case SignInOutcome.Challenged challenged -> PanelAuthResponse.newBuilder()
                .setSuccess(false)
                .setMessage(SECOND_FACTOR_REQUIRED_MESSAGE)
                .setSecondFactor(SignInProtoMapper.toProto(challenged.challenge()))
                .build();
        };
    }

    public PanelAuthResponse issue(Server server, IssuedSession session, String message, HttpServletRequest request,
                                   HttpServletResponse response) {
        panelSessionIssuer.publish(server, session, request, response);
        return PanelAuthResponse.newBuilder()
            .setSuccess(true)
            .setMessage(stringValue(message))
            .setSessionKey(session.sessionKey())
            .build();
    }
}
