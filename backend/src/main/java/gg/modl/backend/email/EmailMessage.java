package gg.modl.backend.email;

import org.jspecify.annotations.Nullable;
import java.util.Optional;

public record EmailMessage(String to, String subject, String html, @Nullable String consoleNotice) {

    public static EmailMessage of(String to, EmailHTMLTemplate.HTMLEmail email) {
        return new EmailMessage(to, email.subject(), email.body(), null);
    }

    public static EmailMessage withConsoleNotice(String to, EmailHTMLTemplate.HTMLEmail email, String consoleNotice) {
        return new EmailMessage(to, email.subject(), email.body(), consoleNotice);
    }

    public Optional<String> consoleNoticeText() {
        return Optional.ofNullable(consoleNotice);
    }
}
