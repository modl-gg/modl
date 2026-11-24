package gg.modl.minecraft.core.chat;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class CommandMatcher {
    private static final char NAMESPACE_SEPARATOR = ':';
    private static final String COMMAND_PREFIX = "/";

    private final Set<String> commands;
    private final Function<String, Collection<String>> platformNames;

    private CommandMatcher(Set<String> commands, Function<String, Collection<String>> platformNames) {
        this.commands = commands;
        this.platformNames = platformNames;
    }

    public static CommandMatcher of(Collection<String> commands, Function<String, Collection<String>> platformNames) {
        Set<String> normalized = new HashSet<>();
        for (String command : commands) {
            String name = commandName(command);
            if (!name.isEmpty()) normalized.add(name);
        }
        return new CommandMatcher(Collections.unmodifiableSet(normalized), platformNames);
    }

    public static CommandMatcher none() {
        return new CommandMatcher(Collections.emptySet(), label -> Collections.emptySet());
    }

    public boolean matches(String commandLine) {
        if (commands.isEmpty()) return false;
        String label = labelOf(commandLine);
        if (label.isEmpty()) return false;
        if (commands.contains(withoutNamespace(label))) return true;
        for (String name : platformNames.apply(label)) {
            if (name != null && commands.contains(commandName(name))) return true;
        }
        return false;
    }

    static String labelOf(String commandLine) {
        if (commandLine == null) return "";
        String line = commandLine.trim();
        while (line.startsWith(COMMAND_PREFIX)) line = line.substring(COMMAND_PREFIX.length());
        int end = 0;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) end++;
        return line.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static String commandName(String command) {
        return withoutNamespace(labelOf(command));
    }

    private static String withoutNamespace(String label) {
        return label.substring(label.lastIndexOf(NAMESPACE_SEPARATOR) + 1);
    }
}
