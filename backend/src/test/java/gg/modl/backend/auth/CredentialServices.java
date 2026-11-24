package gg.modl.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.data.WebAuthnCredential;
import gg.modl.backend.auth.link.PanelLoginToken;
import gg.modl.backend.auth.password.AccountPasswords;
import gg.modl.backend.auth.password.PasswordHasher;
import gg.modl.backend.auth.password.PasswordResetService;
import gg.modl.backend.auth.password.PasswordVerification;
import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.auth.repository.PanelSession;
import gg.modl.backend.auth.session.AuthMethod;
import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.auth.settings.SignInMethod;
import gg.modl.backend.auth.settings.SignInSettings;
import gg.modl.backend.auth.settings.SignInSettingsAdministration;
import gg.modl.backend.auth.settings.SignInSettingsService;
import gg.modl.backend.auth.signin.AccountFactors;
import gg.modl.backend.auth.signin.CredentialAttempts;
import gg.modl.backend.auth.signin.Reauthentication;
import gg.modl.backend.auth.staffsecurity.StaffSecurityService;
import gg.modl.backend.auth.totp.RecoveryCodes;
import gg.modl.backend.auth.totp.SecondFactorVerifier;
import gg.modl.backend.auth.totp.TotpEnrollmentService;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.ratelimit.BucketPool;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import gg.modl.backend.log.service.PanelActionAuditor;
import gg.modl.backend.role.service.PermissionService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.repository.JooqSettingsRepository;
import gg.modl.backend.settings.service.PunishmentTypeService;
import gg.modl.backend.settings.service.SettingsDocumentService;
import gg.modl.backend.staff.data.Staff;
import gg.modl.backend.staff.repository.StaffPorts;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

public final class CredentialServices implements AutoCloseable {
    public static final String OWNER = "owner@example.com";
    public static final String MOD = "mod@example.com";
    public static final String OTHER = "other@example.com";
    public static final String PANEL = "https://panel.example.com";
    public static final String HASH_PREFIX = "hash-of:";

    private static final Duration TOKEN_LIFETIME = Duration.ofDays(1);

    private final SqlTestDatabase database;
    private final AuthRepositories repositories;
    private final StaffPorts staff;
    private final Server server = TestServers.server("Server", OWNER);
    private final PanelActionAuditor auditor = mock(PanelActionAuditor.class);
    private final PasswordHasher hasher = mock(PasswordHasher.class);
    private final SignInSettingsService settings;
    private final AccountFactors factors;
    private final PasswordResetService resets;
    private final StaffSecurityService staffSecurity;
    private final SignInSettingsAdministration administration;
    private final TotpEnrollmentService enrollment;
    private final SecondFactorVerifier verifier;

    private CredentialServices(SqlTestDatabase database) {
        this.database = database;
        this.repositories = AuthRepositories.on(database);
        this.staff = StaffPorts.on(database);
        when(hasher.hash(anyString())).thenAnswer(call -> HASH_PREFIX + call.getArgument(0));
        OneTimeCodeCodec codec = new OneTimeCodeCodec(() -> "secret");
        settings = new SignInSettingsService(new SettingsDocumentService(
            new JooqSettingsRepository(database.dsl(), database.insertIfAbsent(), database.upserts(), database.clock()),
            database.unitOfWork(), database.jsonColumns()), database.unitOfWork());
        factors = new AccountFactors(repositories.passwords(), repositories.totp(), repositories.credentials(), repositories.sessions(),
            settings);
        resets = new PasswordResetService(repositories.resetTokens(), repositories.passwords(), repositories.sessions(),
            repositories.loginTokens(), settings,
            new PermissionService(staff.roles(), mock(PunishmentTypeService.class), staff.staff()),
            new AccountPasswords(hasher), codec, new IdGenerator(),
            new PublicUrls(new InstanceProperties(PANEL, null, null, null, null, null), mock(InstanceContext.class), 8080),
            auditor, database.unitOfWork(), database.clock());
        staffSecurity = new StaffSecurityService(staff.staff(), repositories.passwords(), repositories.totp(), repositories.credentials(),
            repositories.challenges(), repositories.sessions(), repositories.loginTokens(), resets, auditor, database.unitOfWork());
        administration = new SignInSettingsAdministration(settings, staffSecurity, factors, repositories.sessions(), auditor,
            database.unitOfWork());
        RecoveryCodes recoveryCodes = new RecoveryCodes(codec);
        CredentialAttempts attempts = new CredentialAttempts(new BucketPool());
        verifier = new SecondFactorVerifier(repositories.totp(), recoveryCodes, database.clock());
        enrollment = new TotpEnrollmentService(repositories.totp(), repositories.sessions(), factors,
            new Reauthentication(new PasswordVerification(attempts, repositories.passwords(), hasher), factors, verifier, attempts,
                mock(AuthService.class), database.unitOfWork(), database.clock()),
            new AccountAuthorization(staff.staff()), attempts, recoveryCodes, auditor, database.unitOfWork(), database.clock());
    }

