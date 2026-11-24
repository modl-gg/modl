package gg.modl.backend.database.selection;

import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.sql.DatabaseDeparture;
import gg.modl.backend.database.sql.DepartureMarks;
import gg.modl.backend.database.sql.MigrationGuard;
import gg.modl.backend.database.sql.PendingTargetCheck;
import gg.modl.backend.infrastructure.logging.OperatorNotice;
import java.util.Optional;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;

@Slf4j
public final class DepartureGuard implements MigrationGuard, Ordered {
    public static final String REOPEN_SWITCH = "MODL_DATABASE_REOPEN";

    private final DatabaseSelection selection;
    private final DatabaseDialect dialect;
    private final @Nullable String reopenedMove;
    private final OperatorNotice notice;

    public DepartureGuard(DatabaseSelection selection, DatabaseDialect dialect, @Nullable String reopenedMove,
                          OperatorNotice notice) {
        this.selection = selection;
        this.dialect = dialect;
        this.reopenedMove = reopenedMove;
        this.notice = notice;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeMigration(Flyway flyway) {
        DataSource connections = flyway.getConfiguration().getDataSource();
        Optional<DatabaseDeparture> departure = DepartureMarks.read(connections, dialect);
        if (selection.adopting()) {
            arrive(connections, departure);
            return;
        }
        departure.ifPresent(left -> settle(connections, left));
    }

    private void arrive(DataSource connections, Optional<DatabaseDeparture> departure) {
        PanelDatabaseChoice choice = selection.choice();
        DatabaseConnection target = choice.selected();
        if (departure.filter(copy -> choice.startedMove(copy.moveToken())).isEmpty()) {
            DatabaseConnection kept = selection.abandonPending(PendingTargetCheck.NO_COPY);
            throw refusal(target.describe() + " does not hold the copy modl made, so the next start uses " + kept.describe()
                + " again. Restart modl");
        }
        selection.adopt();
        DepartureMarks.clear(connections, dialect);
    }

    private void settle(DataSource connections, DatabaseDeparture left) {
        PanelDatabaseChoice choice = selection.choice();
        if (choice.startedMove(left.moveToken())) {
            DepartureMarks.clear(connections, dialect);
            if (choice.arrived()) {
                log.info("Finished arriving on this database from the move of {}", left.movedAt());
            } else {
                log.info("Cleared the record of a database move to {} that did not finish", left.movedTo());
            }
            return;
        }
        if (reopenedMove != null && left.belongsTo(reopenedMove)) {
            DepartureMarks.clear(connections, dialect);
            notice.publish("modl reopened this database, which was moved to {} at {}. Remove {} again.", left.movedTo(),
                left.movedAt(), REOPEN_SWITCH);
            return;
        }
        throw refusal("this database was moved to " + left.movedTo() + " at " + left.movedAt() + ". Use that database, or set "
            + REOPEN_SWITCH + "=" + left.moveToken() + " for one start to use this copy again");
    }

    private IllegalStateException refusal(String reason) {
        notice.publish("modl cannot start: {}.", reason);
        return new IllegalStateException(reason);
    }
}
