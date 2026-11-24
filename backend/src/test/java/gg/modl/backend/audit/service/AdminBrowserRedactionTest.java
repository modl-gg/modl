package gg.modl.backend.audit.service;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_VERIFICATIONS;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.audit.repository.JooqAdminTableBrowserRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.io.TempDir;

class AdminBrowserRedactionTest {
    private static final String MARKER = "marker";
    private static final String REDACTED = AdminRowRedactor.REDACTED;
    private static final long UNSAFE_LONG = 1_800_000_000_000_000_123L;
    private static final Instant AT = SqlTestDatabase.START;
    private static final String JSON = "{\"apiKey\":\"leak\",\"nested\":[{\"Access_Token\":\"leak\",\"smtp_password\":\"leak\"}],"
        + "\"plain\":\"marker\"}";
    private static final Map<String, Object> REDACTED_JSON = Map.of(
        "apiKey", REDACTED,
        "nested", List.of(Map.of("Access_Token", REDACTED, "smtp_password", REDACTED)),
        "plain", MARKER);
    private static final List<Table<?>> PARENTS_FIRST = List.of(PLAYERS, SETTINGS, STAFF, STAFF_ROLES, TICKETS,
        TICKET_VERIFICATIONS, AUDIT_LOGS, KNOWLEDGEBASE_CATEGORIES, KNOWLEDGEBASE_ARTICLES, HOMEPAGE_CARDS);

    private final Server server = TestServers.server("Demo", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void players(DialectTarget target) {
        assertOnlyRedacted(target, "players", PLAYERS, Set.of("last_skin_hash"));
    }

    @SqlContractTest
    void settings(DialectTarget target) {
        assertOnlyRedacted(target, "settings", SETTINGS, Set.of("data_json"));
    }

    @SqlContractTest
    void staff(DialectTarget target) {
        assertOnlyRedacted(target, "staffs", STAFF,
            Set.of("two_factor_token_hash", "two_factor_token_ip", "two_factor_token_created_at"));
    }

    @SqlContractTest
    void staffRoles(DialectTarget target) {
        assertOnlyRedacted(target, "staffroles", STAFF_ROLES, Set.of());
    }

    @SqlContractTest
    void tickets(DialectTarget target) {
        assertOnlyRedacted(target, "tickets", TICKETS, Set.of("public_access_key"));
    }

    @SqlContractTest
    void ticketVerifications(DialectTarget target) {
        assertOnlyRedacted(target, "ticket_verifications", TICKET_VERIFICATIONS, Set.of("token_hash", "code_hash"));
    }

    @SqlContractTest
    void auditLogs(DialectTarget target) {
        assertOnlyRedacted(target, "logs", AUDIT_LOGS, Set.of());
    }

    @SqlContractTest
    void knowledgebaseCategories(DialectTarget target) {
        assertOnlyRedacted(target, "knowledgebasecategories", KNOWLEDGEBASE_CATEGORIES, Set.of());
    }

    @SqlContractTest
    void knowledgebaseArticles(DialectTarget target) {
        assertOnlyRedacted(target, "knowledgebasearticles", KNOWLEDGEBASE_ARTICLES, Set.of());
    }

    @SqlContractTest
    void homepageCards(DialectTarget target) {
        assertOnlyRedacted(target, "homepagecards", HOMEPAGE_CARDS, Set.of());
    }

    @SqlContractTest
    void safeSettingsKeepTheirDataExceptForSecrets(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            Map<Field<?>, Object> row = markedRow(SETTINGS);
            row.put(SETTINGS.TYPE, "general");
            database.unitOfWork().write(() -> {
                database.dsl().insertInto(SETTINGS).set(row).execute();
            });

            assertThat(browse(database, "settings").get("data_json")).isEqualTo(REDACTED_JSON);
        }
    }

    private void assertOnlyRedacted(DialectTarget target, String identifier, Table<?> table, Set<String> redacted) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            database.unitOfWork().write(() -> PARENTS_FIRST.forEach(
                each -> database.dsl().insertInto(each).set(markedRow(each)).execute()));

            Map<String, Object> shown = browse(database, identifier);

            Map<String, Object> expected = new LinkedHashMap<>();
            markedRow(table).forEach((field, stored) ->
                expected.put(field.getName(), redacted.contains(field.getName()) ? REDACTED : rendered(field, stored)));
            assertThat(shown).isEqualTo(expected);
            assertThat(shown.toString()).doesNotContain("leak");
        }
    }

    private Map<String, Object> browse(SqlTestDatabase database, String identifier) {
        AdminDatabaseBrowserService service = new AdminDatabaseBrowserService(
            new JooqAdminTableBrowserRepository(database.dsl(), database.unitOfWork()), database.jsonColumns());
        return service.getDatabaseTable(server, identifier, 10, 0).rows().getFirst();
    }

    private static Map<Field<?>, Object> markedRow(Table<?> table) {
        Map<Field<?>, Object> row = new HashMap<>();
        for (Field<?> field : table.fields()) {
            row.put(field, markedValue(field));
        }
        return row;
    }

    private static Object markedValue(Field<?> field) {
        if (field.getType() == Instant.class) {
            return AT;
        }
        if (field.getType() == Long.class) {
            return UNSAFE_LONG;
        }
        if (field.getType() == Integer.class) {
            return 7;
        }
        if (field.getType() == Boolean.class) {
            return true;
        }
        return switch (field.getName()) {
            case "status" -> "open";
            case "priority" -> "high";
            default -> field.getName().endsWith("_json") ? JSON : MARKER;
        };
    }

    private static Object rendered(Field<?> field, Object stored) {
        if (stored instanceof Instant instant) {
            return Long.toString(instant.toEpochMilli());
        }
        if (stored instanceof Long number) {
            return number.toString();
        }
        return field.getName().endsWith("_json") ? REDACTED_JSON : stored;
    }
}
