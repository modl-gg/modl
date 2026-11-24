package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.command.ConfiguredCommandAliases;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class CommandRootRegistry {
    private final Map<String, String> rootKeyByAlias;
    private final Set<String> enabledRootKeys;

    private CommandRootRegistry(Map<String, String> rootKeyByAlias, Set<String> enabledRootKeys) {
        this.rootKeyByAlias = Collections.unmodifiableMap(rootKeyByAlias);
        this.enabledRootKeys = Collections.unmodifiableSet(enabledRootKeys);
    }

    public static Builder builder(ConfiguredCommandAliases aliases) {
        return new Builder(aliases);
    }

    public boolean isEnabled(String rootKey) {
        return enabledRootKeys.contains(normalize(rootKey));
    }

    public Optional<String> rootKeyOf(String commandLine) {
        String alias = firstToken(commandLine);
        if (alias.isEmpty()) return Optional.empty();
        return Optional.ofNullable(rootKeyByAlias.get(alias));
    }

    public static String firstToken(String commandLine) {
        if (commandLine == null) return "";
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        return normalize(space >= 0 ? line.substring(0, space) : line);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Builder {
        private final ConfiguredCommandAliases aliases;
        private final Map<String, String> rootKeyByAlias = new HashMap<>();
        private final Set<String> enabledRootKeys = new HashSet<>();

        private Builder(ConfiguredCommandAliases aliases) {
            this.aliases = aliases;
        }

        public Builder registered(String... rootKeys) {
            for (String rootKey : rootKeys) {
                for (String alias : aliases.aliasesFor(rootKey)) {
                    rootKeyByAlias.put(normalize(alias), normalize(rootKey));
                    enabledRootKeys.add(normalize(rootKey));
                }
            }
            return this;
        }

        public CommandRootRegistry build() {
            return new CommandRootRegistry(new HashMap<>(rootKeyByAlias), new HashSet<>(enabledRootKeys));
        }
    }
}
