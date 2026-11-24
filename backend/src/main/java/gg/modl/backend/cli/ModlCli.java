package gg.modl.backend.cli;

import gg.modl.backend.infrastructure.util.FailureDescriptions;
import java.io.PrintStream;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ModlCli {
    private static final int SUCCESS = 0;
    private static final int FAILURE = 1;
    private static final int USAGE_ERROR = 2;
    private static final String LAUNCHER = "java -jar modl-server.jar ";
    private static final String OPTION_PREFIX = "-";
    private static final List<CliCommand> COMMANDS = List.of(
        new BackupCommand(),
        new RestoreCommand(),
        new CopyDatabaseCommand(),
        new UpdateCommand()
    );

    private ModlCli() {
    }

    public static boolean handles(String[] args) {
        return args.length > 0 && !args[0].startsWith(OPTION_PREFIX);
    }

    public static int run(String[] args, Map<String, Object> dotenv) {
        PrintStream err = System.err;
        Optional<CliCommand> resolved = command(args[0]);
        if (resolved.isEmpty()) {
            err.println("Unknown command " + args[0]);
            printUsage(err);
            return USAGE_ERROR;
        }
        CliCommand command = resolved.get();
        CliLogging.quiet();
        try {
            CliArguments arguments = CliArguments.parse(args, command.options(), command.flags());
            command.run(arguments, new CliContext(CliSettings.resolve(dotenv), System.out, Clock.system(ZoneId.systemDefault())));
            return SUCCESS;
        } catch (CliUsageException e) {
            err.println(e.getMessage());
            err.println("Usage: " + LAUNCHER + command.usage());
            return USAGE_ERROR;
        } catch (Exception e) {
            err.println("modl " + command.name() + " failed: " + FailureDescriptions.describe(e));
            return FAILURE;
        }
    }

    private static void printUsage(PrintStream err) {
        err.println("Usage:");
        err.println("  " + LAUNCHER.trim() + "                 start the modl server");
        for (CliCommand command : COMMANDS) {
            err.println("  " + LAUNCHER + command.usage());
        }
    }

    private static Optional<CliCommand> command(String name) {
        return COMMANDS.stream().filter(command -> command.name().equals(name)).findFirst();
    }
}
