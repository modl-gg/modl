package gg.modl.backend.database.selection;

import gg.modl.backend.database.DataDirectoryProperties;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import gg.modl.backend.instance.PanelLocations;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class DatabaseSelection {
    public static final String NOT_REACHED_CODE = "DATABASE_NOT_REACHED_AT_START";
    public static final String COPY_MISSING_CODE = "DATABASE_COPY_MISSING_AT_START";
    private static final String COPY_MISSING_MESSAGE = "The new database does not hold the copy modl made";

    public enum Source {
        CONFIGURATION,
        PANEL,
        DEFAULT
    }

    private final SqlDatabaseProperties configured;
    private final SqlDatabaseProperties effective;
    private final Source source;
    private final DatabaseLocation currentLocation;
    private final boolean currentUsesTls;
    private final DatabaseChoiceFile file;
    private PanelDatabaseChoice choice;

    private DatabaseSelection(SqlDatabaseProperties configured, SqlDatabaseProperties effective, Source source,
                              DatabaseChoiceFile file, PanelDatabaseChoice choice) {
        this.configured = configured;
        this.effective = effective;
        this.source = source;
        this.currentLocation = effective.externalUrl()
            .map(url -> source == Source.CONFIGURATION ? DatabaseLocation.ofUrl(effective.dialect(), url) : choice.selected().location())
            .orElse(DatabaseLocation.BUILT_IN);
        this.currentUsesTls = source != Source.CONFIGURATION && choice.selected().tls();
        this.file = file;
        this.choice = choice;
    }

    public static DatabaseSelection resolve(SqlDatabaseProperties configured, DataDirectoryProperties dataDirectory,
                                            PendingTargetProbe probe, ArrivalWait wait, OperatorNotice notice) {
        DatabaseChoiceFile file = new DatabaseChoiceFile(dataDirectory.root());
        if (configured.externalUrl().isPresent()) {
            retireOverridden(file, notice);
            return new DatabaseSelection(configured, configured, Source.CONFIGURATION, file, PanelDatabaseChoice.initial());
        }
        PanelDatabaseChoice stored = readRequired(file).orElseGet(PanelDatabaseChoice::initial);
        if (stored.pending()) {
            PendingTargetCheck.Result arrival = arrive(probe, stored, configured, wait, notice);
            if (!arrival.ready()) {
                PanelDatabaseChoice fellBack = stored.fellBack(startFailure(arrival));
                write(file, fellBack);
                announceFallBack(notice, arrival, stored.selected(), fellBack.selected());
                stored = fellBack;
            }
        }
        Source source = stored.selected().isBuiltIn() ? Source.DEFAULT : Source.PANEL;
        return new DatabaseSelection(configured, stored.selected().properties(configured), source, file, stored);
    }

    public static SqlDatabaseProperties forTools(SqlDatabaseProperties configured, DataDirectoryProperties dataDirectory) {
        if (configured.externalUrl().isPresent()) {
            return configured;
        }
        Optional<PanelDatabaseChoice> stored = readRequired(new DatabaseChoiceFile(dataDirectory.root()));
        stored.filter(PanelDatabaseChoice::pending).ifPresent(moving -> {
            throw new IllegalStateException("modl copied its data to " + moving.selected().describe() + " and finishes or undoes"
                + " that database move when it starts. Start modl once, then run this command again");
        });
        return stored.map(choice -> choice.selected().properties(configured)).orElse(configured);
    }

    public SqlDatabaseProperties effective() {
        return effective;
    }

    public SqlDatabaseProperties configured() {
        return configured;
    }

    public Source source() {
        return source;
    }

    public boolean managed() {
        return source == Source.CONFIGURATION;
    }

    public DatabaseLocation currentLocation() {
        return currentLocation;
    }

    public Optional<String> currentUsername() {
        return Optional.ofNullable(effective.username());
    }

    public boolean currentUsesTls() {
        return currentUsesTls;
    }

    public String choiceFile() {
        return file.path().toString();
    }

    public synchronized PanelDatabaseChoice choice() {
        return choice;
    }

    public synchronized boolean adopting() {
        return !managed() && choice.pending();
    }

    public synchronized void adopt() {
        update(choice.adopted());
    }

    public synchronized Optional<StartReport> settleStart() {
        if (managed() || choice.pending() || choice.settled()) {
            return Optional.empty();
        }
        Optional<StartReport> report = StartReport.of(choice);
        try {
            update(choice.reported());
        } catch (UncheckedIOException e) {
            log.warn("The finished database start could not be recorded in {}; it is reported again at the next start", file.path(), e);
        }
        return report;
    }

    public synchronized DatabaseConnection abandonPending(String reason) {
        update(choice.fellBack(new MoveFailure(COPY_MISSING_CODE, COPY_MISSING_MESSAGE, reason)));
        return choice.selected();
    }

    public synchronized void recordCopying(DatabaseLocation target, String moveToken) {
        update(choice.copying(target, moveToken));
    }

    public synchronized void recordFailure(DatabaseLocation target, MoveFailure failure) {
        update(choice.failed(target, failure));
    }

    public synchronized void recordMoved(DatabaseConnection target) {
        update(choice.movedTo(target));
    }

    private void update(PanelDatabaseChoice next) {
        write(file, next);
        choice = next;
    }

    private static void write(DatabaseChoiceFile file, PanelDatabaseChoice choice) {
        try {
            file.write(choice);
        } catch (IOException e) {
            throw new UncheckedIOException("The database choice could not be saved to " + file.path(), e);
        }
    }

    private static PendingTargetCheck.Result arrive(PendingTargetProbe probe, PanelDatabaseChoice stored,
                                                    SqlDatabaseProperties configured, ArrivalWait wait, OperatorNotice notice) {
        String token = stored.moveToken();
        if (token == null) {
            return PendingTargetCheck.Result.refused(PendingTargetCheck.NO_COPY);
        }
        SqlDatabaseProperties target = stored.selected().properties(configured);
        long started = wait.now();
        PendingTargetCheck.Result result = probe.check(target, token);
        Duration announced = Duration.ZERO;
        Duration remaining = wait.remaining(result.outcome().waitBudget(), started);
        while (remaining.isPositive()) {
            Duration budget = result.outcome().waitBudget();
            if (budget.compareTo(announced) > 0) {
                notice.publish("Waiting up to {} seconds for {} to answer.", budget.toSeconds(), stored.selected().describe());
                announced = budget;
            }
            try {
                wait.pauseWithin(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("modl stopped while waiting for " + stored.selected().describe() + " to answer", e);
            }
            result = probe.check(target, token);
            remaining = wait.remaining(result.outcome().waitBudget(), started);
        }
        return result;
    }

    private static MoveFailure startFailure(PendingTargetCheck.Result arrival) {
        return arrival.outcome() == PendingTargetCheck.Outcome.REFUSED
            ? new MoveFailure(COPY_MISSING_CODE, COPY_MISSING_MESSAGE, arrival.reason())
            : new MoveFailure(NOT_REACHED_CODE, "The new database could not be reached at the start", arrival.reason());
    }

    private static void announceFallBack(OperatorNotice notice, PendingTargetCheck.Result arrival, DatabaseConnection abandoned,
                                         DatabaseConnection kept) {
        if (arrival.outcome() == PendingTargetCheck.Outcome.REFUSED) {
            notice.publish("{} does not hold the copy modl made ({}), so modl keeps using {}. Move again in {}.",
                abandoned.describe(), arrival.reason(), kept.describe(), PanelLocations.DATABASE);
            return;
        }
        notice.publish("modl could not reach {} ({}), so it keeps using {}. Fix the connection, then move again in {}.",
            abandoned.describe(), arrival.reason(), kept.describe(), PanelLocations.DATABASE);
    }

    private static Optional<PanelDatabaseChoice> readRequired(DatabaseChoiceFile file) {
        try {
            return file.read();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage() + ". Restore it, or remove it to use the built-in SQLite database again;"
                + " MODL_DATABASE_URL also overrides it.", e);
        }
    }

    private static void retireOverridden(DatabaseChoiceFile file, OperatorNotice notice) {
        try {
            file.retire().ifPresent(kept -> notice.publish("The database is set by MODL_DATABASE_URL or connection.yml, so modl no"
                + " longer uses the one chosen in the panel. Its choice was kept as {}.", kept));
        } catch (IOException e) {
            log.warn("Could not set aside {}, which the configured database overrides: {}", file.path(), e.getMessage());
        }
    }

    public sealed interface StartReport {
        static Optional<StartReport> of(PanelDatabaseChoice choice) {
            PanelDatabaseChoice.StartEnding ending = choice.unreported();
            FailedMove failed = choice.failure();
            if (ending == PanelDatabaseChoice.StartEnding.ARRIVED) {
                return Optional.of(new Arrived(choice.selected()));
            }
            if (ending == PanelDatabaseChoice.StartEnding.FELL_BACK && failed != null) {
                return Optional.of(new FellBack(failed.target(), choice.selected(), failed.failure().detail()));
            }
            return Optional.empty();
        }

        record Arrived(DatabaseConnection now) implements StartReport {
        }

        record FellBack(DatabaseLocation abandoned, DatabaseConnection kept, String reason) implements StartReport {
        }
    }
}
