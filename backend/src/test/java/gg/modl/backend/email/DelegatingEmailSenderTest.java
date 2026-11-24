package gg.modl.backend.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.support.TestServers;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class DelegatingEmailSenderTest {
    private static final MailConfiguration SMTP = new MailConfiguration("smtp.example.com", 587, null, null, "noreply@example.com", true);
    private static final EmailMessage LOGIN_CODE = new EmailMessage("staff@example.com", "Login", "<p>123456</p>",
        "Login code for staff@example.com: 123456 (valid 10 minutes)");

    private MailSettingsService mailSettingsService;
    private JavaMailSenderFactory factory;
    private JavaMailSenderImpl javaMailSender;
    private InstanceContext instanceContext;
    private OperatorNotice operatorNotice;
    private DelegatingEmailSender sender;

    @BeforeEach
    void setUp() {
        mailSettingsService = mock(MailSettingsService.class);
        factory = mock(JavaMailSenderFactory.class);
        javaMailSender = mock(JavaMailSenderImpl.class);
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        instanceContext = mock(InstanceContext.class);
        when(instanceContext.instance()).thenReturn(TestServers.server("Server", "owner@example.com"));
        when(factory.emailSender(any())).thenAnswer(invocation ->
            new SmtpEmailSender(javaMailSender, invocation.<MailConfiguration>getArgument(0).from(), instanceContext));
        operatorNotice = mock(OperatorNotice.class);
        sender = new DelegatingEmailSender(mailSettingsService, factory, operatorNotice);
    }

    @Test
    void withoutEmailSettingsTheConsoleNoticeIsPrinted() {
        when(mailSettingsService.active()).thenReturn(Optional.empty());

        sender.send(LOGIN_CODE);

        verify(operatorNotice).publish("{}", LOGIN_CODE.consoleNotice());
    }

    @Test
    void aFailedDeliveryFallsBackToTheConsoleNotice() {
        when(mailSettingsService.active()).thenReturn(Optional.of(SMTP));
        doThrow(new MailSendException("Connection refused")).when(javaMailSender).send(any(MimeMessage.class));

        sender.send(LOGIN_CODE);

        verify(operatorNotice).publish("{}", LOGIN_CODE.consoleNotice());
    }

    @Test
    void theMailSessionIsBuiltOncePerConfiguration() {
        when(mailSettingsService.active()).thenReturn(Optional.of(SMTP));

        sender.send(LOGIN_CODE);
        sender.send(LOGIN_CODE);
        when(mailSettingsService.active()).thenReturn(Optional.of(SMTP.withPassword("changed")));
        sender.send(LOGIN_CODE);

        verify(factory, times(2)).emailSender(any());
        verify(javaMailSender, times(3)).send(any(MimeMessage.class));
    }
}
