package gg.modl.backend.email;

import jakarta.mail.MessagingException;
import java.io.UnsupportedEncodingException;

public interface EmailSender {
    void send(EmailMessage message) throws MessagingException, UnsupportedEncodingException;
}
