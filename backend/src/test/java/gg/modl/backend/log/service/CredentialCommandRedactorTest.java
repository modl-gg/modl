package gg.modl.backend.log.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CredentialCommandRedactorTest {
    private static final Path PLUGIN_REDACTOR = Path.of("..", "minecraft", "core", "src", "main", "java",
        "gg", "modl", "minecraft", "core", "chat", "CredentialCommandRedactor.java");
    private static final Pattern PLUGIN_COMMAND_LIST =
        Pattern.compile("CREDENTIAL_COMMANDS = [^;]+;", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @ParameterizedTest
    @CsvSource({
        "/login hunter2, /login [REDACTED]",
        "login hunter2, login [REDACTED]",
        "//login hunter2, //login [REDACTED]",
        "/ login hunter2, / login [REDACTED]",
        "/LOGIN hunter2, /LOGIN [REDACTED]",
        "/authme:l hunter2, /authme:l [REDACTED]",
        "/register hunter2 hunter2, /register [REDACTED]",
        "/account unregister hunter2, /account unregister [REDACTED]",
        "/modl link 123456, /modl link [REDACTED]",
        "/myloginplugin hunter2, /myloginplugin [REDACTED]",
        "/sudo Bob login hunter2, /sudo Bob login [REDACTED]",
        "/execute as Bob run minecraft:login hunter2, /execute as Bob run minecraft:login [REDACTED]",
        "/connect password=hunter2, /connect password= [REDACTED]",
        "/connect a b c d e f g TOKEN=abc next, /connect a b c d e f g TOKEN= [REDACTED]",
        "/connect --pass=hunter2, /connect --pass= [REDACTED]",
        "/connect a b c d e f g --password hunter2, /connect a b c d e f g --password [REDACTED]"
    })
    void secretsAreRemoved(String typed, String stored) {
        assertThat(CredentialCommandRedactor.redact(typed)).isEqualTo(stored);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", " ", "\t", "\n", "   "})
    void unusualWhitespaceDoesNotHideTheCommand(String space) {
        assertThat(CredentialCommandRedactor.redact("/login" + space + "hunter2")).isEqualTo("/login [REDACTED]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/login", "/login   ", "", "/", "/msg Alex hello there", "/tp 10 64 10", "/modl reload", "/account info",
        "/say one two three four five six login", "/ban Alex Cheating in survival", "/connect password="
    })
    void commandsWithoutASecretAreStoredAsTyped(String typed) {
        assertThat(CredentialCommandRedactor.redact(typed)).isEqualTo(typed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login hunter2", "/sudo Bob login pw", "/connect password=pw", "/modl link 123456"})
    void redactingTwiceChangesNothing(String typed) {
        String redacted = CredentialCommandRedactor.redact(typed);

        assertThat(CredentialCommandRedactor.redact(redacted)).isEqualTo(redacted);
    }

    @Test
    void everyCommandTheCurrentPluginRedactsIsAlsoRedactedHere() throws IOException {
        List<String> commands = pluginCommands();

        assertThat(commands).contains("login", "modl link", "tclaim");
        for (String command : commands) {
            assertThat(CredentialCommandRedactor.redact("/" + command + " hunter2")).as(command)
                .doesNotContain("hunter2");
        }
    }

    private static List<String> pluginCommands() throws IOException {
        String declaration = PLUGIN_COMMAND_LIST.matcher(Files.readString(PLUGIN_REDACTOR)).results()
            .map(MatchResult::group)
            .findFirst()
            .orElseThrow();
        return QUOTED.matcher(declaration).results().map(match -> match.group(1)).toList();
    }
}
