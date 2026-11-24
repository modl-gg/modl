package gg.modl.minecraft.core.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CredentialCommandRedactorTest {
    private static final String SECRET = "hunter2";

    private final CredentialCommandRedactor redactor = CredentialCommandRedactor.withBuiltInCommands();

    @ParameterizedTest
    @ValueSource(strings = {
            "l", "log", "login", "reg", "register", "unreg", "unregister", "changepassword", "changepass", "changepw",
            "cp", "setpassword", "setpass", "resetpassword", "forcelogin", "forceregister", "authme", "nlogin",
            "openlogin", "email", "2fa", "totp", "passwd", "passch", "confirmpasswordreset", "setemail", "premium",
            "autologin", "license", "librelogin", "forcechangepassword", "forcechangepass", "fcp", "forcereg",
            "verification", "verifyemail", "2fauth", "2fauthcode", "2faconfirm"})
    void everyCommandTheHostedExporterRedactsIsRedacted(String command) {
        assertEquals("/" + command + " [REDACTED]", redactor.redact("/" + command + " " + SECRET));
    }

    @Test
    void everyArgumentIsDropped() {
        assertEquals("/changepassword [REDACTED]", redactor.redact("/changepassword old-password new-password"));
        assertEquals("/authme [REDACTED]", redactor.redact("/authme changepassword Player " + SECRET));
    }

    @Test
    void namespacesAndCaseAreIgnored() {
        assertEquals("/authme:login [REDACTED]", redactor.redact("/authme:login " + SECRET));
        assertEquals("/AuthMe:LoGiN [REDACTED]", redactor.redact("/AuthMe:LoGiN " + SECRET));
        assertEquals("/REG [REDACTED]", redactor.redact("/REG " + SECRET + " " + SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "login hunter2", "//login hunter2", "/ login hunter2", "  /login hunter2", "/login   hunter2",
            "/login\thunter2", "/login hunter2", "/login\nhunter2"})
    void unusualSlashesAndWhitespaceDoNotEscapeRedaction(String command) {
        assertEquals("/login [REDACTED]", redactor.redact(command));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/msg Steve login tonight", "/loginx value", "/home login", "/login", "/login ", "/modl help",
            "/account balance", "/", "/authme:msg Steve hello"})
    void otherCommandsAndCommandsWithoutArgumentsAreUnchanged(String command) {
        assertEquals(command, redactor.redact(command));
    }

    @Test
    void subcommandsWithSecretsAreRedacted() {
        assertEquals("/account changepassword [REDACTED]", redactor.redact("/account changepassword old new"));
        assertEquals("/modl link [REDACTED]", redactor.redact("/modl link ABCD-1234"));
        assertEquals("/modl:modl LINK [REDACTED]", redactor.redact("/modl:modl LINK ABCD-1234"));
        assertEquals("/tclaim [REDACTED]", redactor.redact("/tclaim TICKET-1 access-key"));
        assertEquals("/claimticket [REDACTED]", redactor.redact("/claimticket TICKET-1 access-key"));
    }

    @Test
    void configuredAliasesOfModlSecretsAreRedacted() {
        CredentialCommandRedactor aliased = new CredentialCommandRedactor(
                Collections.singletonMap("modl", "mod|panel"), Collections.emptyList());
        CredentialCommandRedactor claimAliased = new CredentialCommandRedactor(
                Collections.singletonMap("tclaim", "mine"), Collections.emptyList());

        assertEquals("/panel link [REDACTED]", aliased.redact("/panel link ABCD-1234"));
        assertEquals("/mod link [REDACTED]", aliased.redact("/mod link ABCD-1234"));
        assertEquals("/mine [REDACTED]", claimAliased.redact("/mine TICKET-1 access-key"));
    }

    @Test
    void configuredCommandsExtendTheBuiltInList() {
        CredentialCommandRedactor configured = new CredentialCommandRedactor(Collections.emptyMap(),
                Arrays.asList("/MyAuth:Pin", "bank setpin", " ", ""));

        assertEquals("/pin [REDACTED]", configured.redact("/pin 1234"));
        assertEquals("/bank setpin [REDACTED]", configured.redact("/bank setpin 1234"));
        assertEquals("/login [REDACTED]", configured.redact("/login " + SECRET));
        assertEquals("/bank balance Steve", configured.redact("/bank balance Steve"));
    }
}
