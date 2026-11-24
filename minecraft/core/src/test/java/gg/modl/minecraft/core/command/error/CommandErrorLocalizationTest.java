package gg.modl.minecraft.core.command.error;

import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.support.FakeCommandActor;
import gg.modl.minecraft.core.support.RecordingPluginLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import revxrsal.commands.Lamp;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Range;
import revxrsal.commands.command.CommandActor;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.exception.SendMessageException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandErrorLocalizationTest {
    private final LocaleManager locale = new LocaleManager("de_DE");
    private final RecordingPluginLogger logger = new RecordingPluginLogger();
    private final FakeCommandActor actor = new FakeCommandActor(UUID.randomUUID(), "Staffer");
    private Lamp<CommandActor> lamp;

    @BeforeEach
    void buildLamp() {
        Lamp.Builder<CommandActor> builder = Lamp.builder();
        CommandErrorLocalization.install(builder, locale, logger, catalog -> { });
        lamp = builder.build();
        lamp.register(new SampleCommands());
    }

    @Test
    void anUnknownCommandIsTranslated() {
        assertReplies("zzzzzzzz", expected("unknown_command", "input", "zzzzzzzz"));
    }

    @Test
    void aMistypedCommandGetsATranslatedSuggestion() {
        assertReplies("sampel", locale.getMessageWithRawValues("command_errors.unknown_command_suggestions",
                mapOf("input", "sampel", "suggestions", "sample")));
    }

    @Test
    void severalMatchingSubcommandsAreSuggestedInTheConfiguredLanguage() {
        lamp.dispatch(actor, "sample numbr");

        List<String> replies = actor.messages();
        assertEquals(expected("unknown_subcommand", "input", "sample numbr"), replies.get(0));
        assertTrue(replies.get(1).contains("sample number"), replies.toString());
        assertTrue(actor.errors().isEmpty(), actor.errors().toString());
    }

    @Test
    void aSingleExpectedSubcommandIsTranslated() {
        assertReplies("lonely other", locale.getMessageWithRawValues("command_errors.expected_literal",
                mapOf("expected", "only", "input", "other")));
    }

    @Test
    void aMissingArgumentNamesTheParameterAndTheUsage() {
        assertReplies("sample number", locale.getMessageWithRawValues("command_errors.missing_argument",
                mapOf("parameter", "amount", "usage", "sample number <amount>")));
    }

    @Test
    void anInvalidIntegerIsTranslated() {
        assertReplies("sample number many", expected("invalid_integer", "input", "many"));
    }

    @Test
    void anInvalidBooleanIsTranslated() {
        assertReplies("sample toggle maybe", expected("invalid_boolean", "input", "maybe"));
    }

    @Test
    void anInvalidChoiceIsTranslated() {
        assertReplies("sample mode purple", expected("invalid_choice", "input", "purple"));
    }

    @Test
    void aNumberOutsideItsRangeNamesTheLimit() {
        assertReplies("sample number 9", locale.getMessageWithRawValues("command_errors.number_too_large",
                mapOf("value", "9", "maximum", "5")));
        actor.messages().clear();
        assertReplies("sample number 0", locale.getMessageWithRawValues("command_errors.number_too_small",
                mapOf("value", "0", "minimum", "1")));
    }

    @Test
    void anUnclosedQuoteIsTranslated() {
        assertReplies("sample text \"never closed", locale.getMessage("command_errors.unclosed_quote"));
    }

    @Test
    void aLibraryErrorTextNeverReachesTheSenderInEnglish() {
        assertReplies("sample library", locale.getMessage("general.invalid_syntax"));
    }

    @Test
    void messagesModlAlreadyTranslatedPassThrough() {
        assertReplies("sample own", "already translated");
        assertTrue(logger.warnings().isEmpty());
    }

    @Test
    void aFailingCommandIsLoggedAndAnsweredWithoutItsDetails() {
        assertReplies("sample crash", locale.getMessage("command_errors.failed"));

        assertEquals(1, logger.warnings().size());
        assertTrue(logger.warnings().get(0).startsWith("[commands] /sample failed"));
        assertTrue(logger.warnings().get(0).contains("internal detail"));
        assertFalse(actor.lastMessage().contains("internal detail"));
    }

    @Test
    void echoedInputCannotAddFormattingOrLines() {
        lamp.dispatch(actor, "sample number &4&lADMIN§k\u001b<red>\u202e");

        assertEquals(expected("invalid_integer", "input", "＆4＆lADMINk<red>"), actor.lastMessage());
        assertFalse(actor.lastMessage().contains("&"));
    }

    @Test
    void hexAndTrailingCodesAreNeutralizedToo() {
        new CommandErrorReplies(locale).send(actor,
                CommandError.of("invalid_player", mapOf("input", "&#ff0000&x&&c\n&")));

        assertEquals(expected("invalid_player", "input", "＆#ff0000＆x＆＆c＆"), actor.lastMessage());
    }

    @Test
    void echoedPlaceholdersStayLiteral() {
        new CommandErrorReplies(locale).send(actor,
                CommandError.of("expected_literal", mapOf("expected", "{input}", "input", "{expected}")));

        assertTrue(actor.lastMessage().contains("{input}"));
        assertTrue(actor.lastMessage().contains("'{expected}'"));
    }

    @Test
    void echoedInputIsBounded() {
        String input = String.join("", Collections.nCopies(5_000, "9"));

        new CommandErrorReplies(locale).send(actor, CommandError.invalidPlayer(input));

        int template = expected("invalid_player", "input", "").length();
        assertEquals(template + CommandErrorReplies.MAX_VALUE_LENGTH, actor.lastMessage().length());
    }

    private String expected(String key, String placeholder, String value) {
        return locale.getMessageWithRawValues("command_errors." + key, mapOf(placeholder, value));
    }

    private void assertReplies(String input, String reply) {
        lamp.dispatch(actor, input);
        assertEquals(Collections.singletonList(reply), actor.messages());
        assertEquals(Arrays.asList(), actor.errors());
    }

    public enum SampleMode {
        FAST, SLOW
    }

    public static final class SampleCommands {
        @Command("sample number")
        public void number(CommandActor sender, @Named("amount") @Range(min = 1, max = 5) int amount) {
        }

        @Command("sample toggle")
        public void toggle(CommandActor sender, @Named("enabled") boolean enabled) {
        }

        @Command("sample mode")
        public void mode(CommandActor sender, @Named("mode") SampleMode mode) {
        }

        @Command("sample text")
        public void text(CommandActor sender, @Named("text") String text) {
        }

        @Command("sample library")
        @SuppressWarnings("DoNotCallSuggester")
        public void library(CommandActor sender) {
            throw new CommandErrorException("English text from the library");
        }

        @Command("sample own")
        @SuppressWarnings("DoNotCallSuggester")
        public void own(CommandActor sender) {
            throw new SendMessageException("already translated");
        }

        @Command("sample crash")
        @SuppressWarnings("DoNotCallSuggester")
        public void crash(CommandActor sender) {
            throw new IllegalStateException("internal detail");
        }

        @Command("lonely only")
        public void lonely(CommandActor sender) {
        }
    }
}
