package gg.modl.backend.auth.totp;

import static gg.modl.backend.auth.CredentialServices.MOD;
import static gg.modl.backend.auth.CredentialServices.OTHER;
import static gg.modl.backend.auth.CredentialServices.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.CredentialServices;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.signin.CredentialProof;
import gg.modl.backend.auth.signin.SignInClient;
import gg.modl.backend.infrastructure.exception.UnauthorizedException;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class TotpEnrollmentServiceContractTest {
    private static final int SHORT_WINDOW_ATTEMPTS = 5;
    private static final SignInClient CLIENT = SignInClient.unidentified();

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void startingOnlyStoresAPendingSecret(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);

            TotpEnrollment started = start(services, session, CredentialProof.NONE);

            assertThat(started.keyUri()).startsWith("otpauth://totp/").contains("secret=" + started.secret());
            assertThat(started.expiresInSeconds()).isEqualTo(TotpEnrollmentService.ENROLLMENT_LIFETIME.toSeconds());
            assertThat(started.toString()).doesNotContain(started.secret());
            assertThat(services.factors().totpEnabled(MOD)).isFalse();
        }
    }

    @SqlContractTest
    void confirmingEnablesVerifiesThisSessionEndsTheOthersAndIssuesTheCodesOnce(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory, new RetryInjection())) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            services.addSession(MOD, "second-browser", AuthMethod.EMAIL_CODE, false);
            services.addSession(OTHER, "someone-else", AuthMethod.EMAIL_CODE, false);

            List<String> codes = enable(services, session);

            assertThat(codes).hasSize(RecoveryCodes.COUNT).doesNotHaveDuplicates();
            assertThat(services.factors().totpEnabled(MOD)).isTrue();
            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isEqualTo(RecoveryCodes.COUNT);
            assertThat(sessionsOf(services, MOD))
                .singleElement()
                .satisfies(kept -> {
                    assertThat(kept.publicId()).isEqualTo(session.publicId());
                    assertThat(kept.secondFactorVerified()).isTrue();
                });
            assertThat(sessionsOf(services, OTHER)).hasSize(1);
        }
    }

    @SqlContractTest
    void theConfirmationCodeCannotBeReplayed(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            String secret = start(services, session, CredentialProof.NONE).secret();
            String code = currentCode(services, secret);

            services.enrollment().confirm(services.server(), session, code);

            assertThat(services.database().unitOfWork().write(() -> services.verifier().verify(MOD, code))).isEmpty();
        }
    }

    @SqlContractTest
    void anExpiredOrMissingEnrolmentIsRefused(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            assertRefused(() -> services.enrollment().confirm(services.server(), session, "123456"),
                AuthFailures.TOTP_ENROLLMENT_EXPIRED);

            String secret = start(services, session, CredentialProof.NONE).secret();
            services.clock().advance(TotpEnrollmentService.ENROLLMENT_LIFETIME);

            assertRefused(() -> services.enrollment().confirm(services.server(), session, currentCode(services, secret)),
                AuthFailures.TOTP_ENROLLMENT_EXPIRED);
            assertThat(services.factors().totpEnabled(MOD)).isFalse();
        }
    }

    @SqlContractTest
    void wrongCodesChangeNothingAndUseUpTheAttempts(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            String secret = start(services, session, CredentialProof.NONE).secret();
            String wrong = wrongCode(services, secret);

            for (int attempt = 0; attempt < SHORT_WINDOW_ATTEMPTS; attempt++) {
                assertRefused(() -> services.enrollment().confirm(services.server(), session, wrong), AuthFailures.SECOND_FACTOR_INVALID);
            }

            assertRefused(() -> services.enrollment().confirm(services.server(), session, currentCode(services, secret)),
                AuthFailures.TOO_MANY_ATTEMPTS);
            assertThat(services.factors().totpEnabled(MOD)).isFalse();
        }
    }

    @SqlContractTest
    void aFailedAuditLeavesTheEnrolmentPendingAndTheSessionsAlive(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            services.addSession(MOD, "second-browser", AuthMethod.EMAIL_CODE, false);
            String secret = start(services, session, CredentialProof.NONE).secret();
            doThrow(new IllegalStateException("audit failed")).when(services.auditor()).recordStaffAction(any(), any(), any());

            assertThatThrownBy(() -> services.enrollment().confirm(services.server(), session, currentCode(services, secret)))
                .hasMessageContaining("audit failed");

            assertThat(services.factors().totpEnabled(MOD)).isFalse();
            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isZero();
            assertThat(sessionsOf(services, MOD)).hasSize(2);
        }
    }

    @SqlContractTest
    void anAccountThatLostAccessCannotConfirm(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            AuthSessionData session = services.addSession(MOD, "mod", AuthMethod.EMAIL_CODE, false);
            String secret = start(services, session, CredentialProof.NONE).secret();

            services.removeStaff(mod);

            assertThatThrownBy(() -> services.enrollment().confirm(services.server(), session, currentCode(services, secret)))
                .isInstanceOf(UnauthorizedException.class);
            assertThat(services.factors().totpEnabled(MOD)).isFalse();
        }
    }

    @SqlContractTest
    void replacingKeepsTheOldSecretUntilTheNewOneIsConfirmed(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            List<String> codes = enable(services, session);
            String original = storedSecret(services);
            assertRefused(() -> start(services, session, CredentialProof.NONE), AuthFailures.REAUTHENTICATION_REQUIRED);

            String replacement = start(services, session, new CredentialProof(null, codes.getFirst())).secret();
            assertThat(storedSecret(services)).isEqualTo(original);

            services.enrollment().confirm(services.server(), session, currentCode(services, replacement));
            assertThat(storedSecret(services)).isEqualTo(replacement);
            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isEqualTo(RecoveryCodes.COUNT);
        }
    }

    @SqlContractTest
    void anOldSessionOfAnAccountWithAPasswordMustProveItBeforeEnrolling(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.PASSWORD);
            services.addPassword(MOD);
            services.clock().advance(Duration.ofMinutes(16));

            assertRefused(() -> start(services, session, CredentialProof.NONE), AuthFailures.REAUTHENTICATION_REQUIRED);
            assertThat(services.repositories().totp().find(MOD)).isEmpty();
        }
    }

    @SqlContractTest
    void theLastFactorStaysWhileItIsRequired(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.PASSKEY);
            enable(services, session);
            services.store(SignInMethod.EMAIL_CODE, true);

            assertRefused(() -> services.enrollment().disable(services.server(), session, CredentialProof.NONE, CLIENT),
                AuthFailures.LAST_SECOND_FACTOR);

            assertThat(services.factors().totpEnabled(MOD)).isTrue();
            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isEqualTo(RecoveryCodes.COUNT);
        }
    }

    @SqlContractTest
    void disablingKeepsTheVerifiedFlagWhileAPasskeyRemains(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.PASSKEY);
            enable(services, session);
            services.addPasskey(MOD);
            services.store(SignInMethod.EMAIL_CODE, true);

            services.enrollment().disable(services.server(), session, CredentialProof.NONE, CLIENT);

            assertThat(services.factors().totpEnabled(MOD)).isFalse();
            assertThat(sessionsOf(services, MOD)).singleElement().extracting(PanelSession::secondFactorVerified).isEqualTo(true);
        }
    }

    @SqlContractTest
    void disablingTheLastFactorClearsTheVerifiedFlag(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory, new RetryInjection())) {
            AuthSessionData session = modSession(services, AuthMethod.PASSKEY);
            enable(services, session);

            services.enrollment().disable(services.server(), session, CredentialProof.NONE, CLIENT);

            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isZero();
            assertThat(sessionsOf(services, MOD)).singleElement().extracting(PanelSession::secondFactorVerified).isEqualTo(false);
        }
    }

    @SqlContractTest
    void newRecoveryCodesNeedAProofAndReplaceTheOldOnes(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.EMAIL_CODE);
            List<String> old = enable(services, session);
            assertRefused(() -> services.enrollment().regenerateRecoveryCodes(services.server(), session, CredentialProof.NONE, CLIENT),
                AuthFailures.REAUTHENTICATION_REQUIRED);

            List<String> fresh = services.enrollment().regenerateRecoveryCodes(
                services.server(), session, new CredentialProof(null, old.getFirst()), CLIENT);

            assertThat(fresh).hasSize(RecoveryCodes.COUNT).doesNotContainAnyElementsOf(old);
            assertThat(services.database().unitOfWork().write(() -> services.verifier().verify(MOD, old.getLast()))).isEmpty();
            assertThat(services.database().unitOfWork().write(() -> services.verifier().verify(MOD, fresh.getFirst())))
                .contains(SecondFactorMethod.RECOVERY_CODE);
        }
    }

    @SqlContractTest
    void withoutAnAuthenticatorThereIsNothingToDisableOrRegenerate(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData session = modSession(services, AuthMethod.PASSKEY);

            assertRefused(() -> services.enrollment().disable(services.server(), session, CredentialProof.NONE, CLIENT),
                AuthFailures.TOTP_NOT_ENABLED);
            assertRefused(() -> services.enrollment().regenerateRecoveryCodes(services.server(), session, CredentialProof.NONE, CLIENT),
                AuthFailures.TOTP_NOT_ENABLED);
        }
    }

    private static AuthSessionData modSession(CredentialServices services, AuthMethod method) {
        services.addStaff(MOD);
        return services.addSession(MOD, "mod", method, method == AuthMethod.PASSKEY);
    }

    private static TotpEnrollment start(CredentialServices services, AuthSessionData session, CredentialProof proof) {
        return services.enrollment().start(services.server(), session, proof, CLIENT);
    }

    private static List<String> enable(CredentialServices services, AuthSessionData session) {
        String secret = start(services, session, CredentialProof.NONE).secret();
        return services.enrollment().confirm(services.server(), session, currentCode(services, secret));
    }

    private static List<PanelSession> sessionsOf(CredentialServices services, String email) {
        return services.repositories().sessions().findByEmail(email, services.clock().instant());
    }

    private static String storedSecret(CredentialServices services) {
        return services.repositories().totp().find(MOD).orElseThrow().secret();
    }

    private static String currentCode(CredentialServices services, String secret) {
        return Totp.codeAt(Base32.decode(secret), Totp.stepAt(services.clock().instant()));
    }

    private static String wrongCode(CredentialServices services, String secret) {
        byte[] key = Base32.decode(secret);
        Instant now = services.clock().instant();
        return IntStream.range(0, 10)
            .mapToObj(candidate -> String.format("%06d", candidate))
            .filter(candidate -> Totp.matchingStep(key, candidate, now).isEmpty())
            .findFirst()
            .orElseThrow();
    }
}
