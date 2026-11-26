package gg.modl.backend.database.sql;

import static gg.modl.backend.database.jooq.Tables.DATABASE_DEPARTURE;
import static gg.modl.backend.database.jooq.Tables.INSTANCE_SECRETS;
import static gg.modl.backend.database.jooq.Tables.INSTANCE_STATE;
import static gg.modl.backend.database.jooq.Tables.IP_LOOKUPS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static gg.modl.backend.database.jooq.Tables.SERVER_KEYS;
import static gg.modl.backend.database.jooq.Tables.SETUP_STEPS;
import static gg.modl.backend.database.jooq.Tables.STORAGE_TRANSFERS;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.support.sql.SqlTestDatabase.START;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.modl.backend.database.ConstraintViolations;
import gg.modl.backend.database.DatabaseDialect;
import gg.modl.backend.database.jooq.DefaultSchema;
import gg.modl.backend.database.jooq.Tables;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.support.sql.TestDialects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class SchemaEqualsModelTest {
    private static final Set<String> WITHOUT_ROWID_TABLES = Set.of(
        "modl_punishment_modifications", "modl_punishment_notes", "modl_punishment_evidence", "modl_player_notes",
        "modl_ticket_notes", "modl_player_notifications", "modl_player_ips", "modl_player_usernames", "modl_player_links",
        "modl_ticket_tags", "modl_ticket_assignees", "modl_staff_role_permissions", "modl_staff_ticket_subscriptions",
        "modl_punishment_tickets", "modl_setup_steps", "modl_staff_credentials", "modl_staff_totp", "modl_staff_recovery_codes");
    private static final Set<String> NON_TIME_BIGINT_COLUMNS = Set.of(
        "modl_settings.version", "modl_players.total_playtime_seconds", "modl_player_ips.login_count",
        "modl_punishments.duration_ms", "modl_punishment_modifications.effective_duration_ms", "modl_punishment_evidence.file_size",
        "modl_webauthn_credentials.signature_count", "modl_replays.file_size", "modl_replay_shares.view_count",
        "modl_storage_files.size_bytes",
        "modl_storage_reservations.reserved_bytes", "modl_chat_logs.id", "modl_command_logs.id", "modl_audit_logs.id",
        "modl_staff_totp.last_used_step", "modl_storage_transfers.copied_objects", "modl_storage_transfers.copied_bytes",
        "modl_storage_transfers.skipped_objects", "modl_storage_transfers.failed_objects");
    private static final Set<String> ASCII_COLUMNS = Set.of("modl_webauthn_credentials.credential_id");
    private static final String PLAYER_ID = "p1";
    private static final Map<String, Consumer<DSLContext>> CHECK_VIOLATIONS = Map.ofEntries(
        Map.entry("modl_instance: id = 1", dsl -> dsl.insertInto(Tables.INSTANCE)
            .set(Tables.INSTANCE.ID, 2).set(Tables.INSTANCE.SERVER_NAME, "modl").set(Tables.INSTANCE.CREATED_AT, START).set(Tables.INSTANCE.UPDATED_AT, START)
            .execute()),
        Map.entry("modl_instance_secrets: id = 1", dsl -> dsl.insertInto(INSTANCE_SECRETS)
            .set(INSTANCE_SECRETS.ID, 2).set(INSTANCE_SECRETS.AUTH_CODE_HASH_SECRET, "secret")
            .set(INSTANCE_SECRETS.STORAGE_SIGNING_KEY, "key").set(INSTANCE_SECRETS.CREATED_AT, START)
            .execute()),
        Map.entry("modl_database_departure: id = 1", dsl -> dsl.insertInto(DATABASE_DEPARTURE)
            .set(DATABASE_DEPARTURE.ID, 2).set(DATABASE_DEPARTURE.MOVE_TOKEN, "token").set(DATABASE_DEPARTURE.MOVED_TO, "copy")
            .set(DATABASE_DEPARTURE.MOVED_AT, START)
            .execute()),
        Map.entry("modl_instance_state: id = 1", dsl -> dsl.insertInto(INSTANCE_STATE)
            .set(INSTANCE_STATE.ID, 2).set(INSTANCE_STATE.LAST_RUN_VERSION, "3.0.0").set(INSTANCE_STATE.VERSION_CHANGED_AT, START)
            .execute()),
        Map.entry("modl_setup_steps: status IN ('PENDING', 'DONE', 'SKIPPED')", dsl -> dsl.insertInto(SETUP_STEPS)
            .set(SETUP_STEPS.STEP, "owner").set(SETUP_STEPS.STATUS, "STARTED").set(SETUP_STEPS.UPDATED_AT, START)
            .execute()),
        Map.entry("modl_player_notes: kind IN ('NOTE', 'WARNING')", dsl -> dsl.insertInto(PLAYER_NOTES)
            .set(PLAYER_NOTES.PLAYER_ID, PLAYER_ID).set(PLAYER_NOTES.SEQ, 1).set(PLAYER_NOTES.ID, "n1")
            .set(PLAYER_NOTES.BODY, "note").set(PLAYER_NOTES.KIND, "BAN").set(PLAYER_NOTES.CREATED_AT, START)
            .execute()),
        Map.entry("modl_punishments: enforcement_category IN ('BAN', 'MUTE', 'NONE')", dsl -> dsl.insertInto(PUNISHMENTS)
            .set(PUNISHMENTS.ID, "pun1").set(PUNISHMENTS.PLAYER_ID, PLAYER_ID).set(PUNISHMENTS.TYPE_ORDINAL, 1)
            .set(PUNISHMENTS.ISSUED_AT, START).set(PUNISHMENTS.ENFORCEMENT_CATEGORY, "KICK").set(PUNISHMENTS.ACTIVE_UNTIL_AT, START)
            .execute()),
        Map.entry("modl_tickets: status IN ('unfinished', 'open', 'closed')", dsl -> ticket(dsl, "pending", "normal")),
        Map.entry("modl_tickets: priority IN ('low', 'normal', 'high')", dsl -> ticket(dsl, "open", "urgent")),
        Map.entry("modl_replays: status IN ('PENDING', 'COMPLETE', 'FAILED')", dsl -> dsl.insertInto(REPLAYS)
            .set(REPLAYS.ID, "r1").set(REPLAYS.STATUS, "LOST").set(REPLAYS.CREATED_AT, START)
            .execute()),
        Map.entry("modl_server_keys: scope IN ('FULL', 'BEHIND_PROXY')", dsl -> dsl.insertInto(SERVER_KEYS)
            .set(SERVER_KEYS.ID, "k1").set(SERVER_KEYS.NAME, "lobby").set(SERVER_KEYS.NAME_LOWER, "lobby")
            .set(SERVER_KEYS.SCOPE, "MEMBER").set(SERVER_KEYS.KEY_HASH, "h").set(SERVER_KEYS.KEY_HINT, "hint")
            .set(SERVER_KEYS.CREATED_AT, START)
            .execute()),
        Map.entry("modl_ip_lookups: vpn IN ('YES', 'NO', 'UNKNOWN')", dsl -> dsl.insertInto(IP_LOOKUPS)
            .set(IP_LOOKUPS.IP, "203.0.113.1").set(IP_LOOKUPS.PROVIDER, "test").set(IP_LOOKUPS.VPN, "MAYBE")
            .set(IP_LOOKUPS.LOOKED_UP_AT, START)
            .execute()),
        Map.entry("modl_storage_transfers: source_backend IN ('LOCAL', 'S3')", dsl -> transfer(dsl, "t1", "FTP", "S3", "RUNNING")),
        Map.entry("modl_storage_transfers: target_backend IN ('LOCAL', 'S3')", dsl -> transfer(dsl, "t2", "LOCAL", "FTP", "RUNNING")),
        Map.entry("modl_storage_transfers: state IN ('RUNNING', 'FAILED', 'DONE')", dsl -> transfer(dsl, "t3", "LOCAL", "S3", "LOST")));
    private static final Pattern CREATE_TABLE_OR_CHECK = Pattern.compile("CREATE TABLE (\\w+)|\\bCHECK \\(");
    private static final Map<String, LiveSchema> LIVE = new ConcurrentHashMap<>();

    @TempDir
    static Path dataDirectories;

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theDatabaseHoldsExactlyTheModelTables(DialectTarget target) {
        assertEquals(new TreeSet<>(model().keySet()), new TreeSet<>(live(target).tables().keySet()));
    }

    @SqlContractTest
    void everyTableHasTheModelColumnsInOrder(DialectTarget target) {
        assertMatchesModel(perTable(model(), table -> columnNames(table)), perTable(live(target).tables(), table -> columnNames(table)));
    }

    @SqlContractTest
    void everyColumnHasTheModelNullability(DialectTarget target) {
        assertMatchesModel(perColumn(model(), TableShape.Column::nullable), perColumn(live(target).tables(), TableShape.Column::nullable));
    }

    @SqlContractTest
    void everyColumnHasTheModelJavaType(DialectTarget target) {
        assertMatchesModel(perColumn(model(), TableShape.Column::javaType), perColumn(live(target).tables(), TableShape.Column::javaType));
    }

    @SqlContractTest
    void everyVarcharColumnHasTheModelLength(DialectTarget target) {
        assertMatchesModel(perColumn(model(), TableShape.Column::length), perColumn(live(target).tables(), TableShape.Column::length));
    }

    @SqlContractTest
    void everyColumnHasTheModelDefault(DialectTarget target) {
        assertMatchesModel(perColumn(model(), TableShape.Column::defaultValue), perColumn(live(target).tables(), TableShape.Column::defaultValue));
    }

    @SqlContractTest
    void everyTextColumnHasTheDialectCollationAndNoOtherColumnHasOne(DialectTarget target) {
        Map<String, String> expected = new TreeMap<>();
        model().values().forEach(table -> table.columns().stream()
            .filter(column -> ColumnTypes.isText(column.javaType()))
            .forEach(column -> {
                String key = LiveSchema.columnKey(table.name(), column.name());
                expected.put(key, expectedCollation(target.dialect(), key));
            }));

        assertMatchesModel(expected, live(target).collations());
    }

    @SqlContractTest
    void everyTableHasTheModelPrimaryKey(DialectTarget target) {
        assertMatchesModel(perTable(model(), TableShape::primaryKey), perTable(live(target).tables(), TableShape::primaryKey));
    }

    @SqlContractTest
    void everyTableHasTheModelIndexes(DialectTarget target) {
        assertMatchesModel(perTable(model(), TableShape::indexes),
            perTable(live(target).tables(), SchemaEqualsModelTest::indexesAsSqliteCodegenReadsThem));
    }

    @SqlContractTest
    void everyTableHasTheModelForeignKeysWithTheirDeleteRules(DialectTarget target) {
        Function<TableShape, Set<TableShape.ForeignKey>> foreignKeys = target.isSqlite()
            ? table -> table.foreignKeys().stream().map(TableShape.ForeignKey::unnamed).collect(Collectors.toSet())
            : TableShape::foreignKeys;

        assertMatchesModel(perTable(model(), foreignKeys), perTable(live(target).tables(), foreignKeys));
    }

    @SqlContractTest
    void everyCheckConstraintRejectsAViolatingRow(DialectTarget target) {
        assertEquals(new TreeSet<>(declaredChecks(target.dialect())), new TreeSet<>(CHECK_VIOLATIONS.keySet()));

        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            database.unitOfWork().write(() -> player(database.dsl()));
            ConstraintViolations violations = database.bean(ConstraintViolations.class);
            Map<String, ConstraintViolations.Kind> outcomes = new TreeMap<>();
            CHECK_VIOLATIONS.forEach((check, violation) -> outcomes.put(check, outcome(database, violations, violation)));

            Map<String, ConstraintViolations.Kind> expected = new TreeMap<>();
            CHECK_VIOLATIONS.keySet().forEach(check -> expected.put(check, ConstraintViolations.Kind.CHECK));
            assertEquals(expected, outcomes);
        }
    }

    @Test
    void theSqliteWithoutRowidTablesAreExactlyTheDesignedSet() {
        assertEquals(new TreeSet<>(WITHOUT_ROWID_TABLES), live(TestDialects.SQLITE).withoutRowidTables());
    }

    @Test
    void everyMillisecondColumnEndsInAtAndGeneratesAsInstant() {
        Set<String> nonTimeBigints = new TreeSet<>();
        Set<String> misnamedOrMistyped = new TreeSet<>();
        for (Table<?> table : DefaultSchema.DEFAULT_SCHEMA.getTables()) {
            for (Field<?> field : table.fields()) {
                String key = LiveSchema.columnKey(table.getName(), field.getName());
                boolean bigint = field.getDataType().getSQLDataType().getSQLType() == Types.BIGINT;
                boolean timeNamed = field.getName().endsWith("_at");
                boolean instant = field.getType() == Instant.class;
                if (bigint && !timeNamed) {
                    nonTimeBigints.add(key);
                }
                if (timeNamed != instant || (instant && !bigint)) {
                    misnamedOrMistyped.add(key);
                }
            }
        }

        assertEquals(new TreeSet<>(NON_TIME_BIGINT_COLUMNS), nonTimeBigints);
        assertEquals(Set.of(), misnamedOrMistyped);
    }

    private LiveSchema live(DialectTarget target) {
        return LIVE.computeIfAbsent(target.name(), name -> readLiveSchema(target));
    }

    private static LiveSchema readLiveSchema(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, Files.createDirectories(dataDirectories.resolve(target.name())));
             Connection connection = database.bean(DataSource.class).getConnection()) {
            return LiveSchema.read(connection, target.dialect());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the " + target + " schema", e);
        }
    }

    private static Map<String, TableShape> model() {
        return ModelSchema.tables();
    }

    private static List<String> columnNames(TableShape table) {
        return table.columns().stream().map(TableShape.Column::name).toList();
    }

    private static <T> Map<String, T> perTable(Map<String, TableShape> tables, Function<TableShape, T> property) {
        Map<String, T> values = new TreeMap<>();
        tables.forEach((name, table) -> values.put(name, property.apply(table)));
        return values;
    }

    private static <T> Map<String, T> perColumn(Map<String, TableShape> tables, Function<TableShape.Column, T> property) {
        Map<String, T> values = new TreeMap<>();
        tables.values().forEach(table -> table.columns()
            .forEach(column -> values.put(LiveSchema.columnKey(table.name(), column.name()), property.apply(column))));
        return values;
    }

    private static Set<TableShape.Index> indexesAsSqliteCodegenReadsThem(TableShape table) {
        if (!WITHOUT_ROWID_TABLES.contains(table.name())) {
            return table.indexes();
        }
        return table.indexes().stream()
            .map(index -> new TableShape.Index(index.name(), withPrimaryKeySuffix(index.columns(), table.primaryKey()), index.unique()))
            .collect(Collectors.toSet());
    }

    private static List<String> withPrimaryKeySuffix(List<String> columns, List<String> primaryKey) {
        List<String> carried = new ArrayList<>(columns);
        primaryKey.stream().filter(column -> !columns.contains(column)).forEach(carried::add);
        return List.copyOf(carried);
    }

    private static <T> void assertMatchesModel(Map<String, T> model, Map<String, T> live) {
        Set<String> keys = new TreeSet<>(model.keySet());
        keys.addAll(live.keySet());
        String differences = keys.stream()
            .filter(key -> !Objects.equals(model.get(key), live.get(key)) || model.containsKey(key) != live.containsKey(key))
            .map(key -> key + ": model " + describe(model, key) + ", database " + describe(live, key))
            .collect(Collectors.joining("\n"));
        assertEquals("", differences);
    }

    private static String describe(Map<String, ?> values, String key) {
        return values.containsKey(key) ? String.valueOf(values.get(key)) : "<absent>";
    }

    private static String expectedCollation(DatabaseDialect dialect, String column) {
        return switch (dialect) {
            case SQLITE -> "BINARY";
            case POSTGRESQL -> "C";
            case MYSQL, MARIADB -> ASCII_COLUMNS.contains(column) ? "ascii_bin" : "utf8mb4_bin";
        };
    }

    private static ConstraintViolations.Kind outcome(SqlTestDatabase database, ConstraintViolations violations,
                                                     Consumer<DSLContext> violation) {
        try {
            database.unitOfWork().write(() -> violation.accept(database.dsl()));
            return null;
        } catch (RuntimeException failure) {
            return violations.classify(failure);
        }
    }

    private static Set<String> declaredChecks(DatabaseDialect dialect) {
        Set<String> checks = new TreeSet<>();
        for (String migration : migrations(dialect)) {
            Matcher match = CREATE_TABLE_OR_CHECK.matcher(migration);
            String table = null;
            while (match.find()) {
                if (match.group(1) != null) {
                    table = match.group(1);
                } else {
                    checks.add(table + ": " + balancedContent(migration, match.end() - 1));
                }
            }
        }
        return checks;
    }

    private static String balancedContent(String text, int openParenthesis) {
        int depth = 0;
        for (int index = openParenthesis; index < text.length(); index++) {
            char character = text.charAt(index);
            depth += character == '(' ? 1 : character == ')' ? -1 : 0;
            if (depth == 0) {
                return text.substring(openParenthesis + 1, index).replaceAll("\\s+", " ").trim();
            }
        }
        throw new IllegalStateException("Unbalanced CHECK at " + openParenthesis);
    }

    private static List<String> migrations(DatabaseDialect dialect) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + dialect.migrationLocation() + "/*.sql");
            return Arrays.stream(resources).map(SchemaEqualsModelTest::content).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String content(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void player(DSLContext dsl) {
        dsl.insertInto(PLAYERS)
            .set(PLAYERS.ID, PLAYER_ID)
            .set(PLAYERS.MINECRAFT_UUID, "00000000-0000-0000-0000-000000000001")
            .set(PLAYERS.USERNAME, "Steve")
            .set(PLAYERS.USERNAME_LOWER, "steve")
            .set(PLAYERS.CREATED_AT, START)
            .execute();
    }

    private static void transfer(DSLContext dsl, String id, String source, String target, String state) {
        dsl.insertInto(STORAGE_TRANSFERS)
            .set(STORAGE_TRANSFERS.ID, id)
            .set(STORAGE_TRANSFERS.SOURCE_BACKEND, source)
            .set(STORAGE_TRANSFERS.TARGET_BACKEND, target)
            .set(STORAGE_TRANSFERS.STATE, state)
            .set(STORAGE_TRANSFERS.STARTED_AT, START)
            .set(STORAGE_TRANSFERS.UPDATED_AT, START)
            .execute();
    }

    private static void ticket(DSLContext dsl, String status, String priority) {
        dsl.insertInto(TICKETS)
            .set(TICKETS.ID, "t-" + status + "-" + priority)
            .set(TICKETS.TYPE, "bug")
            .set(TICKETS.STATUS, status)
            .set(TICKETS.PRIORITY, priority)
            .set(TICKETS.PUBLIC_ACCESS_KEY, "key")
            .set(TICKETS.CREATED_AT, START)
            .set(TICKETS.UPDATED_AT, START)
            .execute();
    }
}
