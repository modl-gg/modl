package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.instance.InstanceContext;
import jakarta.mail.MessagingException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailTesterTest {
    private static final MailConfiguration STARTTLS = new MailConfiguration("smtp.example.com", 587, "modl", "secret", "modl@example.com", true);
    private static final MailConfiguration PLAIN = new MailConfiguration("smtp.example.com", 25, "modl", "secret", "modl@example.com", false);

    private final JavaMailSenderFactory factory = mock(JavaMailSenderFactory.class);
    private final JavaMailSenderImpl configured = mock(JavaMailSenderImpl.class);
    private final JavaMailSenderImpl probe = mock(JavaMailSenderImpl.class);
    private final MailTester tester = new MailTester(factory, mock(InstanceContext.class));

    @Test
    void aServerWithoutStartTlsIsATlsFailure() throws MessagingException {
        givenSenders(STARTTLS);
        doThrow(new MessagingException("STARTTLS is required but host does not support STARTTLS")).when(configured).testConnection();

        Optional<MailFailure> failure = tester.verify(STARTTLS);

        assertThat(failure).map(MailFailure::outcome).contains(MailDeliveryOutcome.TLS_FAILED);
        assertThat(failure).map(MailFailure::detail).contains("STARTTLS is required but host does not support STARTTLS");
    }

    @Test
    void aServerThatRefusesEveryConnectionIsAConnectFailure() throws MessagingException {
        givenSenders(STARTTLS);
        doThrow(new MessagingException("554 No service")).when(configured).testConnection();
        doThrow(new MessagingException("554 No service")).when(probe).testConnection();

        assertThat(tester.verify(STARTTLS)).map(MailFailure::outcome).contains(MailDeliveryOutcome.CONNECT_FAILED);
    }

    @Test
    void anUnexplainedFailureWithoutStartTlsIsAConnectFailureWithoutAProbe() throws MessagingException {
        givenSenders(PLAIN);
        doThrow(new MessagingException("554 No service")).when(configured).testConnection();

        assertThat(tester.verify(PLAIN)).map(MailFailure::outcome).contains(MailDeliveryOutcome.CONNECT_FAILED);
        verify(probe, never()).testConnection();
    }

    @Test
    void aConnectionFailureEndsTheTestBeforeAnyMessageIsSent() throws MessagingException {
        givenSenders(STARTTLS);
        doThrow(new MessagingException("STARTTLS is required but host does not support STARTTLS")).when(configured).testConnection();

        MailTestResult result = tester.sendTest(STARTTLS, "owner@example.com");

        assertThat(result.outcome()).isEqualTo(MailDeliveryOutcome.TLS_FAILED);
        assertThat(result.recipient()).isEqualTo("owner@example.com");
        verify(factory, never()).emailSender(STARTTLS);
    }

    private void givenSenders(MailConfiguration configuration) {
        when(factory.javaMailSender(configuration)).thenReturn(configured);
        when(factory.javaMailSender(argThat(candidate -> candidate != null && !candidate.starttls() && candidate.username() == null)))
            .thenReturn(probe);
    }
}
