package gg.modl.minecraft.core.command.error;

import gg.modl.minecraft.core.util.TimeUtil;
import revxrsal.commands.exception.CommandErrorException;
import revxrsal.commands.exception.CooldownException;
import revxrsal.commands.exception.EnumNotFoundException;
import revxrsal.commands.exception.ExpectedLiteralException;
import revxrsal.commands.exception.InputParseException;
import revxrsal.commands.exception.InvalidBooleanException;
import revxrsal.commands.exception.InvalidDecimalException;
import revxrsal.commands.exception.InvalidHelpPageException;
import revxrsal.commands.exception.InvalidIntegerException;
import revxrsal.commands.exception.InvalidListSizeException;
import revxrsal.commands.exception.InvalidStringSizeException;
import revxrsal.commands.exception.InvalidUUIDException;
import revxrsal.commands.exception.MissingArgumentException;
import revxrsal.commands.exception.NoPermissionException;
import revxrsal.commands.exception.NumberNotInRangeException;
import revxrsal.commands.exception.TrailingInputException;
import revxrsal.commands.exception.UnknownCommandException;
import revxrsal.commands.exception.UnknownParameterException;
import revxrsal.commands.exception.ValueNotAllowedException;

import java.text.NumberFormat;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

final class StandardCommandErrors {
    private static final String LIST_SEPARATOR = ", ";

    private StandardCommandErrors() {
    }

    static void addTo(CommandErrorCatalog catalog) {
        addInputErrors(catalog);
        addValueErrors(catalog);
        addLimitErrors(catalog);
        catalog.explain(NoPermissionException.class, e -> CommandError.noPermission());
        catalog.explain(CommandErrorException.class, e -> CommandError.invalidSyntax());
        catalog.explain(CooldownException.class, e -> CommandError.of("cooldown",
                mapOf("time", TimeUtil.formatTimeMillis(Math.max(1, e.getTimeLeftMillis())))));
        catalog.explain(InvalidHelpPageException.class, e -> CommandError.of("invalid_help_page",
                mapOf("page", String.valueOf(e.page()), "pages", String.valueOf(e.numberOfPages()))));
    }

    private static void addInputErrors(CommandErrorCatalog catalog) {
        catalog.explain(UnknownCommandException.class, StandardCommandErrors::unknownCommand);
        catalog.explain(ExpectedLiteralException.class, e -> CommandError.of("expected_literal",
                mapOf("expected", e.node().name(), "input", e.input())));
        catalog.explain(MissingArgumentException.class, e -> CommandError.of("missing_argument",
                mapOf("parameter", e.node().name(), "usage", e.command().usage())));
        catalog.explain(TrailingInputException.class, e -> CommandError.of("too_many_arguments", mapOf("input", e.input())));
        catalog.explain(InputParseException.class, StandardCommandErrors::unreadableInput);
        catalog.explain(UnknownParameterException.class, e -> CommandError.of(
                e.shorthand() ? "unknown_short_flag" : "unknown_flag", mapOf("input", e.name())));
    }

    private static void addValueErrors(CommandErrorCatalog catalog) {
        catalog.explain(EnumNotFoundException.class, e -> CommandError.of("invalid_choice", mapOf("input", e.input())));
        catalog.explain(ValueNotAllowedException.class, e -> CommandError.of("value_not_allowed",
                mapOf("input", e.input(), "allowed", String.join(LIST_SEPARATOR, e.allowedValues()))));
        catalog.explain(InvalidBooleanException.class, e -> CommandError.of("invalid_boolean", mapOf("input", e.input())));
        catalog.explain(InvalidDecimalException.class, e -> CommandError.of("invalid_number", mapOf("input", e.input())));
        catalog.explain(InvalidIntegerException.class, e -> CommandError.of("invalid_integer", mapOf("input", e.input())));
        catalog.explain(InvalidUUIDException.class, e -> CommandError.of("invalid_uuid", mapOf("input", e.input())));
    }

    private static void addLimitErrors(CommandErrorCatalog catalog) {
        catalog.explain(NumberNotInRangeException.class, e -> e.input().doubleValue() < e.minimum()
                ? CommandError.of("number_too_small", mapOf("value", format(e.input()), "minimum", format(e.minimum())))
                : CommandError.of("number_too_large", mapOf("value", format(e.input()), "maximum", format(e.maximum()))));
        catalog.explain(InvalidStringSizeException.class, e -> e.input().length() < e.minimum()
                ? CommandError.of("text_too_short", mapOf("minimum", format(e.minimum())))
                : CommandError.of("text_too_long", mapOf("maximum", format(e.maximum()))));
        catalog.explain(InvalidListSizeException.class, e -> e.inputSize() < e.minimum()
                ? CommandError.of("too_few_values", mapOf("minimum", format(e.minimum())))
                : CommandError.of("too_many_values", mapOf("maximum", format(e.maximum()))));
    }

    private static CommandError unknownCommand(UnknownCommandException e) {
        if (e.suggestions().isEmpty()) return CommandError.of("unknown_command", mapOf("input", e.input()));
        return CommandError.of("unknown_command_suggestions",
                mapOf("input", e.input(), "suggestions", String.join(LIST_SEPARATOR, e.suggestions())));
    }

    private static CommandError unreadableInput(InputParseException e) {
        switch (e.cause()) {
            case INVALID_ESCAPE_CHARACTER:
                return CommandError.of("invalid_escape");
            case UNCLOSED_QUOTE:
                return CommandError.of("unclosed_quote");
            case EXPECTED_WHITESPACE:
                return CommandError.of("expected_space");
            default:
                return CommandError.invalidSyntax();
        }
    }

    private static String format(Number number) {
        return NumberFormat.getInstance().format(number);
    }
}
