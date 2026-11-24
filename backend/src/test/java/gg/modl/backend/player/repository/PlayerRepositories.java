package gg.modl.backend.player.repository;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.support.sql.SqlTestDatabase;
import org.jooq.DSLContext;

public record PlayerRepositories(
    JooqPlayerRepository players,
    JooqPunishmentRepository punishments,
    JooqPlayerImportRepository imports,
    JooqPlayerPunishmentLock lock
) {
    public static PlayerRepositories on(SqlTestDatabase database) {
        return on(database, database.dsl());
    }

    public static PlayerRepositories on(SqlTestDatabase database, DSLContext dsl) {
        InsertIfAbsent insertIfAbsent = new InsertIfAbsent(dsl, database.dialect());
        Upserts upserts = new Upserts(dsl, database.dialect());
        ChildRows childRows = new ChildRows(dsl, database.dialect(), insertIfAbsent);
        JooqPunishmentRepository punishments = new JooqPunishmentRepository(dsl, database.unitOfWork(), insertIfAbsent,
            childRows, database.jsonColumns());
        return new PlayerRepositories(
            new JooqPlayerRepository(dsl, database.unitOfWork(), insertIfAbsent, upserts, childRows, database.jsonColumns(),
                database.clock()),
            punishments,
            new JooqPlayerImportRepository(dsl, database.unitOfWork(), insertIfAbsent, upserts, childRows,
                database.jsonColumns(), punishments),
            new JooqPlayerPunishmentLock(childRows));
    }
}
