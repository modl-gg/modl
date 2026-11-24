package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.TicketRepliesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import org.jooq.TableRecord;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.io.TempDir;

class ConstraintViolationsContractTest {
    private static final String PLAYER = "p1";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aDuplicatePrimaryKeyIsADuplicateRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.staff("s1", "a@example.com", "a"));

            DuplicateRowException duplicate = assertThrows(DuplicateRowException.class, () ->
                insertInUnit(database, KernelRows.staff("s1", "b@example.com", "b")));

            assertEquals(ConstraintViolations.Kind.UNIQUE, violations(database).classify(duplicate));
            assertEquals(1, database.dsl().fetchCount(STAFF));
        }
    }

    @SqlContractTest
    void aReplyToAMissingTicketIsAReferenceViolation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            TicketRepliesRecord orphan = new TicketRepliesRecord();
            orphan.setTicketId("missing");
            orphan.setSeq(1);
            orphan.setId("r1");
            orphan.setCreatedAt(KernelRows.NOW);

            ReferenceViolationException violation = assertThrows(ReferenceViolationException.class, () -> insertInUnit(database, orphan));

            assertEquals(ConstraintViolations.Kind.FOREIGN_KEY, violations(database).classify(violation));
            assertEquals(0, database.dsl().fetchCount(TICKET_REPLIES));
        }
    }

    @SqlContractTest
    void aPunishmentWithoutActiveUntilIsAnUntranslatedNotNullViolation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));
            PunishmentsRecord punishment = KernelRows.punishment("pu1", PLAYER);
            punishment.reset(PUNISHMENTS.ACTIVE_UNTIL_AT);

            assertUntranslated(database, ConstraintViolations.Kind.NOT_NULL, punishment);
            assertEquals(0, database.dsl().fetchCount(PUNISHMENTS));
        }
    }

    @SqlContractTest
    void aPunishmentWithoutEnforcementCategoryIsAnUntranslatedNotNullViolation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));
            PunishmentsRecord punishment = KernelRows.punishment("pu1", PLAYER);
            punishment.reset(PUNISHMENTS.ENFORCEMENT_CATEGORY);

            assertUntranslated(database, ConstraintViolations.Kind.NOT_NULL, punishment);
        }
    }

    @SqlContractTest
    void anUnknownEnforcementCategoryIsAnUntranslatedCheckViolation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.player(PLAYER));
            PunishmentsRecord punishment = KernelRows.punishment("pu1", PLAYER);
            punishment.setEnforcementCategory("X");

            assertUntranslated(database, ConstraintViolations.Kind.CHECK, punishment);
        }
    }

    @SqlContractTest
    void anUnknownTicketStatusIsAnUntranslatedCheckViolation(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            TicketsRecord ticket = KernelRows.ticket("t1");
            ticket.setStatus("bogus");

            assertUntranslated(database, ConstraintViolations.Kind.CHECK, ticket);
            assertEquals(0, database.dsl().fetchCount(TICKETS));
        }
    }

    @SqlContractTest
    void aValueLongerThanItsColumnIsTooLongOnExternalDatabases(DialectTarget target) {
        assumeTrue(target.dialect().isExternal(), "SQLite does not enforce lengths; TextLimits guards them");
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            TicketsRecord ticket = KernelRows.ticket("t1");
            ticket.setType("x".repeat(TextLimits.limit(TICKETS.TYPE) + 1));

            ValueTooLongException tooLong = assertThrows(ValueTooLongException.class, () -> insertInUnit(database, ticket));

            assertEquals(ConstraintViolations.Kind.TOO_LONG, violations(database).classify(tooLong));
            assertEquals(0, database.dsl().fetchCount(TICKETS));
        }
    }

    private static void assertUntranslated(SqlTestDatabase database, ConstraintViolations.Kind expected, TableRecord<?> row) {
        DataAccessException failure = assertThrows(DataAccessException.class, () -> insertInUnit(database, row));
        assertEquals(expected, violations(database).classify(failure));
    }

    private static void insertInUnit(SqlTestDatabase database, TableRecord<?> row) {
        database.unitOfWork().write(() -> database.dsl().insertInto(row.getTable()).set(row).execute());
    }

    private static ConstraintViolations violations(SqlTestDatabase database) {
        return database.bean(ConstraintViolations.class);
    }
}
