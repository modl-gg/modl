package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

class MailFailuresTest {

    @Test
    void failuresAreClassifiedByTheirCause() {
        assertThat(outcome(new MessagingException("x", new ConnectException("Connection refused")))).isEqualTo(MailDeliveryOutcome.CONNECT_FAILED);
        assertThat(outcome(new MessagingException("x", new UnknownHostException("nowhere")))).isEqualTo(MailDeliveryOutcome.CONNECT_FAILED);
        assertThat(outcome(new MessagingException("x", new SocketTimeoutException("Read timed out")))).isEqualTo(MailDeliveryOutcome.TIMED_OUT);
        assertThat(outcome(new MessagingException("x", new SSLHandshakeException("bad cert")))).isEqualTo(MailDeliveryOutcome.TLS_FAILED);
        assertThat(outcome(new AuthenticationFailedException("535 Authentication failed"))).isEqualTo(MailDeliveryOutcome.AUTH_FAILED);
        assertThat(outcome(new SendFailedException("550 Sender rejected"))).isEqualTo(MailDeliveryOutcome.REJECTED);
    }

    @Test
    void aFailureWithoutAKnownTypeTakesTheOutcomeOfItsPhase() {
        MessagingException unsupported = new MessagingException("STARTTLS is required but host does not support STARTTLS");

        assertThat(MailFailures.classify(unsupported, () -> MailDeliveryOutcome.TLS_FAILED).outcome()).isEqualTo(MailDeliveryOutcome.TLS_FAILED);
        assertThat(MailFailures.classify(new MailSendException("550 Mailbox unavailable"), () -> MailDeliveryOutcome.REJECTED).outcome())
            .isEqualTo(MailDeliveryOutcome.REJECTED);
    }

    @Test
    void theFailuresOfEachMessageAreClassifiedAndDescribed() {
        MailSendException sendFailure = new MailSendException(Map.of("message", new MessagingException("x", new SocketTimeoutException("Read timed out"))));

        MailFailure failure = MailFailures.classify(sendFailure, () -> MailDeliveryOutcome.REJECTED);

        assertThat(failure.outcome()).isEqualTo(MailDeliveryOutcome.TIMED_OUT);
        assertThat(failure.detail()).isEqualTo("Read timed out");
    }

    @Test
    void theDetailIsTheRootCauseOnOneBoundedLine() {
        assertThat(MailFailures.detail(new MessagingException("outer", new ConnectException("Connection\r\nrefused")))).isEqualTo("Connection refused");
        assertThat(MailFailures.detail(new MailSendException("y".repeat(1000)))).hasSize(300);
    }

    private static MailDeliveryOutcome outcome(Exception failure) {
        return MailFailures.classify(failure, () -> MailDeliveryOutcome.REJECTED).outcome();
    }
}
