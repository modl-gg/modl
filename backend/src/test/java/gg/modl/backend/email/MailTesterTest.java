package gg.modl.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.email.StartTlsProbe.Support;
import gg.modl.backend.instance.InstanceContext;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.util.Optional;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailTesterTest {
    private static final MailConfiguration STARTTLS = new MailConfiguration("smtp.example.com", 587, "modl", "secret", "modl@example.com", true);
    private static final MailConfiguration PLAIN = new MailConfiguration("smtp.example.com", 25, "modl", "secret", "modl@example.com", false);
    private static final MessagingException UNEXPLAINED = new MessagingException("STARTTLS is required but host does not support STARTTLS");

    private final JavaMailSenderFactory factory = mock(JavaMailSenderFactory.class);
    private final JavaMailSenderImpl configured = mock(JavaMailSenderImpl.class);
    private final StartTlsProbe startTls = mock(StartTlsProbe.class);
    private final MailTester tester = new MailTester(factory, mock(InstanceContext.class), startTls);

    @Test
    void aServerThatOffersNoStartTlsIsATlsFailureNamingIt() throws MessagingException {
        failsWith(STARTTLS, UNEXPLAINED);
        when(startTls.probe("smtp.example.com", 587)).thenReturn(Support.NOT_OFFERED);

        Optional<MailFailure> failure = tester.verify(STARTTLS);

        assertThat(failure).contains(new MailFailure(MailDeliveryOutcome.TLS_FAILED, MailTester.STARTTLS_NOT_OFFERED));
    }

    @Test
    void anUnexplainedFailureOnAServerThatOffersStartTlsIsAConnectFailure() throws MessagingException {
        failsWith(STARTTLS, new MessagingException("554 No service"));
        when(startTls.probe("smtp.example.com", 587)).thenReturn(Support.OFFERED);

        assertThat(tester.verify(STARTTLS)).contains(new MailFailure(MailDeliveryOutcome.CONNECT_FAILED, "554 No service"));
    }

    @Test
    void anUnexplainedFailureOnAServerThatCannotBeAskedIsAConnectFailure() throws MessagingException {
        failsWith(STARTTLS, new MessagingException("554 No service"));
        when(startTls.probe("smtp.example.com", 587)).thenReturn(Support.UNKNOWN);

        assertThat(tester.verify(STARTTLS)).map(MailFailure::outcome).contains(MailDeliveryOutcome.CONNECT_FAILED);
    }

    @Test
    void aFailedHandshakeIsATlsFailureWithoutAProbe() throws MessagingException {
        failsWith(STARTTLS, new MessagingException("Could not convert socket to TLS", new SSLHandshakeException("PKIX path building failed")));

        assertThat(tester.verify(STARTTLS)).contains(new MailFailure(MailDeliveryOutcome.TLS_FAILED, "PKIX path building failed"));
        verify(startTls, never()).probe(anyString(), anyInt());
    }

    @Test
    void refusedCredentialsAreAnAuthFailureWithoutAProbe() throws MessagingException {
        failsWith(STARTTLS, new AuthenticationFailedException("535 Authentication failed"));

        assertThat(tester.verify(STARTTLS)).map(MailFailure::outcome).contains(MailDeliveryOutcome.AUTH_FAILED);
        verify(startTls, never()).probe(anyString(), anyInt());
    }

    @Test
    void anUnexplainedFailureWithoutStartTlsIsAConnectFailureWithoutAProbe() throws MessagingException {
        failsWith(PLAIN, new MessagingException("554 No service"));

        assertThat(tester.verify(PLAIN)).map(MailFailure::outcome).contains(MailDeliveryOutcome.CONNECT_FAILED);
        verify(startTls, never()).probe(anyString(), anyInt());
    }

    @Test
    void aConnectionFailureEndsTheTestBeforeAnyMessageIsSent() throws MessagingException {
        failsWith(STARTTLS, UNEXPLAINED);
        when(startTls.probe("smtp.example.com", 587)).thenReturn(Support.NOT_OFFERED);

        MailTestResult result = tester.sendTest(STARTTLS, "owner@example.com");

        assertThat(result.outcome()).isEqualTo(MailDeliveryOutcome.TLS_FAILED);
        assertThat(result.recipient()).isEqualTo("owner@example.com");
        verify(factory, never()).emailSender(STARTTLS);
    }

    private void failsWith(MailConfiguration configuration, MessagingException failure) throws MessagingException {
        when(factory.javaMailSender(configuration)).thenReturn(configured);
        doThrow(failure).when(configured).testConnection();
    }
}
