package gg.modl.backend.database;

import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.modl.backend.database.jooq.Indexes;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class InsertIfAbsentContractTest {
    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aNamedUniqueTargetInsertsOnceThenReportsTheRowPresent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            boolean first = database.unitOfWork().write(() -> database.insertIfAbsent().on(STAFF, Indexes.UQ_STAFF_EMAIL)
                .insert(KernelRows.staff("s1", "owner@example.com", "owner")));
            boolean second = database.unitOfWork().write(() -> database.insertIfAbsent().on(STAFF, Indexes.UQ_STAFF_EMAIL)
                .insert(KernelRows.staff("s2", "owner@example.com", "other")));

            assertTrue(first);
            assertFalse(second);
            assertEquals(List.of("s1"), database.dsl().select(STAFF.ID).from(STAFF).fetch(STAFF.ID));
        }
    }

    @SqlContractTest
    void aPrimaryKeyTargetInsertsOnceThenReportsTheRowPresent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            boolean first = database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(STAFF)
                .insert(KernelRows.staff("s1", "a@example.com", "a")));
            boolean second = database.unitOfWork().write(() -> database.insertIfAbsent().onPrimaryKey(STAFF)
                .insert(KernelRows.staff("s1", "b@example.com", "b")));

            assertTrue(first);
            assertFalse(second);
            assertEquals("a@example.com", database.dsl().fetchSingle(STAFF, STAFF.ID.eq("s1")).getEmail());
        }
    }

    @SqlContractTest
    void aConflictOnAnotherUniqueIndexThanTheTargetRaisesDuplicateRow(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.staff("s1", "a@example.com", "taken"));

            assertThrows(DuplicateRowException.class, () -> database.unitOfWork().write(() ->
                database.insertIfAbsent().on(STAFF, Indexes.UQ_STAFF_EMAIL).insert(KernelRows.staff("s2", "b@example.com", "taken"))));
            assertEquals(1, database.dsl().fetchCount(STAFF));
        }
    }

    @SqlContractTest
    void anyUniqueInsertReportsAConflictOnEveryUniqueIndex(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            boolean first = insertAnyUnique(database, "s1", "a@example.com", "a");
            boolean samePrimaryKey = insertAnyUnique(database, "s1", "b@example.com", "b");
            boolean sameEmail = insertAnyUnique(database, "s2", "a@example.com", "c");
            boolean sameUsername = insertAnyUnique(database, "s3", "d@example.com", "a");
            boolean distinct = insertAnyUnique(database, "s4", "e@example.com", "e");

            assertTrue(first);
            assertFalse(samePrimaryKey);
            assertFalse(sameEmail);
            assertFalse(sameUsername);
            assertTrue(distinct);
            assertEquals(2, database.dsl().fetchCount(STAFF));
        }
    }

    @SqlContractTest
    void aFalseNamedInsertLeavesTheUnitUsableForAReadAnInsertAndAnUpdate(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.staff("s1", "a@example.com", "a"));

            String classified = database.unitOfWork().write(() -> {
                boolean inserted = database.insertIfAbsent().on(STAFF, Indexes.UQ_STAFF_EMAIL)
                    .insert(KernelRows.staff("s2", "a@example.com", "b"));
                assertFalse(inserted);
                String existing = database.dsl().select(STAFF.ID).from(STAFF).where(STAFF.EMAIL.eq("a@example.com")).fetchSingle(STAFF.ID);
                assertTrue(database.insertIfAbsent().on(STAFF, Indexes.UQ_STAFF_EMAIL).insert(KernelRows.staff("s3", "c@example.com", "c")));
                database.dsl().update(STAFF).set(STAFF.USERNAME, "renamed").where(STAFF.ID.eq(existing)).execute();
                return existing;
            });

            assertEquals("s1", classified);
            assertEquals("renamed", database.dsl().fetchSingle(STAFF, STAFF.ID.eq("s1")).getUsername());
            assertEquals(2, database.dsl().fetchCount(STAFF));
        }
    }

    @SqlContractTest
    void aFalseAnyUniqueInsertOnAUsernameCollisionLeavesTheUnitUsableForAClassificationAndASecondInsert(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.staff("s1", "a@example.com", "taken"));

            database.unitOfWork().write(() -> {
                assertFalse(database.insertIfAbsent().onAnyUnique(STAFF).insert(KernelRows.staff("s2", "b@example.com", "taken")));
                assertTrue(database.dsl().fetchExists(STAFF, STAFF.USERNAME.eq("taken")));
                assertTrue(database.insertIfAbsent().onAnyUnique(STAFF).insert(KernelRows.staff("s2", "b@example.com", "taken-2")));
            });

            assertEquals("taken-2", database.dsl().fetchSingle(STAFF, STAFF.ID.eq("s2")).getUsername());
        }
    }

    @SqlContractTest
    void aFalsePrimaryKeyInsertFollowedByAnUpdateCommits(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KernelRows.insert(database, KernelRows.settings("general"));

            database.unitOfWork().write(() -> {
                assertFalse(database.insertIfAbsent().onPrimaryKey(SETTINGS).insert(KernelRows.settings("general")));
                database.dsl().update(SETTINGS).set(SETTINGS.VERSION, SETTINGS.VERSION.plus(1L)).where(SETTINGS.TYPE.eq("general")).execute();
            });

            assertEquals(1L, database.dsl().fetchSingle(SETTINGS, SETTINGS.TYPE.eq("general")).getVersion());
        }
    }

    private static boolean insertAnyUnique(SqlTestDatabase database, String id, String email, String username) {
        return database.unitOfWork().write(() -> database.insertIfAbsent().onAnyUnique(STAFF).insert(KernelRows.staff(id, email, username)));
    }
}
