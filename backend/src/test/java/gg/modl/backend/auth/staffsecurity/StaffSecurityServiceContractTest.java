package gg.modl.backend.auth.staffsecurity;

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
import gg.modl.backend.auth.data.WebAuthnChallenge;
import gg.modl.backend.auth.password.PasswordResetLink;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class StaffSecurityServiceContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theOwnerIsListedFirstWithoutAStaffRowAndEveryFactorIsShown(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            Staff other = services.addStaff(OTHER);
            services.addPassword(OWNER);
            services.addPassword(MOD);
            services.addTotp(MOD);
            services.addPasskey(OTHER);

            List<StaffSecurityEntry> entries = services.staffSecurity().list(services.server());

            assertThat(entries.getFirst()).isEqualTo(new StaffSecurityEntry(null, OWNER, true, false, false, true));
            assertThat(entries).hasSize(3).contains(
                new StaffSecurityEntry(mod.getId(), MOD, true, true, false, false),
                new StaffSecurityEntry(other.getId(), OTHER, false, false, true, false));
        }
    }

    @SqlContractTest
    void anOwnerWithAStaffRowIsListedOnce(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff owner = services.addStaff(OWNER);

            assertThat(services.staffSecurity().list(services.server()))
                .containsExactly(new StaffSecurityEntry(owner.getId(), OWNER, false, false, false, true));
        }
    }

    @SqlContractTest
    void theOwnersOwnAccountCannotBeReset(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff owner = services.addStaff(OWNER);
            services.store(SignInMethod.PASSWORD, false);
            services.addTotp(OWNER);

            assertRefused(() -> services.staffSecurity().createPasswordResetLink(services.server(), OWNER, owner.getId()),
                AuthFailures.OWNER_ACCOUNT_PROTECTED);
            assertRefused(() -> services.staffSecurity().resetSecondFactor(services.server(), OWNER, owner.getId()),
                AuthFailures.OWNER_ACCOUNT_PROTECTED);

            assertThat(services.repositories().totp().isEnabled(OWNER)).isTrue();
            verifyNoInteractions(services.auditor());
        }
    }

    @SqlContractTest
    void anUnknownStaffMemberIsNotFound(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            assertThatThrownBy(() -> services.staffSecurity().resetSecondFactor(services.server(), OWNER, "nobody"))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> services.staffSecurity().createPasswordResetLink(services.server(), OWNER, "nobody"))
                .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @SqlContractTest
    void aResetLinkIsCreatedForThatStaffMember(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            services.store(SignInMethod.PASSWORD, false);

            PasswordResetLink link = services.staffSecurity().createPasswordResetLink(services.server(), OWNER, mod.getId());

            assertThat(link.email()).isEqualTo(MOD);
            verify(services.auditor()).recordStaffAction(services.server(), OWNER, "Created a password reset link for " + MOD);
        }
    }

    @SqlContractTest
    void resettingASecondFactorEndsEveryWayPastItAndKeepsThePassword(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory, new RetryInjection())) {
            Staff mod = services.addStaff(MOD);
            Instant now = services.clock().instant();
            for (String email : List.of(MOD, OTHER)) {
                services.addPassword(email);
                services.addTotp(email);
                services.addPasskey(email);
                services.addLoginToken(email);
                services.addSession(email, "session-" + email, AuthMethod.PASSKEY, true);
                services.repositories().totp().replaceRecoveryCodes(email, List.of("recovery"), now);
                services.repositories().challenges().insert(
                    new WebAuthnChallenge("challenge-" + email, "{}", email, now.plusSeconds(600)));
            }

            services.staffSecurity().resetSecondFactor(services.server(), OWNER, mod.getId());

            assertThat(services.factors().hasSecondFactor(MOD)).isFalse();
            assertThat(services.repositories().totp().countRecoveryCodes(MOD)).isZero();
            assertThat(services.repositories().challenges().consumeActive("challenge-" + MOD, now)).isEmpty();
            assertThat(services.repositories().sessions().findByEmail(MOD, now)).isEmpty();
            assertThat(services.hasLoginToken(MOD)).isFalse();
            assertThat(services.repositories().passwords().findPasswordHash(MOD)).isPresent();
            assertThat(services.factors().passkeyCount(OTHER)).isEqualTo(1);
            assertThat(services.factors().totpEnabled(OTHER)).isTrue();
            assertThat(services.repositories().sessions().findByEmail(OTHER, now)).hasSize(1);
            assertThat(services.hasLoginToken(OTHER)).isTrue();
        }
    }

    @SqlContractTest
    void aFailedAuditLeavesTheSecondFactorInPlace(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            services.addTotp(MOD);
            services.addSession(MOD, "mod-session", AuthMethod.PASSWORD, true);
            doThrow(new IllegalStateException("audit failed")).when(services.auditor()).recordStaffAction(any(), any(), any());

            assertThatThrownBy(() -> services.staffSecurity().resetSecondFactor(services.server(), OWNER, mod.getId()))
                .hasMessageContaining("audit failed");

            assertThat(services.repositories().totp().isEnabled(MOD)).isTrue();
            assertThat(services.repositories().sessions().findByEmail(MOD, services.clock().instant())).hasSize(1);
        }
    }
}
