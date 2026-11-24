package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.infrastructure.rest.ClassifiedRoute;
import gg.modl.backend.infrastructure.rest.RESTMappingV1;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@RequiredArgsConstructor
public class SecondFactorPolicy {
    private static final Set<String> RESTRICTED_ALLOWLIST = Set.of(
        route("GET", RESTMappingV1.PANEL_AUTH_ME),
        route("GET", RESTMappingV1.PANEL_AUTH_SECURITY),
        route("POST", RESTMappingV1.PANEL_AUTH_LOGOUT),
        route("DELETE", RESTMappingV1.PANEL_AUTH_SESSIONS));
    private static final Set<String> ENROLLMENT_ROUTES = Set.of(
        route("POST", RESTMappingV1.PANEL_AUTH_TOTP),
        route("POST", RESTMappingV1.PANEL_AUTH_TOTP_CONFIRM),
        route("POST", RESTMappingV1.PANEL_AUTH_WEBAUTHN_REGISTER_OPTIONS),
        route("POST", RESTMappingV1.PANEL_AUTH_WEBAUTHN_REGISTER_VERIFY));
    private static final PathContainer.Options DECODED_PATH = PathContainer.Options.create('/', false);
    private static final List<PathPattern> ANONYMOUS_ROUTES = RESTMappingV1.PANEL_AUTH_ANONYMOUS_ENDPOINTS.stream()
        .map(PathPatternParser.defaultInstance::parse)
        .toList();

    private final SignInSettingsService signInSettings;
    private final AccountFactors accountFactors;

    public SecondFactorState state(AuthSessionData session) {
        return state(session.getEmail(), session.isSecondFactorVerified());
    }

    public SecondFactorState state(String email, boolean sessionVerified) {
        if (sessionVerified || !signInSettings.current().secondFactorRequired()) {
            return SecondFactorState.SATISFIED;
        }
        return accountFactors.hasSecondFactor(email)
            ? SecondFactorState.VERIFICATION_REQUIRED
            : SecondFactorState.ENROLLMENT_REQUIRED;
    }

    public boolean admitsEverywhere(AuthSessionData session) {
        return state(session) == SecondFactorState.SATISFIED;
    }

    public SecondFactorDecision decide(AuthSessionData session, ClassifiedRoute route, String method) {
        SecondFactorState state = state(session);
        if (state == SecondFactorState.SATISFIED) {
            return SecondFactorDecision.ADMIT;
        }
        if (!route.isPanelArea()) {
            return SecondFactorDecision.ANONYMOUS;
        }
        String request = route(method, route.path());
        if (RESTRICTED_ALLOWLIST.contains(request)) {
            return SecondFactorDecision.ADMIT;
        }
        if (state == SecondFactorState.ENROLLMENT_REQUIRED && ENROLLMENT_ROUTES.contains(request)) {
            return SecondFactorDecision.ADMIT;
        }
        if (isAnonymousRoute(route.path())) {
            return SecondFactorDecision.ANONYMOUS;
        }
        return state == SecondFactorState.ENROLLMENT_REQUIRED
            ? SecondFactorDecision.ENROLLMENT_REQUIRED
            : SecondFactorDecision.VERIFICATION_REQUIRED;
    }

    private static boolean isAnonymousRoute(String path) {
        PathContainer container = PathContainer.parsePath(path, DECODED_PATH);
        return ANONYMOUS_ROUTES.stream().anyMatch(pattern -> pattern.matches(container));
    }

    private static String route(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }
}
