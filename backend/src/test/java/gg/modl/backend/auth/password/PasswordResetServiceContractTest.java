package gg.modl.backend.auth.password;

import static gg.modl.backend.auth.CredentialServices.HASH_PREFIX;
import static gg.modl.backend.auth.CredentialServices.MOD;
import static gg.modl.backend.auth.CredentialServices.OTHER;
import static gg.modl.backend.auth.CredentialServices.OWNER;
import static gg.modl.backend.auth.CredentialServices.PANEL;
import static gg.modl.backend.auth.CredentialServices.assertRefused;
import static gg.modl.backend.database.jooq.Tables.PASSWORD_RESET_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import gg.modl.backend.auth.AuthFailures;
import gg.modl.backend.auth.CredentialServices;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;

class PasswordResetServiceContractTest {
    private static final String NEW_PASSWORD = "a brand new password";
    private static final String TOKEN_FRAGMENT = "token=";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theTokenTravelsOnlyInTheFragmentAndOnlyItsHashIsStored(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            PasswordResetLink link = linkFor(services, services.addStaff(MOD));

            assertThat(link.url()).startsWith(PANEL + "/reset-password#" + TOKEN_FRAGMENT);
            assertThat(URI.create(link.url()).getQuery()).isNull();
            assertThat(link.expiresAt()).isEqualTo(services.clock().instant().plus(PasswordResetService.LINK_LIFETIME));
            assertThat(link.toString()).doesNotContain(tokenOf(link));
            assertThat(services.database().dsl().fetchValues(PASSWORD_RESET_TOKENS.TOKEN_HASH))
                .hasSize(1).doesNotContain(tokenOf(link));
            assertThat(services.resets().inspect(services.server(), tokenOf(link)).email()).isEqualTo(MOD);
        }
    }

    @SqlContractTest
    void aNewLinkReplacesTheLiveOne(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            String first = tokenOf(linkFor(services, mod));
            String second = tokenOf(services.resets().createFor(services.server(), OWNER, mod));

            assertRefused(() -> services.resets().inspect(services.server(), first), AuthFailures.RESET_LINK_INVALID);
            assertThat(services.resets().inspect(services.server(), second).email()).isEqualTo(MOD);
        }
    }

    @SqlContractTest
    void aLinkSetsThePasswordOnceAndEndsSessionsAndLoginLinksButKeepsFactors(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory, new RetryInjection())) {
            Staff mod = services.addStaff(MOD);
            services.addTotp(MOD);
            services.addPasskey(MOD);
            services.addLoginToken(MOD);
            services.addLoginToken(OTHER);
            services.addSession(MOD, "mod-session", AuthMethod.PASSKEY, true);
            services.addSession(OTHER, "other-session", AuthMethod.PASSKEY, true);
            String token = tokenOf(linkFor(services, mod));

            services.resets().reset(services.server(), token, NEW_PASSWORD);

            assertThat(services.repositories().passwords().findPasswordHash(MOD)).contains(HASH_PREFIX + NEW_PASSWORD);
            assertThat(services.repositories().sessions().findByEmail(MOD, services.clock().instant())).isEmpty();
            assertThat(services.repositories().sessions().findByEmail(OTHER, services.clock().instant())).hasSize(1);
            assertThat(services.hasLoginToken(MOD)).isFalse();
            assertThat(services.hasLoginToken(OTHER)).isTrue();
            assertThat(services.factors().totpEnabled(MOD)).isTrue();
            assertThat(services.factors().passkeyCount(MOD)).isEqualTo(1);
            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.RESET_LINK_INVALID);
        }
    }

    @SqlContractTest
    void anExpiredOrUnknownLinkIsRefusedBeforeAnyHash(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            String token = tokenOf(linkFor(services, services.addStaff(MOD)));
            services.clock().advance(PasswordResetService.LINK_LIFETIME.plus(Duration.ofSeconds(1)));

            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.RESET_LINK_INVALID);
            assertRefused(() -> services.resets().reset(services.server(), "a-token-nobody-issued", NEW_PASSWORD),
                AuthFailures.RESET_LINK_INVALID);
            verify(services.hasher(), never()).hash(anyString());
        }
    }

    @SqlContractTest
    void aPolicyFailureOrABusyHasherLeavesTheLinkUsable(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            String token = tokenOf(linkFor(services, services.addStaff(MOD)));
            assertRefused(() -> services.resets().reset(services.server(), token, "too short"), AuthFailures.PASSWORD_POLICY);

            doThrow(AuthFailures.hashingBusy()).when(services.hasher()).hash(NEW_PASSWORD);
            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.PASSWORD_HASHING_BUSY);

            assertThat(services.resets().inspect(services.server(), token).email()).isEqualTo(MOD);
            assertThat(services.repositories().passwords().findPasswordHash(MOD)).isEmpty();
        }
    }

    @SqlContractTest
    void aFailedAuditRollsBackTheConsumptionAndThePassword(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            String token = tokenOf(linkFor(services, services.addStaff(MOD)));
            doThrow(new IllegalStateException("audit failed")).when(services.auditor()).recordStaffAction(any(), any(), any());

            assertThatThrownBy(() -> services.resets().reset(services.server(), token, NEW_PASSWORD)).hasMessageContaining("audit failed");

            assertThat(services.resets().inspect(services.server(), token).email()).isEqualTo(MOD);
            assertThat(services.repositories().passwords().findPasswordHash(MOD)).isEmpty();
        }
    }

    @SqlContractTest
    void aLinkDiesWithTheAccess(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            String token = tokenOf(linkFor(services, mod));

            services.removeStaff(mod);

            assertRefused(() -> services.resets().inspect(services.server(), token), AuthFailures.RESET_LINK_INVALID);
            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.RESET_LINK_INVALID);
        }
    }

    @SqlContractTest
    void theOwnerNeverGetsALinkAndAnAccountThatBecameTheOwnerCannotUseOne(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            Staff owner = services.addStaff(OWNER);
            String token = tokenOf(linkFor(services, mod));

            assertRefused(() -> services.resets().createFor(services.server(), OWNER, owner), AuthFailures.OWNER_ACCOUNT_PROTECTED);
            services.server().setAdminEmail(MOD);
            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.RESET_LINK_INVALID);
        }
    }

    @SqlContractTest
    void resetLinksAreOffWhileTheMethodIsEmailCode(DialectTarget target) {
        try (CredentialServices services = CredentialServices.start(target, dataDirectory)) {
            Staff mod = services.addStaff(MOD);
            assertRefused(() -> services.resets().createFor(services.server(), OWNER, mod), AuthFailures.SIGN_IN_METHOD_DISABLED);

            String token = tokenOf(linkFor(services, mod));
            services.store(SignInMethod.EMAIL_CODE, false);

            assertRefused(() -> services.resets().inspect(services.server(), token), AuthFailures.SIGN_IN_METHOD_DISABLED);
            assertRefused(() -> services.resets().reset(services.server(), token, NEW_PASSWORD), AuthFailures.SIGN_IN_METHOD_DISABLED);
            verify(services.hasher(), never()).hash(anyString());
        }
    }

    private static PasswordResetLink linkFor(CredentialServices services, Staff staff) {
        services.store(SignInMethod.PASSWORD, false);
        return services.resets().createFor(services.server(), OWNER, staff);
    }

    private static String tokenOf(PasswordResetLink link) {
        String fragment = URI.create(link.url()).getRawFragment();
        return URLDecoder.decode(fragment.substring(TOKEN_FRAGMENT.length()), StandardCharsets.UTF_8);
    }
}
