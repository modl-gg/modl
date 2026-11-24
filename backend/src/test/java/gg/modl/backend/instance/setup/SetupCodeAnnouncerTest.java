package gg.modl.backend.instance.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.infrastructure.web.HttpOrigin;
import gg.modl.backend.instance.InstanceProperties;
import gg.modl.backend.instance.PublicUrls;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SetupCodeAnnouncerTest {
    private static final IssuedSetupCode ISSUED = new IssuedSetupCode("hash", Instant.parse("2026-11-21T12:00:00Z"),
        Instant.parse("2026-11-21T13:00:00Z"));
    private static final String PLUGIN_FILE = "plugins/modl/setup-code.txt";
    private static final InstanceProperties PLUGIN = new InstanceProperties(null, null, null, null, "internal-key", null);
    private static final InstanceProperties STANDALONE = new InstanceProperties(null, null, null, null, null, null);

    private PublicUrls publicUrls;
    private SetupCodeFile setupCodeFile;
    private OperatorNotice operatorNotice;

    @BeforeEach
    void setUp() {
        publicUrls = mock(PublicUrls.class);
        setupCodeFile = mock(SetupCodeFile.class);
        operatorNotice = mock(OperatorNotice.class);
        when(publicUrls.knownOrigin()).thenReturn(Optional.empty());
        when(publicUrls.hostNeutralSetupPage()).thenReturn("http://<this server's address>:8080/setup");
        when(setupCodeFile.write(anyString())).thenReturn(Optional.of(PLUGIN_FILE));
    }

    @Test
    void anEmbeddedSetupCodeNamesTheFileAndTheConnectionFileFallback() {
        announcer(PLUGIN).announce(SetupCodeKind.SETUP, "ABCDEFGH", ISSUED);

        InOrder order = inOrder(operatorNotice);
        order.verify(operatorNotice).publish("{}: {}. Open {} and enter it (valid {}).", "Setup code", "ABCD-EFGH",
            "http://<this server's address>:8080/setup", "1 hour");
        order.verify(operatorNotice).publish("{}", "The code is also saved in plugins/modl/setup-code.txt. If this console is"
            + " unreliable, read it there, or stop the server, set embedded.admin-email in connection.yml and start it again.");
    }

    @Test
    void aStandaloneSetupCodeOffersTheEnvironmentFallbackAndAKnownPanelUrl() {
        when(publicUrls.knownOrigin()).thenReturn(Optional.of(new HttpOrigin("https", "panel.example.com", 443)));
        when(publicUrls.setupPage()).thenReturn("https://panel.example.com/setup");
        when(setupCodeFile.write(anyString())).thenReturn(Optional.of("data/setup-code.txt"));

        announcer(STANDALONE).announce(SetupCodeKind.SETUP, "ABCDEFGH", ISSUED);

        verify(operatorNotice).publish("{}: {}. Open {} and enter it (valid {}).", "Setup code", "ABCD-EFGH",
            "https://panel.example.com/setup", "1 hour");
        verify(operatorNotice).publish("{}", "The code is also saved in data/setup-code.txt. If this console is unreliable, read it"
            + " there, or stop the server, set MODL_ADMIN_EMAIL and start it again.");
    }

    @Test
    void anOwnerSignInCodeOnlyNamesTheFile() {
        announcer(PLUGIN).announce(SetupCodeKind.OWNER_SIGN_IN, "ABCDEFGH", ISSUED);

        verify(operatorNotice).publish("{}: {}. Open {} and enter it (valid {}).", "Owner sign-in code", "ABCD-EFGH",
            "http://<this server's address>:8080/setup", "1 hour");
        verify(operatorNotice).publish("{}", "The code is also saved in plugins/modl/setup-code.txt.");
    }

    @Test
    void withoutAFileTheSecondLineKeepsOnlyTheConfigFallback() {
        when(setupCodeFile.write(anyString())).thenReturn(Optional.empty());

        announcer(STANDALONE).announce(SetupCodeKind.SETUP, "ABCDEFGH", ISSUED);

        verify(operatorNotice).publish("{}", "If this console is unreliable, stop the server, set MODL_ADMIN_EMAIL and start it again.");
    }

    @Test
    void withoutAFileAnOwnerSignInCodePrintsOneLine() {
        when(setupCodeFile.write(anyString())).thenReturn(Optional.empty());

        announcer(PLUGIN).announce(SetupCodeKind.OWNER_SIGN_IN, "ABCDEFGH", ISSUED);

        verify(operatorNotice, never()).publish(eq("{}"), anyString());
    }

    @Test
    void theFileCarriesTheCodeThePageAndTheExpiry() {
        announcer(PLUGIN).announce(SetupCodeKind.SETUP, "ABCDEFGH", ISSUED);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(setupCodeFile).write(content.capture());
        assertThat(content.getValue())
            .startsWith("modl setup code: ABCD-EFGH")
            .contains("Open http://<this server's address>:8080/setup and enter the code.")
            .contains("(server time). This file is deleted when setup is finished.")
            .contains("For a new code, restart the server.");
    }

    private SetupCodeAnnouncer announcer(InstanceProperties properties) {
        return new SetupCodeAnnouncer(publicUrls, properties, setupCodeFile, operatorNotice);
    }
}
