package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ConflictException;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.settings.service.SettingsCache;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailSettingsServiceTest {
    private static final MailProperties NOT_CONFIGURED = new MailProperties(null, null, null, null, null, null);
    private static final MailSettingsInput INPUT = new MailSettingsInput("smtp.example.com", 587, "mailer", "noreply@example.com", true);

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private MailTester mailTester;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        mailTester = mock(MailTester.class);
        when(mailTester.verify(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void configuredSmtpWinsAndCannotBeChangedFromThePanel() {
        MailSettingsService service = service(new MailProperties("smtp.config", 465, "user", "secret", "from@example.com", true));

        assertThat(service.view().source()).isEqualTo(MailSettingsSource.CONFIG);
        assertThat(service.view().passwordSet()).isTrue();
        assertThat(service.active()).map(MailConfiguration::host).contains("smtp.config");
        assertThatThrownBy(() -> service.save(INPUT, Optional.of("pw")))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("MAIL_MANAGED_BY_CONFIG");
        assertThatThrownBy(service::delete).isInstanceOf(ConflictException.class);
    }

    @Test
    void withoutConfigOrStoredSettingsThereIsNoDelivery() {
        MailSettingsService service = service(NOT_CONFIGURED);

        assertThat(service.active()).isEmpty();
        assertThat(service.isDeliveryEnabled()).isFalse();
        assertThat(service.view().source()).isEqualTo(MailSettingsSource.NONE);
    }

    @Test
    void savedSettingsAreVerifiedFirstThenUsedForDelivery() {
        MailSettingsService service = service(NOT_CONFIGURED);

        MailSettingsView view = service.save(INPUT, Optional.of("secret"));

        verify(mailTester).verify(INPUT.withPassword("secret"));
        assertThat(view.source()).isEqualTo(MailSettingsSource.PANEL);
        assertThat(view.passwordSet()).isTrue();
        assertThat(service.active()).contains(INPUT.withPassword("secret"));
    }

    @Test
    void settingsThatFailVerificationAreNotSavedAndNameTheFailure() {
        when(mailTester.verify(any())).thenReturn(Optional.of(new MailFailure(MailDeliveryOutcome.AUTH_FAILED, "535 Authentication failed")));
        MailSettingsService service = service(NOT_CONFIGURED);

        assertThatThrownBy(() -> service.save(INPUT, Optional.of("wrong")))
            .isInstanceOf(ValidationException.class)
            .hasMessage("535 Authentication failed")
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("MAIL_AUTH_FAILED");
        assertThat(kit.version("mail")).isZero();
        assertThat(service.active()).isEmpty();
    }

    @Test
    void anOmittedPasswordIsKeptOnlyForTheSameServerAndAccount() {
        MailSettingsService service = service(NOT_CONFIGURED);
        service.save(INPUT, Optional.of("secret"));

        service.save(new MailSettingsInput("SMTP.example.com", 587, "mailer", "other@example.com", true), Optional.empty());
        assertThat(service.active()).map(MailConfiguration::password).contains("secret");

        service.sendTest(Optional.of(new MailSettingsInput("evil.example.net", 587, "mailer", "noreply@example.com", true)),
            Optional.empty(), "owner@example.com");
        verify(mailTester).sendTest(new MailConfiguration("evil.example.net", 587, "mailer", null, "noreply@example.com", true),
            "owner@example.com");
    }

    @Test
    void aStoredPasswordIsNeverSentOverAConnectionWithoutTls() {
        MailSettingsService service = service(NOT_CONFIGURED);
        service.save(INPUT, Optional.of("secret"));

        service.save(new MailSettingsInput("smtp.example.com", 587, "mailer", "noreply@example.com", false), Optional.empty());

        assertThat(service.active()).map(MailConfiguration::password).isEmpty();
    }

    @Test
    void configuredSmtpIsTheOnlyThingThatCanBeTestedWhileItIsActive() {
        MailProperties configured = new MailProperties("smtp.config", 465, "user", "secret", "from@example.com", true);

        service(configured).sendTest(Optional.of(INPUT), Optional.empty(), "owner@example.com");

        verify(mailTester).sendTest(configured.configuration().orElseThrow(), "owner@example.com");
    }

    @Test
    void anEmptyPasswordClearsIt() {
        MailSettingsService service = service(NOT_CONFIGURED);
        service.save(INPUT, Optional.of("secret"));

        assertThat(service.save(INPUT, Optional.of("")).passwordSet()).isFalse();
    }

    @Test
    void theStoredSettingsAreReadOnceAndRefreshedOnWrites() {
        MailSettingsService service = service(NOT_CONFIGURED);

        assertThat(service.active()).isEmpty();
        kit.store("mail", Map.of("host", "smtp.example.com", "port", 587, "from", "noreply@example.com"));
        assertThat(service.active()).isEmpty();

        kit.clock().advance(SettingsCache.LIFETIME);
        assertThat(service.active()).map(MailConfiguration::host).contains("smtp.example.com");

        service.save(INPUT, Optional.of("secret"));
        assertThat(service.active()).map(MailConfiguration::password).contains("secret");
    }

    @Test
    void deletingStopsDelivery() {
        MailSettingsService service = service(NOT_CONFIGURED);
        service.save(INPUT, Optional.of("secret"));

        assertThat(service.delete().source()).isEqualTo(MailSettingsSource.NONE);

        assertThat(kit.settingsRepository().find("mail")).isEmpty();
        assertThat(service.active()).isEmpty();
    }

    @Test
    void testingWithoutAnySettingsIsAConflict() {
        assertThatThrownBy(() -> service(NOT_CONFIGURED).sendTest(Optional.empty(), Optional.empty(), "owner@example.com"))
            .isInstanceOf(ConflictException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode())
            .isEqualTo("MAIL_NOT_CONFIGURED");
    }

    @Test
    void thePasswordNeverAppearsInTextRenderings() {
        MailConfiguration configuration = INPUT.withPassword("hunter2");

        assertThat(configuration.toString()).doesNotContain("hunter2");
        assertThat(MailSettingsService.StoredMailSettings.of(configuration).toString()).doesNotContain("hunter2");
        assertThat(new MailProperties("smtp", 587, "user", "hunter2", "from@example.com", true).toString()).doesNotContain("hunter2");
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> new MailSettingsInput("smtp example.com", 587, null, "noreply@example.com", true))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MailSettingsInput("smtp.example.com", 0, null, "noreply@example.com", true))
            .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MailSettingsInput("smtp.example.com", 587, null, "not an address", true))
            .isInstanceOf(ValidationException.class);
    }

    private MailSettingsService service(MailProperties properties) {
        return new MailSettingsService(
            properties, kit.instanceContext(), mailTester, kit.documents(), kit.unitOfWork(), kit.clock());
    }
}
