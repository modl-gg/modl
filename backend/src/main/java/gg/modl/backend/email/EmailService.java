package gg.modl.backend.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final AsyncEmailDispatcher dispatcher;

    public void sendStaffInviteEmail(String toEmail, String serverName, String role, String invitationLink,
                                     ValidityPeriod validity) {
        EmailHTMLTemplate.HTMLEmail email = EmailHTMLTemplate.STAFF_INVITE_TEMPLATE.build(serverName, role, invitationLink, validity);
        dispatcher.dispatch(EmailMessage.withConsoleNotice(toEmail, email,
            "Staff invitation for %s: %s".formatted(toEmail, invitationLink)));
    }

    public void send(String toEmail, EmailHTMLTemplate.HTMLEmail email) {
        dispatcher.dispatch(EmailMessage.of(toEmail, email));
    }

    public void send(String toEmail, EmailHTMLTemplate.HTMLEmail email, String consoleNotice) {
        dispatcher.dispatch(EmailMessage.withConsoleNotice(toEmail, email, consoleNotice));
    }
}
