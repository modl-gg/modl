package gg.modl.backend.settings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.email.MailConfiguration;
import gg.modl.backend.email.MailDeliveryOutcome;
import gg.modl.backend.email.MailSettingsInput;
import gg.modl.backend.email.MailSettingsService;
import gg.modl.backend.email.MailSettingsSource;
import gg.modl.backend.email.MailSettingsView;
import gg.modl.backend.email.MailTestResult;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.proto.modl.v1.MailSettings;
import gg.modl.proto.modl.v1.MailSettingsResponse;
import gg.modl.proto.modl.v1.MailSource;
import gg.modl.proto.modl.v1.MailTestOutcome;
import gg.modl.proto.modl.v1.TestMailRequest;
import gg.modl.proto.modl.v1.UpdateMailSettingsRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PanelMailSettingsControllerTest {
    private static final MailSettings SETTINGS = MailSettings.newBuilder()
        .setHost("smtp.example.com").setPort(587).setUsername("mailer").setFrom("noreply@example.com").setStarttls(true).build();
    private static final MailSettingsInput INPUT = new MailSettingsInput("smtp.example.com", 587, "mailer", "noreply@example.com", true);
    private static final MailSettingsView STORED = new MailSettingsView(MailSettingsSource.PANEL, Optional.of(INPUT.withPassword("secret")));

    private final MailSettingsService mailSettingsService = mock(MailSettingsService.class);
    private final PanelMailSettingsController controller = new PanelMailSettingsController(mailSettingsService);

    @Test
    void anOmittedPasswordKeepsTheStoredOneAndAnEmptyOneClearsIt() {
        when(mailSettingsService.save(any(), any())).thenReturn(STORED);

        controller.updateSettings(UpdateMailSettingsRequest.newBuilder().setSettings(SETTINGS).build());
        controller.updateSettings(UpdateMailSettingsRequest.newBuilder().setSettings(SETTINGS).setPassword("").build());

        verify(mailSettingsService).save(INPUT, Optional.empty());
        verify(mailSettingsService).save(INPUT, Optional.of(""));
    }

    @Test
    void theResponseNeverCarriesThePassword() {
        when(mailSettingsService.view()).thenReturn(STORED);

        MailSettingsResponse response = controller.getSettings();

        assertThat(response.getSource()).isEqualTo(MailSource.MAIL_SOURCE_PANEL);
        assertThat(response.getPasswordSet()).isTrue();
        assertThat(response.toString()).doesNotContain("secret");
    }

    @Test
    void theTestMailGoesToTheSignedInOwner() {
        when(mailSettingsService.sendTest(eq(Optional.empty()), eq(Optional.empty()), eq("owner@example.com")))
            .thenReturn(new MailTestResult(MailDeliveryOutcome.CONNECT_FAILED, "Connection refused", "owner@example.com"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/panel/settings/mail/test");
        AuthSessionData session = new AuthSessionData();
        session.setEmail("owner@example.com");
        request.setAttribute(RequestAttribute.SESSION, session);

        assertThat(controller.sendTest(TestMailRequest.getDefaultInstance(), request).getOutcome())
            .isEqualTo(MailTestOutcome.MAIL_TEST_OUTCOME_CONNECT_FAILED);
    }

    @Test
    void configurationsWithoutAUsernameAreShownWithAnEmptyOne() {
        when(mailSettingsService.view()).thenReturn(new MailSettingsView(MailSettingsSource.CONFIG,
            Optional.of(new MailConfiguration("smtp.example.com", 25, null, null, "noreply@example.com", false))));

        assertThat(controller.getSettings().getSettings().getUsername()).isEmpty();
    }
}
