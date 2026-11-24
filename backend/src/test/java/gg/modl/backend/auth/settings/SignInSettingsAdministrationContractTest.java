package gg.modl.backend.auth.settings;

import static gg.modl.backend.auth.CredentialServices.MOD;
import static gg.modl.backend.auth.CredentialServices.OTHER;
import static gg.modl.backend.auth.CredentialServices.OWNER;
import static gg.modl.backend.auth.CredentialServices.assertRefused;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.CredentialServices;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.database.StaleClientVersionException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

class SignInSettingsAdministrationContractTest {
    private static final SignInSettings PASSWORDS = new SignInSettings(SignInMethod.PASSWORD, false);
    private static final SignInSettings REQUIRED = new SignInSettings(SignInMethod.EMAIL_CODE, true);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aMissingRowMeansDefaultsAndTheOverviewCountsWhoLacksWhat(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            services.addStaff(MOD);
            services.addStaff(OTHER);
            services.addPassword(OWNER);
            services.addPasskey(MOD);

            assertThat(services.administration().overview(services.server()))
                .isEqualTo(new SignInSettingsOverview(SignInSettings.DEFAULTS, 0, 3, 2, 2));
        }
    }

    @SqlContractTest
    void passwordSignInNeedsThatTheOwnerHasAPassword(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.EMAIL_CODE, false);

            assertRefused(() -> update(services, owner, PASSWORDS), AuthFailures.OWNER_PASSWORD_REQUIRED);
            assertThat(services.settings().stored()).isEqualTo(SignInSettings.DEFAULTS);

            services.addPassword(OWNER);
            assertThat(update(services, owner, PASSWORDS).settings()).isEqualTo(PASSWORDS);
        }
    }

    @SqlContractTest
    void theRequirementNeedsAnOwnerFactorAndAVerifiedSession(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData verified = services.addSession(OWNER, "verified", AuthMethod.PASSKEY, true);
            AuthSessionData unverified = services.addSession(OWNER, "unverified", AuthMethod.EMAIL_CODE, false);

            assertRefused(() -> update(services, verified, REQUIRED), AuthFailures.OWNER_SECOND_FACTOR_REQUIRED);

            services.addTotp(OWNER);
            assertRefused(() -> update(services, unverified, REQUIRED), AuthFailures.OWNER_SECOND_FACTOR_REQUIRED);
            assertThat(update(services, verified, REQUIRED).settings()).isEqualTo(REQUIRED);
        }
    }

    @SqlContractTest
    void theSessionIsReReadInsideTheUnit(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.PASSKEY, true);
            services.addTotp(OWNER);
            services.repositories().sessions().clearSecondFactorVerified(OWNER);

            assertRefused(() -> update(services, owner, REQUIRED), AuthFailures.OWNER_SECOND_FACTOR_REQUIRED);
        }
    }

    @SqlContractTest
    void turningTheRequirementOffNeedsNoFactorAndEndsNoSession(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            services.store(SignInMethod.EMAIL_CODE, true);
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.EMAIL_CODE, false);
            services.addSession(MOD, "mod", AuthMethod.EMAIL_CODE, false);

            assertThat(update(services, owner, SignInSettings.DEFAULTS).settings()).isEqualTo(SignInSettings.DEFAULTS);

            assertThat(services.repositories().sessions().findByEmail(OWNER, services.clock().instant())).hasSize(1);
            assertThat(services.repositories().sessions().findByEmail(MOD, services.clock().instant())).hasSize(1);
        }
    }

    @SqlContractTest
    void aStaleVersionIsRefused(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.PASSKEY, true);
            services.addPassword(OWNER);
            long read = services.settings().state().version();
            update(services, owner, PASSWORDS);

            assertThatThrownBy(() -> services.administration().update(services.server(), owner, read, SignInSettings.DEFAULTS))
                .isInstanceOf(StaleClientVersionException.class);
            assertThat(services.settings().stored()).isEqualTo(PASSWORDS);
        }
    }

    @SqlContractTest
    void aChangeIsAuditedWithTheOldAndNewValues(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.PASSKEY, true);
            services.addPassword(OWNER);

            update(services, owner, PASSWORDS);

            verify(services.auditor()).recordStaffAction(services.server(), OWNER,
                "Changed sign-in settings: method EMAIL_CODE to PASSWORD, two-factor requirement off to off");
        }
    }

    @SqlContractTest
    void savingTheSameValuesIsNotAudited(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.EMAIL_CODE, false);

            update(services, owner, SignInSettings.DEFAULTS);

            verifyNoInteractions(services.auditor());
        }
    }

    @SqlContractTest
    void theCachedSnapshotFollowsACommittedChangeUnderARetry(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory, new RetryInjection())) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.PASSKEY, true);
            services.addPassword(OWNER);
            assertThat(services.settings().current()).isEqualTo(SignInSettings.DEFAULTS);

            update(services, owner, PASSWORDS);

            assertThat(services.settings().current()).isEqualTo(PASSWORDS);
        }
    }

    @SqlContractTest
    void aFailedAuditChangesNeitherTheRowNorTheSnapshot(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            AuthSessionData owner = services.addSession(OWNER, "owner", AuthMethod.PASSKEY, true);
            services.addPassword(OWNER);
            assertThat(services.settings().current()).isEqualTo(SignInSettings.DEFAULTS);
            doThrow(new IllegalStateException("audit failed")).when(services.auditor()).recordStaffAction(any(), any(), any());

            assertThatThrownBy(() -> update(services, owner, PASSWORDS)).hasMessageContaining("audit failed");

            assertThat(services.settings().stored()).isEqualTo(SignInSettings.DEFAULTS);
            assertThat(services.settings().current()).isEqualTo(SignInSettings.DEFAULTS);
        }
    }

    private static SignInSettingsOverview update(CredentialServices services, AuthSessionData session, SignInSettings requested) {
        return services.administration().update(services.server(), session, services.settings().state().version(), requested);
    }
}
