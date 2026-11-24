package gg.modl.backend.cli;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CliArguments {
    private static final String OPTION_PREFIX = "--";

    private final Map<String, String> options;
    private final Set<String> flags;

    private CliArguments(Map<String, String> options, Set<String> flags) {
        this.options = Map.copyOf(options);
        this.flags = Set.copyOf(flags);
    }

    static CliArguments parse(String[] args, Set<String> allowedOptions, Set<String> allowedFlags) {
        Map<String, String> options = new HashMap<>();
        Set<String> flags = new HashSet<>();
        int index = 1;
        while (index < args.length) {
            String option = args[index];
            String name = option.startsWith(OPTION_PREFIX) ? option.substring(OPTION_PREFIX.length()) : "";
            if (allowedFlags.contains(name)) {
                flags.add(name);
                index++;
                continue;
            }
            if (!allowedOptions.contains(name)) {
                throw new CliUsageException("Unknown option " + option);
            }
            if (index + 1 >= args.length || args[index + 1].isBlank() || args[index + 1].startsWith(OPTION_PREFIX)) {
                throw new CliUsageException("Missing value for " + option);
            }
            options.put(name, args[index + 1]);
            index += 2;
        }
        return new CliArguments(options, flags);
    }

    String required(String option) {
        String value = options.get(option);
        if (value == null) {
            throw new CliUsageException("Missing required option --" + option);
        }
        return value;
    }

    Optional<String> optional(String option) {
        return Optional.ofNullable(options.get(option));
    }

    boolean flag(String flag) {
        return flags.contains(flag);
    }
}
