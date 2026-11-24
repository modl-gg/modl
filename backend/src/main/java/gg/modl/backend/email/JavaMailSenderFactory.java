package gg.modl.backend.email;

import gg.modl.backend.instance.InstanceContext;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JavaMailSenderFactory {
    private static final String SMTP_TIMEOUT_MILLIS = "10000";

    private final InstanceContext instanceContext;

    public SmtpEmailSender emailSender(MailConfiguration configuration) {
        return new SmtpEmailSender(javaMailSender(configuration), configuration.from(), instanceContext);
    }

    public JavaMailSenderImpl javaMailSender(MailConfiguration configuration) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(configuration.host());
        mailSender.setPort(configuration.port());
        mailSender.setDefaultEncoding("UTF-8");
        if (configuration.hasCredentials()) {
            mailSender.setUsername(configuration.username());
            mailSender.setPassword(configuration.password());
        }

        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.smtp.auth", String.valueOf(configuration.hasCredentials()));
        properties.put("mail.smtp.ssl.enable", String.valueOf(configuration.usesImplicitTls()));
        properties.put("mail.smtp.starttls.enable", String.valueOf(configuration.usesStartTls()));
        properties.put("mail.smtp.starttls.required", String.valueOf(configuration.usesStartTls()));
        properties.put("mail.smtp.connectiontimeout", SMTP_TIMEOUT_MILLIS);
        properties.put("mail.smtp.timeout", SMTP_TIMEOUT_MILLIS);
        properties.put("mail.smtp.writetimeout", SMTP_TIMEOUT_MILLIS);
        return mailSender;
    }
}
