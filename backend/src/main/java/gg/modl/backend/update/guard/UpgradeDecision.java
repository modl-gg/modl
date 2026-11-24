package gg.modl.backend.update.guard;

import gg.modl.update.ReleaseVersion;
import gg.modl.update.TransactionKind;
import gg.modl.update.UpdatePhase;
import gg.modl.update.UpdateTransaction;
import org.jspecify.annotations.Nullable;
import java.time.Instant;

public final class UpgradeDecision {
    static final ReleaseVersion BEFORE_FIRST_RELEASE = ReleaseVersion.parse("0.0.0");

    private UpgradeDecision() {
    }

    public record Inputs(
        ReleaseVersion current,
        ReleaseVersion minUpgradeFrom,
        @Nullable UpdateTransaction pending,
        boolean hasModlTables,
        @Nullable String lastRunVersion,
        String databaseId,
        boolean schemaAboveBinary,
        boolean allowDowngrade,
        String newTransactionId,
        Instant now
    ) {
    }

    public sealed interface Action permits Proceed, Refuse, RollBack, Upgrade, Resume {
    }

    public record Proceed(boolean freshInstall) implements Action {
    }

    public record Refuse(String message) implements Action {
    }

    public record RollBack(UpdateTransaction pending, boolean restore) implements Action {
    }

    public record Upgrade(UpdateTransaction transaction, boolean backUp, boolean restoreFirst) implements Action {
    }

    public record Resume(UpdateTransaction transaction) implements Action {
    }

    public static Action decide(Inputs in) {
        UpdateTransaction pending = in.pending();
        if (pending != null) {
            return withPending(in, pending);
        }
        if (!in.hasModlTables()) {
            return new Proceed(true);
        }
        if (in.lastRunVersion() == null) {
            return new Upgrade(newTransaction(in, BEFORE_FIRST_RELEASE), true, false);
        }
        ReleaseVersion recorded = ReleaseVersion.tryParse(in.lastRunVersion());
        if (recorded == null) {
            return new Refuse("The database records an unknown modl version (" + in.lastRunVersion() + "). Use the modl version that"
                + " last ran on it.");
        }
        if (recorded.equals(in.current())) {
            return new Proceed(false);
        }
        if (in.current().isNewerThan(recorded)) {
            if (recorded.isOlderThan(in.minUpgradeFrom())) {
                return new Refuse(installFirst(recorded, in));
            }
            return new Upgrade(newTransaction(in, recorded), true, false);
        }
        if (in.allowDowngrade() && !in.schemaAboveBinary()) {
            return new Proceed(false);
        }
        return new Refuse("This database was last used by modl " + recorded + ", which is newer than this modl " + in.current()
            + ". Start modl " + recorded + " again, or restore a backup made by modl " + in.current() + " into an empty"
            + " database with this version's restore command.");
    }

    private static Action withPending(Inputs in, UpdateTransaction pending) {
        if (pending.databaseId() != null && !pending.databaseId().equals(in.databaseId())) {
            return new Refuse("updates/pending.json belongs to another database than the one configured. Configure that database"
                + " again, or move updates/pending.json away if you know the update never touched this database.");
        }
        ReleaseVersion current = in.current();
        if (current.equals(pending.from())) {
            return rollBack(pending);
        }
        if (current.equals(pending.to())) {
            return continueTo(in, pending);
        }
        if (current.isNewerThan(pending.from()) && current.isNewerThan(pending.to())) {
            boolean touched = pending.phase().mayHaveChangedDatabase();
            boolean restoreFirst = touched && pending.backup() != null;
            UpdateTransaction replacement = UpdateTransaction.builder(in.newTransactionId(), TransactionKind.NONE, pending.from(), current,
                    in.now())
                .databaseId(in.databaseId())
                .backup(pending.backup(), pending.backupSha256())
                .schemaChanged(pending.schemaChanged())
                .phase(touched ? UpdatePhase.MIGRATING : UpdatePhase.STAGED)
                .build();
            return new Upgrade(replacement, pending.backup() == null && !touched, restoreFirst);
        }
        return new Refuse("An update from modl " + pending.from() + " to " + pending.to() + " is unfinished. Start modl "
            + pending.from() + " to roll it back, or modl " + pending.to() + " to finish it.");
    }

    private static Action rollBack(UpdateTransaction pending) {
        if (!pending.phase().mayHaveChangedDatabase()) {
            return new RollBack(pending, false);
        }
        if (pending.backup() != null) {
            return new RollBack(pending, true);
        }
        if (Boolean.FALSE.equals(pending.schemaChanged())) {
            return new RollBack(pending, false);
        }
        return new Refuse("The update from modl " + pending.from() + " to " + pending.to() + " may have changed the database and no"
            + " pre-update backup exists. Restore your own backup, or start modl " + pending.to() + " again.");
    }

    private static Action continueTo(Inputs in, UpdateTransaction pending) {
        UpdatePhase phase = pending.phase();
        if (phase.beforeMigration()) {
            if (!BEFORE_FIRST_RELEASE.equals(pending.from()) && pending.from().isOlderThan(in.minUpgradeFrom())) {
                return new Refuse(installFirst(pending.from(), in));
            }
            return new Upgrade(pending, true, false);
        }
        if (phase == UpdatePhase.STARTING) {
            return new Resume(pending);
        }
        return new Upgrade(pending, false, pending.backup() != null);
    }

    private static UpdateTransaction newTransaction(Inputs in, ReleaseVersion from) {
        return UpdateTransaction.builder(in.newTransactionId(), TransactionKind.NONE, from, in.current(), in.now())
            .databaseId(in.databaseId())
            .build();
    }

    private static String installFirst(ReleaseVersion recorded, Inputs in) {
        return "modl " + in.current() + " upgrades databases from modl " + in.minUpgradeFrom() + " or newer, but this one was last"
            + " used by modl " + recorded + ". Install modl " + in.minUpgradeFrom() + " first, start it once, then this version.";
    }
}
