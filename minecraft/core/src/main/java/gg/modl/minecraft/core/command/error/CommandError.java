package gg.modl.minecraft.core.command.error;

import lombok.Value;

import java.util.Map;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@Value
public class CommandError {
    private static final String KEY_PREFIX = "command_errors.";

    String localeKey;
    Map<String, String> values;

    public static CommandError playersOnly() {
        return new CommandError("general.players_only", mapOf());
    }

    public static CommandError consoleOnly() {
        return of("console_only");
    }

    public static CommandError invalidPlayer(String input) {
        return of("invalid_player", mapOf("input", input));
    }

    public static CommandError of(String name) {
        return of(name, mapOf());
    }

    public static CommandError of(String name, Map<String, String> values) {
        return new CommandError(KEY_PREFIX + name, values);
    }

    static CommandError noPermission() {
        return new CommandError("general.no_permission", mapOf());
    }

    static CommandError invalidSyntax() {
        return new CommandError("general.invalid_syntax", mapOf());
    }
}
