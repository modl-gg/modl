package gg.modl.backend.email;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class DelegatingEmailSender implements EmailSender {
    private final MailSettingsService mailSettingsService;
    private final JavaMailSenderFactory javaMailSenderFactory;
    private final ConsoleEmailSender consoleEmailSender;
    private final AtomicReference<ActiveSmtpSender> activeSmtpSender = new AtomicReference<>();

    public DelegatingEmailSender(MailSettingsService mailSettingsService, JavaMailSenderFactory javaMailSenderFactory,
                                 OperatorNotice operatorNotice) {
        this.mailSettingsService = mailSettingsService;
        this.javaMailSenderFactory = javaMailSenderFactory;
        this.consoleEmailSender = new ConsoleEmailSender(operatorNotice);
    }

    @Override
    public void send(EmailMessage message) {
        Optional<MailConfiguration> configuration = mailSettingsService.active();
        if (configuration.isEmpty()) {
            consoleEmailSender.send(message);
            return;
        }
        try {
            smtpSenderFor(configuration.get()).send(message);
        } catch (MessagingException | MailException | UnsupportedEncodingException e) {
            log.warn("Could not email {} through {}, using the console instead: {}", EmailAddressUtil.mask(message.to()),
                configuration.get().host(), MailFailures.detail(e));
            consoleEmailSender.send(message);
        }
    }

    private SmtpEmailSender smtpSenderFor(MailConfiguration configuration) {
        ActiveSmtpSender current = activeSmtpSender.get();
        if (current != null && current.configuration().equals(configuration)) {
            return current.sender();
        }
        SmtpEmailSender sender = javaMailSenderFactory.emailSender(configuration);
        activeSmtpSender.set(new ActiveSmtpSender(configuration, sender));
        return sender;
    }

    private record ActiveSmtpSender(MailConfiguration configuration, SmtpEmailSender sender) {
    }
}
