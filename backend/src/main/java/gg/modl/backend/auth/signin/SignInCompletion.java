package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.AccountAuthorization;
import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.StaffCredentialRepository;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.IssuedSession;
import gg.modl.backend.auth.session.SessionService;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.totp.SecondFactorMethod;
import gg.modl.backend.auth.totp.SecondFactorVerifier;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.email.EmailAddressUtil;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignInCompletion {
    private static final List<SecondFactorMethod> CODE_METHODS = List.of(SecondFactorMethod.TOTP, SecondFactorMethod.RECOVERY_CODE);

    private final SessionService sessionService;
    private final AccountAuthorization accountAuthorization;
    private final StaffCredentialRepository credentialRepository;
    private final AccountFactors accountFactors;
    private final SignInSettingsService signInSettings;
    private final SecondFactorChallenges challenges;
    private final SecondFactorVerifier secondFactorVerifier;
    private final CredentialAttempts credentialAttempts;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;

    public SignInOutcome complete(Server server, SignInAttempt attempt, SignInClient client) {
        String identity = requireIdentity(attempt.email());
        Decision decision = unitOfWork.write(() -> decide(server, identity, attempt, client));
        return switch (decision) {
            case Decision.Issue issue -> new SignInOutcome.Issued(issue.session());
            case Decision.Challenge challenge -> new SignInOutcome.Challenged(
                challenges.open(identity, attempt.method(), attempt.verifiedPasswordHash(), challenge.methods()));
        };
    }

    public IssuedSession completeChallenge(Server server, String token, String code, SignInClient client) {
        SecondFactorChallenge challenge = challenges.find(token).filter(SecondFactorChallenge::acceptsCode)
            .orElseThrow(AuthFailures::challengeExpired);
        AttemptReservation reservation = credentialAttempts.reserveSecondFactor(challenge.email());
        Optional<IssuedSession> issued = unitOfWork.write(() -> verifyAndIssue(server, challenge, code, client));
        if (issued.isEmpty()) {
            challenges.recordFailure(token);
            throw AuthFailures.secondFactorInvalid();
        }
        reservation.succeeded();
        challenges.close(token);
        return issued.get();
    }

    private Decision decide(Server server, String identity, SignInAttempt attempt, SignInClient client) {
        accountAuthorization.requireAuthorizedForUpdate(server, identity);
        requireUnchangedPassword(identity, attempt.verifiedPasswordHash());
        if (attempt.method() == AuthMethod.PASSKEY) {
            return new Decision.Issue(createSession(server, identity, attempt.method(), true, client));
        }
        if (accountFactors.totpEnabled(identity)) {
            return new Decision.Challenge(codeMethodsFor(identity));
        }
        if (signInSettings.stored().secondFactorRequired() && accountFactors.passkeyCount(identity) > 0) {
            return new Decision.Challenge(List.of(SecondFactorMethod.PASSKEY));
        }
        return new Decision.Issue(createSession(server, identity, attempt.method(), false, client));
    }

    private Optional<IssuedSession> verifyAndIssue(Server server, SecondFactorChallenge challenge, String code, SignInClient client) {
        accountAuthorization.requireAuthorizedForUpdate(server, challenge.email());
        requireUnchangedPassword(challenge.email(), challenge.expectedPasswordHash());
        Optional<SecondFactorMethod> verified = secondFactorVerifier.verify(challenge.email(), code);
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        if (verified.get() == SecondFactorMethod.RECOVERY_CODE) {
            panelActionAuditor.recordStaffAction(server, challenge.email(), "Signed in with a two-factor recovery code");
        }
        return Optional.of(createSession(server, challenge.email(), challenge.method(), true, client));
    }

    private List<SecondFactorMethod> codeMethodsFor(String identity) {
        if (accountFactors.passkeyCount(identity) == 0) {
            return CODE_METHODS;
        }
        return List.of(SecondFactorMethod.TOTP, SecondFactorMethod.RECOVERY_CODE, SecondFactorMethod.PASSKEY);
    }

    private IssuedSession createSession(Server server, String identity, AuthMethod method, boolean verified, SignInClient client) {
        return sessionService.createSession(server, identity, method, verified, client.ipAddress(), client.userAgent());
    }

    private void requireUnchangedPassword(String identity, @Nullable String expectedHash) {
        if (expectedHash == null) {
            return;
        }
        boolean unchanged = credentialRepository.findPasswordHash(identity).filter(stored -> Objects.equals(stored, expectedHash)).isPresent();
        if (!unchanged) {
            throw AuthFailures.invalidCredentials();
        }
    }

    private static String requireIdentity(String email) {
        String identity = EmailAddressUtil.normalize(email);
        if (identity == null) {
            throw new UnauthorizedException("This account no longer has access to the panel");
        }
        return identity;
    }

    private sealed interface Decision {
        record Issue(IssuedSession session) implements Decision {
        }

        record Challenge(List<SecondFactorMethod> methods) implements Decision {
        }
    }
}
