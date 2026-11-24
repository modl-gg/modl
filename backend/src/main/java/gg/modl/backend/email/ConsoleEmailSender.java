package gg.modl.backend.email;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ConsoleEmailSender implements EmailSender {
    private final OperatorNotice operatorNotice;

    @Override
    public void send(EmailMessage message) {
        message.consoleNoticeText().ifPresentOrElse(
            text -> operatorNotice.publish("{}", text),
            () -> log.debug("Email delivery is not configured; dropped \"{}\" to {}",
                message.subject(), EmailAddressUtil.mask(message.to()))
        );
    }
}
