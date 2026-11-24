package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.SetupProgress;
import gg.modl.backend.support.MutableClock;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SetupCodeServiceTest {
    private static final Instant BOOT = SqlTestDatabase.START;
    private static final SetupClient OWNER = SetupClient.identifiedBy("203.0.113.7");

    @TempDir
    Path directory;

    private InstanceTestKit kit;
    private MutableClock clock;
    private InstanceContext instanceContext;
    private SetupCodeAnnouncer announcer;
    private SetupCodeFile setupCodeFile;
    private SetupCodeService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(directory);
        clock = kit.clock();
        instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(unclaimed());
        announcer = mock(SetupCodeAnnouncer.class);
        setupCodeFile = mock(SetupCodeFile.class);
        service = new SetupCodeService(kit.secretsRepository(), kit.unitOfWork(), instanceContext, new OneTimeCodeCodec(() -> "secret"), new SetupCodeAttempts(),
            announcer, setupCodeFile, clock);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void anUnclaimedInstanceGetsASetupCodeValidForOneHour() {
        assertThat(service.issueIfNeeded()).isTrue();

        ArgumentCaptor<IssuedSetupCode> issued = ArgumentCaptor.forClass(IssuedSetupCode.class);
        verify(announcer).announce(eq(SetupCodeKind.SETUP), anyString(), issued.capture());
        assertThat(issued.getValue().issuedAt()).isEqualTo(BOOT);
        assertThat(issued.getValue().expiresAt()).isEqualTo(BOOT.plus(Duration.ofHours(1)));
    }

    @Test
    void aPresetOwnerWhoNeverSignedInGetsAnOwnerSignInCode() {
        Server preset = TestServers.server("Server", "owner@example.com");
        preset.setSetup(new SetupProgress(BOOT, null, null, null));
        when(instanceContext.instance()).thenReturn(preset);

        assertThat(service.issueIfNeeded()).isTrue();

        verify(announcer).announce(eq(SetupCodeKind.OWNER_SIGN_IN), anyString(), any());
    }

    @Test
    void anInstanceThatIsSetUpGetsNoCode() {
        Server ready = TestServers.server("Server", "owner@example.com");
        ready.setSetup(new SetupProgress(BOOT, BOOT, null, null));
        when(instanceContext.instance()).thenReturn(ready);

        assertThat(service.issueIfNeeded()).isFalse();

        verify(announcer, never()).announce(any(), anyString(), any());
    }

    @Test
    void theCodeIsSingleUseAcceptsAnyCaseAndSeparatorAndRemovesTheFile() {
        String code = issue();

        assertThat(consume(" " + code.toLowerCase(Locale.ROOT).substring(0, 4) + " - " + code.substring(4) + " ", OWNER))
            .isEqualTo(SetupCodeVerdict.CONSUMED);
        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.NONE);
        verify(setupCodeFile).delete();
    }

    @Test
    void anExpiredCodeIsTreatedAsNoCode() {
        String code = issue();
        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.NONE);
    }

    @Test
    void fiveWrongCodesLockOnlyThatClientOutOfThisCode() {
        String code = issue();
        for (int attempt = 0; attempt < SetupCodeAttempts.MAX_FAILURES_PER_CLIENT; attempt++) {
            assertThat(consume("WRONGCOD", SetupClient.identifiedBy("198.51.100.1"))).isEqualTo(SetupCodeVerdict.INVALID);
        }

        assertThat(consume(code, SetupClient.identifiedBy("198.51.100.1"))).isEqualTo(SetupCodeVerdict.LOCKED);
        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.CONSUMED);
    }

    @Test
    void fiftyWrongCodesInTotalLockTheCodeForEveryone() {
        String code = issue();

        lockForEveryone();

        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.LOCKED);
    }

    @Test
    void aNewCodeClearsEveryClientsFailures() {
        issue();
        for (int attempt = 0; attempt < SetupCodeAttempts.MAX_FAILURES_PER_CLIENT; attempt++) {
            consume("WRONGCOD", OWNER);
        }

        String fresh = issue();

        assertThat(consume(fresh, OWNER)).isEqualTo(SetupCodeVerdict.CONSUMED);
    }

    @Test
    void clientsBehindOneUntrustedProxyShareOneClientBudget() {
        String code = issue();
        SetupClient forwarded = SetupClient.identifiedBy("127.0.0.1");
        for (int attempt = 0; attempt < SetupCodeAttempts.MAX_FAILURES_PER_CLIENT; attempt++) {
            assertThat(consume("WRONGCOD", forwarded)).isEqualTo(SetupCodeVerdict.INVALID);
        }

        assertThat(consume(code, forwarded)).isEqualTo(SetupCodeVerdict.LOCKED);
        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.CONSUMED);
    }

    @Test
    void attemptsWithoutAnActiveCodeAreNotCharged() {
        for (int attempt = 0; attempt < SetupCodeAttempts.MAX_FAILURES_PER_CLIENT + 1; attempt++) {
            assertThat(consume("WRONGCOD", OWNER)).isEqualTo(SetupCodeVerdict.NONE);
        }

        String code = issue();

        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.CONSUMED);
    }

    @Test
    void anInstanceSetUpBeforeTheWizardGetsNoCode() {
        Server ready = TestServers.server("Server", "owner@example.com");
        when(instanceContext.instance()).thenReturn(ready);

        assertThat(service.issueIfNeeded()).isFalse();
        verify(announcer, never()).announce(any(), anyString(), any());
    }

    @Test
    void revokingClearsTheCodeAndTheFile() {
        String code = issue();

        service.revoke();

        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.NONE);
        verify(setupCodeFile, atLeastOnce()).delete();
    }

    @Test
    void aFailedFollowUpWriteLeavesTheCodeUsableAndTheFileInPlace() {
        String code = issue();

        assertThatThrownBy(() -> service.consume(code, OWNER, () -> {
            throw new ConflictException("claimed by someone else");
        })).isInstanceOf(ConflictException.class);

        verify(setupCodeFile, never()).delete();
        assertThat(consume(code, OWNER)).isEqualTo(SetupCodeVerdict.CONSUMED);
    }

    @Test
    void aRetriedConsumeRemovesTheFileOnce() {
        String code = issue();

        consume(code, OWNER);

        verify(setupCodeFile, times(1)).delete();
    }

    private SetupCodeVerdict consume(String code, SetupClient client) {
        return service.consume(code, client, () -> {
        });
    }

    private void lockForEveryone() {
        for (int client = 0; client < SetupCodeService.LOCK_THRESHOLD / SetupCodeAttempts.MAX_FAILURES_PER_CLIENT; client++) {
            for (int attempt = 0; attempt < SetupCodeAttempts.MAX_FAILURES_PER_CLIENT; attempt++) {
                consume("WRONGCOD", SetupClient.identifiedBy("198.51.100." + client));
            }
        }
    }

    private String issue() {
        service.issueIfNeeded();
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(announcer, atLeastOnce()).announce(any(), codes.capture(), any());
        String code = codes.getValue();
        assertThat(code).matches("[A-HJ-NP-Z2-9]{8}");
        return code;
    }

    private static Server unclaimed() {
        return TestServers.server("Server", null);
    }
}