    public static CredentialServices start(DialectTarget target, Path dataDirectory) {
        return start(target, dataDirectory, WriteAttemptHook.NONE);
    }

    public static CredentialServices start(DialectTarget target, Path dataDirectory, WriteAttemptHook hook) {
        return new CredentialServices(SqlTestDatabase.start(target, dataDirectory, hook));
    }

    public Staff addStaff(String email) {
        Staff member = StaffPorts.member(email, email.substring(0, email.indexOf('@')));
        staff.staff().insert(member);
        return member;
    }

    public void removeStaff(Staff member) {
        database.unitOfWork().write(() -> {
            staff.staff().deleteById(member.getId());
        });
    }

    public AuthSessionData addSession(String email, String id, AuthMethod method, boolean verified) {
        Instant now = clock().instant();
        Instant expiresAt = now.plus(TOKEN_LIFETIME);
        AuthSessionData session = new AuthSessionData(id, email, now, expiresAt, null, null, "digest", method, verified);
        repositories.sessions().insert(
            new PanelSession(session.publicId(), email, "digest", null, null, now, expiresAt, method, verified));
        return session;
    }

    public void addPassword(String email) {
        repositories.passwords().replace(email, HASH_PREFIX + "old password", clock().instant());
    }

    public void addTotp(String email) {
        Instant now = clock().instant();
        repositories.totp().savePending(email, "JBSWY3DPEHPK3PXP", now.plus(TOKEN_LIFETIME), now);
        repositories.totp().enablePending(email, "JBSWY3DPEHPK3PXP", 1, now);
    }

    public void addPasskey(String email) {
        Instant now = clock().instant();
        String key = email.substring(0, email.indexOf('@'));
        repositories.credentials().insertIfAbsent(
            new WebAuthnCredential(key, email, "credential-" + key, new byte[] {1, 2}, 0, "handle-" + key, "Passkey", now, now));
    }

    public void addLoginToken(String email) {
        Instant now = clock().instant();
        repositories.loginTokens().insert(PanelLoginToken.builder()
            .id(email.substring(0, email.indexOf('@')))
            .tokenHash(loginTokenHash(email))
            .identityEmail(email)
            .expiresAt(now.plus(TOKEN_LIFETIME))
            .createdAt(now)
            .build());
    }

    public boolean hasLoginToken(String email) {
        return repositories.loginTokens().consumeActive(loginTokenHash(email), clock().instant()).isPresent();
    }

    public void store(SignInMethod method, boolean secondFactorRequired) {
        settings.replace(settings.state().version(), new SignInSettings(method, secondFactorRequired));
    }

    public static void assertRefused(ThrowingCallable call, String machineCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BaseApplicationException.class,
            refused -> assertThat(refused.getMachineCode()).isEqualTo(machineCode));
    }

    public SqlTestDatabase database() {
        return database;
    }

    public MutableClock clock() {
        return database.clock();
    }

    public AuthRepositories repositories() {
        return repositories;
    }

    public Server server() {
        return server;
    }

    public PanelActionAuditor auditor() {
        return auditor;
    }

    public PasswordHasher hasher() {
        return hasher;
    }

    public SignInSettingsService settings() {
        return settings;
    }

    public AccountFactors factors() {
        return factors;
    }

    public SecondFactorVerifier verifier() {
        return verifier;
    }

    public PasswordResetService resets() {
        return resets;
    }

    public StaffSecurityService staffSecurity() {
        return staffSecurity;
    }

    public SignInSettingsAdministration administration() {
        return administration;
    }

    public TotpEnrollmentService enrollment() {
        return enrollment;
    }

    @Override
    public void close() {
        database.close();
    }

    private static String loginTokenHash(String email) {
        return "login-" + email;
    }
}
