package gg.modl.backend.email;

import gg.modl.backend.instance.InstanceContext;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

@RequiredArgsConstructor
public class SmtpEmailSender implements EmailSender {
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final InstanceContext instanceContext;

    @Override
    public void send(EmailMessage message) throws MessagingException, UnsupportedEncodingException {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");

        helper.setFrom(fromAddress, instanceContext.instance().getServerName());
        helper.setTo(message.to());
        helper.setSubject(message.subject());
        helper.setText(message.html(), true);

        mailSender.send(mimeMessage);
    }
}
