package gg.modl.backend.auth;

import static gg.modl.backend.database.jooq.Tables.AUTH_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import gg.modl.backend.auth.repository.AuthRepositories;
import gg.modl.backend.email.EmailService;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeCodec;
import gg.modl.backend.infrastructure.onetimecode.OneTimeCodeConditions;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class AuthServiceContractTest {
    private static final String EMAIL = "mod@example.com";

    private final Server server = TestServers.server("Server", "owner@example.com");
    private final AuthConfiguration configuration = new AuthConfiguration();
    private final EmailService emailService = mock(EmailService.class);
    private final OperatorNotice operatorNotice = mock(OperatorNotice.class);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theEmailedCodeSignsInOnce(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AuthService service = service(database);

            service.sendUserLoginCode(server, "Mod@Example.com");

            ArgumentCaptor<String> consoleLine = ArgumentCaptor.forClass(String.class);
            verify(emailService).send(eq("Mod@Example.com"), any(), consoleLine.capture());
            String code = consoleLine.getValue().replaceAll("^.*: (\\d+) \\(.*$", "$1");
            assertThat(service.verifyCode(server, EMAIL, code)).isTrue();
            assertThat(service.verifyCode(server, EMAIL, code)).isFalse();
        }
    }

    @SqlContractTest
    void exhaustingTheBudgetAnnouncesTheLockoutOnceAndStopsSendingUntilTheWindowEnds(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AuthService service = service(database);
            int codes = configuration.getEmailCodeFailureLimit() / OneTimeCodeConditions.MAX_FAILED_ATTEMPTS;
            for (int code = 0; code < codes; code++) {
                service.sendUserLoginCode(server, EMAIL);
                for (int attempt = 0; attempt < OneTimeCodeConditions.MAX_FAILED_ATTEMPTS; attempt++) {
                    assertThat(service.verifyCode(server, EMAIL, "not-the-code")).isFalse();
                }
                database.clock().advance(Duration.ofSeconds(configuration.getEmailCodeCooldownSeconds()));
            }

            service.sendUserLoginCode(server, EMAIL);

            verify(emailService, times(codes)).send(anyString(), any(), anyString());
            verify(operatorNotice).publish(LoginLockoutNotice.MESSAGE, "m***@example.com", "24 hours");
            verify(operatorNotice).publish(anyString(), any(), any());

            database.clock().advance(Duration.ofSeconds(configuration.getEmailCodeFailureWindowSeconds()));
            service.sendUserLoginCode(server, EMAIL);

            verify(emailService, times(codes + 1)).send(anyString(), any(), anyString());
        }
    }

    @SqlContractTest
    void guessesAgainstAnUnknownAddressStoreAndAnnounceNothing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            AuthService service = service(database);

            for (int attempt = 0; attempt <= configuration.getEmailCodeFailureLimit(); attempt++) {
                assertThat(service.verifyCode(server, "nobody@example.com", "000000")).isFalse();
            }
            assertThat(service.verifyCode(server, "n".repeat(300), "000000")).isFalse();
            assertThat(service.verifyCode(server, " ", "000000")).isFalse();

            assertThat(database.dsl().fetchCount(AUTH_CODES)).isZero();
            verifyNoInteractions(emailService, operatorNotice);
        }
    }

    private AuthService service(SqlTestDatabase database) {
        return new AuthService(emailService, AuthRepositories.on(database).authCodes(), configuration,
            new OneTimeCodeCodec(() -> "secret"), new LoginLockoutNotice(operatorNotice, configuration), database.unitOfWork(),
            database.clock());
    }
}
