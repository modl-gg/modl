package gg.modl.backend.auth.signin;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.AuthService;
import gg.modl.backend.auth.password.PasswordPolicy;
import gg.modl.backend.auth.password.PasswordVerification;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.totp.SecondFactorVerifier;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Reauthentication {
    static final Duration FRESH_SESSION = Duration.ofMinutes(15);

    private final PasswordVerification passwordVerification;
    private final AccountFactors accountFactors;
    private final SecondFactorVerifier secondFactorVerifier;
    private final CredentialAttempts credentialAttempts;
    private final AuthService authService;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public void require(AuthSessionData session, SensitiveOperation operation, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        switch (operation) {
            case CHANGE_PASSWORD -> {
                if (!isFresh(session) || session.getAuthMethod() == AuthMethod.PASSWORD) {
                    requirePassword(email, proof, client);
                }
            }
            case MANAGE_TOTP -> {
                if (!isFresh(session) || session.getAuthMethod() != AuthMethod.PASSKEY) {
                    requireCode(email, proof);
                }
            }
            case ADD_CREDENTIAL -> requireForNewCredential(session, proof, client);
        }
    }

    public EmailChangeProof emailChangeProof(String email) {
        if (accountFactors.totpEnabled(email)) {
            return EmailChangeProof.AUTHENTICATOR;
        }
        return accountFactors.hasPassword(email) ? EmailChangeProof.PASSWORD : EmailChangeProof.CURRENT_ADDRESS_CODE;
    }

    public void requireForEmailChange(Server server, AuthSessionData session, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        switch (emailChangeProof(email)) {
            case AUTHENTICATOR -> requireCode(email, proof);
            case PASSWORD -> requirePassword(email, proof, client);
            case CURRENT_ADDRESS_CODE -> requireCurrentAddressCode(server, email, proof);
        }
    }

    private void requireCurrentAddressCode(Server server, String email, CredentialProof proof) {
        String code = proof.secondFactorCode().orElseThrow(AuthFailures::reauthenticationRequired);
        if (!authService.verifyCode(server, email, code)) {
            throw AuthFailures.secondFactorInvalid();
        }
    }

    private void requireForNewCredential(AuthSessionData session, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        boolean totpEnabled = accountFactors.totpEnabled(email);
        boolean hasPassword = accountFactors.hasPassword(email);
        if (isFresh(session) || (!totpEnabled && !hasPassword)) {
            return;
        }
        if (totpEnabled) {
            requireCode(email, proof);
        } else {
            requirePassword(email, proof, client);
        }
    }

    boolean isFresh(AuthSessionData session) {
        return session.getCreatedAt() != null && !session.getCreatedAt().plus(FRESH_SESSION).isBefore(clock.instant());
    }

    private void requirePassword(String email, CredentialProof proof, SignInClient client) {
        String presented = proof.password().orElseThrow(AuthFailures::reauthenticationRequired);
        passwordVerification.verify(email, PasswordPolicy.normalize(presented), client, () -> true,
            AuthFailures::currentPasswordInvalid);
    }

    private void requireCode(String email, CredentialProof proof) {
        String code = proof.secondFactorCode().orElseThrow(AuthFailures::reauthenticationRequired);
        AttemptReservation reservation = credentialAttempts.reserveSecondFactor(email);
        boolean verified = unitOfWork.write(() -> secondFactorVerifier.verify(email, code).isPresent());
        if (!verified) {
            throw AuthFailures.secondFactorInvalid();
        }
        reservation.succeeded();
    }
}
