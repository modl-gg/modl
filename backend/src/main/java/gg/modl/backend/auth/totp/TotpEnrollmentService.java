package gg.modl.backend.auth.totp;

import gg.modl.backend.auth.AccountAuthorization;
import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.repository.PanelSessionRepository;
import gg.modl.backend.auth.repository.StaffTotp;
import gg.modl.backend.auth.repository.StaffTotpRepository;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.auth.signin.AttemptReservation;
import gg.modl.backend.auth.signin.CredentialAttempts;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.signin.SensitiveOperation;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.server.data.Server;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TotpEnrollmentService {
    static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StaffTotpRepository totpRepository;
    private final PanelSessionRepository sessionRepository;
    private final AccountFactors accountFactors;
    private final Reauthentication reauthentication;
    private final AccountAuthorization accountAuthorization;
    private final CredentialAttempts credentialAttempts;
    private final RecoveryCodes recoveryCodes;
    private final PanelActionAuditor panelActionAuditor;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public TotpEnrollment start(Server server, AuthSessionData session, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        reauthentication.require(session, manageOrAdd(email), proof, client);
        byte[] secretBytes = new byte[Totp.SECRET_BYTES];
        RANDOM.nextBytes(secretBytes);
        String secret = Base32.encode(secretBytes);
        Instant now = clock.instant();
        unitOfWork.write(() -> totpRepository.savePending(email, secret, now.plus(ENROLLMENT_LIFETIME), now));
        return new TotpEnrollment(secret, TotpKeyUri.of(server.getServerName(), email, secret), ENROLLMENT_LIFETIME.toSeconds());
    }

    public List<String> confirm(Server server, AuthSessionData session, String code) {
        String email = session.getEmail();
        AttemptReservation reservation = credentialAttempts.reserveSecondFactor(email);
        List<String> codes = recoveryCodes.generate();
        List<String> codeHashes = recoveryCodes.hashAll(codes);
        unitOfWork.write(() -> {
            accountAuthorization.requireAuthorizedForUpdate(server, email);
            Instant now = clock.instant();
            StaffTotp totp = totpRepository.find(email).orElseThrow(AuthFailures::totpEnrollmentExpired);
            String pending = totp.pendingSecretAt(now).orElseThrow(AuthFailures::totpEnrollmentExpired);
            OptionalLong step = Totp.matchingStep(Base32.decode(pending), code.strip(), now);
            if (step.isEmpty() || !totpRepository.enablePending(email, pending, step.getAsLong(), now)) {
                throw AuthFailures.secondFactorInvalid();
            }
            totpRepository.replaceRecoveryCodes(email, codeHashes, now);
            sessionRepository.markSecondFactorVerified(session.publicId());
            sessionRepository.deleteByEmailExcept(email, session.publicId());
            panelActionAuditor.recordStaffAction(server, email,
                totp.enabled() ? "Replaced their authenticator app" : "Turned on an authenticator app");
        });
        reservation.succeeded();
        return codes;
    }

    public void disable(Server server, AuthSessionData session, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        requireEnabled(email);
        reauthentication.require(session, SensitiveOperation.MANAGE_TOTP, proof, client);
        unitOfWork.write(() -> {
            if (!totpRepository.isEnabled(email)) {
                throw AuthFailures.totpNotEnabled();
            }
            totpRepository.delete(email);
            accountFactors.afterFactorRemoved(email);
            panelActionAuditor.recordStaffAction(server, email, "Turned off their authenticator app");
        });
    }

    public List<String> regenerateRecoveryCodes(Server server, AuthSessionData session, CredentialProof proof, SignInClient client) {
        String email = session.getEmail();
        requireEnabled(email);
        reauthentication.require(session, SensitiveOperation.MANAGE_TOTP, proof, client);
        List<String> codes = recoveryCodes.generate();
        List<String> codeHashes = recoveryCodes.hashAll(codes);
        unitOfWork.write(() -> {
            if (!totpRepository.isEnabled(email)) {
                throw AuthFailures.totpNotEnabled();
            }
            totpRepository.replaceRecoveryCodes(email, codeHashes, clock.instant());
            panelActionAuditor.recordStaffAction(server, email, "Created new two-factor recovery codes");
        });
        return codes;
    }

    private SensitiveOperation manageOrAdd(String email) {
        return totpRepository.isEnabled(email) ? SensitiveOperation.MANAGE_TOTP : SensitiveOperation.ADD_CREDENTIAL;
    }

    private void requireEnabled(String email) {
        if (!totpRepository.isEnabled(email)) {
            throw AuthFailures.totpNotEnabled();
        }
    }
}
