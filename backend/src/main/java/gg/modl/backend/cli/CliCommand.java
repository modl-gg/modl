package gg.modl.backend.cli;

import java.util.Set;

interface CliCommand {

    String name();

    String usage();

    Set<String> options();

    default Set<String> flags() {
        return Set.of();
    }

    void run(CliArguments arguments, CliContext context) throws Exception;
}
