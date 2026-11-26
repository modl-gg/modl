package gg.modl.backend.cli;

import gg.modl.backend.backup.BackupDiskSpace;
import gg.modl.backend.database.backup.DatabaseCopy;
import gg.modl.backend.database.backup.TransferReport;
import gg.modl.backend.database.sql.BackupSource;
import gg.modl.backend.database.sql.LockedDatabase;
import gg.modl.backend.database.sql.OfflineDatabase;
import gg.modl.backend.database.sql.RestoreTarget;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import java.io.IOException;
import java.util.List;
import java.util.Set;

final class CopyDatabaseCommand implements CliCommand {
    private static final String TO = "to";
    private static final String USERNAME = "username";
    private static final String PASSWORD_VARIABLE = "password-env";
    private static final String BUILT_IN = "sqlite";

    @Override
    public String name() {
        return "copy-database";
    }

    @Override
    public String usage() {
        return "copy-database --to <jdbc-url|" + BUILT_IN + "> [--username <name>] [--password-env <variable>]";
    }

    @Override
    public Set<String> options() {
        return Set.of(TO, USERNAME, PASSWORD_VARIABLE);
    }

    @Override
    public void run(CliArguments arguments, CliContext context) throws Exception {
        SqlDatabaseProperties configured = context.settings().database();
        SqlDatabaseProperties target = targetDatabase(arguments, context.settings());
        if (!configured.dialect().isExternal() && !target.dialect().isExternal()) {
            throw new CliUsageException("modl already uses the built-in database; give the JDBC URL of the database to copy it to");
        }
        try (OfflineDatabase source = context.openConfiguredDatabase();
             LockedDatabase locked = LockedDatabase.lock(target, context.settings().dataDirectory())) {
            BackupSource copiedFrom = source.source();
            context.requireExistingDatabase(copiedFrom);
            RestoreTarget copiedTo = locked.target();
            List<String> modlTables = copiedTo.modlTables();
            if (!modlTables.isEmpty()) {
                throw new CliFailureException("The target database already has modl tables (" + String.join(", ", modlTables)
                    + "); refusing to overwrite them. Use another database, or remove exactly those tables yourself if you no longer"
                    + " need them. Tables of other software can stay.");
            }
            context.out().println("Copying the database; changes made while it runs are not copied, so keep modl stopped");
            TransferReport report = copy(copiedFrom, copiedTo, context);
            context.out().println("Copied the database (" + report.describe() + ")");
        }
        context.out().println(nextStep(target));
    }

    private static TransferReport copy(BackupSource source, RestoreTarget target, CliContext context) throws IOException {
        return new DatabaseCopy(context.backupOf(source), context.settings().dataDirectory().temporary(), BackupDiskSpace.SYSTEM,
            context.clock()).into(target);
    }

    private static SqlDatabaseProperties targetDatabase(CliArguments arguments, CliSettings settings) {
        String to = arguments.required(TO);
        String url = BUILT_IN.equals(to) ? null : to;
        String username = arguments.optional(USERNAME).orElse(null);
        String password = arguments.optional(PASSWORD_VARIABLE)
            .map(variable -> settings.variable(variable)
                .orElseThrow(() -> new CliUsageException("The environment variable " + variable + " is not set")))
            .orElse(null);
        if (url == null && (username != null || password != null)) {
            throw new CliUsageException("The built-in database takes no --" + USERNAME + " or --" + PASSWORD_VARIABLE);
        }
        try {
            return new SqlDatabaseProperties(url, username, password, SqlDatabaseProperties.DEFAULT_POOL_SIZE,
                settings.database().sqlite());
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("--" + TO + " must be " + BUILT_IN + " or a jdbc:mysql://, jdbc:mariadb://, or jdbc:postgresql://"
                + " URL without a user name or password (pass those with --" + USERNAME + " and --" + PASSWORD_VARIABLE + ")");
        }
    }

    private static String nextStep(SqlDatabaseProperties target) {
        return target.dialect().isExternal()
            ? "Done. Set MODL_DATABASE_URL, MODL_DATABASE_USERNAME, and MODL_DATABASE_PASSWORD to the copy's values to run modl on it."
            : "Done. Remove MODL_DATABASE_URL to run modl on the built-in database.";
    }
}
