package gg.modl.backend.email;

import gg.modl.backend.instance.InstanceContext;
import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MailTester {
    private final JavaMailSenderFactory javaMailSenderFactory;
    private final InstanceContext instanceContext;

    public Optional<MailFailure> verify(MailConfiguration configuration) {
        try {
            connect(configuration);
            return Optional.empty();
        } catch (MessagingException | MailException e) {
            return Optional.of(MailFailures.classify(e, () -> unexplainedConnectionOutcome(configuration)));
        }
    }

    public MailTestResult sendTest(MailConfiguration configuration, String recipient) {
        Optional<MailFailure> connectionFailure = verify(configuration);
        if (connectionFailure.isPresent()) {
            return failed(connectionFailure.get(), recipient);
        }
        SmtpEmailSender sender = javaMailSenderFactory.emailSender(configuration);
        String serverName = instanceContext.instance().getServerName();
        try {
            sender.send(EmailMessage.of(recipient, EmailHTMLTemplate.MAIL_TEST.build(serverName)));
            return new MailTestResult(MailDeliveryOutcome.DELIVERED, "", recipient);
        } catch (MessagingException | MailException | UnsupportedEncodingException e) {
            return failed(MailFailures.classify(e, () -> MailDeliveryOutcome.REJECTED), recipient);
        }
    }

    private MailDeliveryOutcome unexplainedConnectionOutcome(MailConfiguration configuration) {
        return configuration.usesStartTls() && connects(configuration.plainProbe())
            ? MailDeliveryOutcome.TLS_FAILED
            : MailDeliveryOutcome.CONNECT_FAILED;
    }

    private boolean connects(MailConfiguration configuration) {
        try {
            connect(configuration);
            return true;
        } catch (MessagingException | MailException e) {
            return false;
        }
    }

    private void connect(MailConfiguration configuration) throws MessagingException {
        javaMailSenderFactory.javaMailSender(configuration).testConnection();
    }

    private static MailTestResult failed(MailFailure failure, String recipient) {
        return new MailTestResult(failure.outcome(), failure.detail(), recipient);
    }
}
