package gg.modl.backend.hostedimport.job;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;
import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_LINKS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_TICKETS;
import static gg.modl.backend.database.jooq.Tables.REPLAYS;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.SETUP_STEPS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLE_PERMISSIONS;
import static gg.modl.backend.database.jooq.Tables.STAFF_TICKET_SUBSCRIPTIONS;
import static gg.modl.backend.database.jooq.Tables.STORAGE_FILES;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.BackendApplication;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.database.WriteAttemptHook;
import gg.modl.backend.database.sql.SqlDatabaseProperties;
import gg.modl.backend.hostedimport.EndToEndArchive;
import gg.modl.backend.hostedimport.intake.ImportArchiveFiles;
import gg.modl.backend.hostedimport.model.ImportJob;
import gg.modl.backend.hostedimport.model.ImportPhase;
import gg.modl.backend.hostedimport.model.ImportStatus;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.ImportTally;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.hostedimport.model.RetentionChoice;
import gg.modl.backend.hostedimport.model.SubjectCount;
import gg.modl.backend.hostedimport.service.HostedImportService;
import gg.modl.backend.hostedimport.service.ImportRequests;
import gg.modl.backend.instance.InstanceContext;
import gg.modl.backend.instance.InstanceService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.storage.ObjectStorage;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.TestDialects;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class HostedImportEndToEndTest {
    private static final String OWNER = "owner@example.com";
    private static final String PUBLIC_URL = "https://panel.example.com";
    private static final Duration PATIENCE = Duration.ofMinutes(2);
    private static final Duration POLL = Duration.ofMillis(25);
    private static final int CHUNK_BYTES = 16 * 1024;
    private static final int MOST_BOUNDARIES = 300;

    @TempDir
    Path directory;

    @SqlContractTest
    void anImportKilledAtEveryUnitBoundaryEndsLikeAnUninterruptedOne(DialectTarget target) throws Exception {
        Path archive = EndToEndArchive.write(directory.resolve("archive"));

        Outcome uninterrupted = uninterruptedImport(target, archive);
        Outcome killed = importKilledAtEveryBoundary(target, archive);

        assertComplete(uninterrupted);
        assertThat(killed.kills()).isGreaterThan(ImportPhase.values().length);
        assertThat(killed.counts()).isEqualTo(uninterrupted.counts());
        assertThat(killed.issueCounts()).isEqualTo(uninterrupted.issueCounts());
        killed.tables().forEach((table, rows) -> assertThat(rows).as(table).isEqualTo(uninterrupted.tables().get(table)));
    }

    private Outcome uninterruptedImport(DialectTarget target, Path archive) throws Exception {
        try (Installation installation = new Installation(target, directory.resolve("uninterrupted"))) {
            installation.startImportOf(archive);
            installation.awaitKillOrCompletion();
            return installation.outcome();
        }
    }

    private Outcome importKilledAtEveryBoundary(DialectTarget target, Path archive) throws Exception {
        try (Installation installation = new Installation(target, directory.resolve("killed"))) {
            installation.uploadAndVerify(archive);
            installation.killer().arm();
            installation.confirm();
            boolean restartedWhilePlayersWereImported = false;
            while (installation.awaitKillOrCompletion()) {
                assertThat(installation.killer().kills()).isLessThan(MOST_BOUNDARIES);
                installation.killer().newLife();
                if (!restartedWhilePlayersWereImported && installation.job().phase() == ImportPhase.PLAYERS) {
                    installation.restartHost();
                    restartedWhilePlayersWereImported = true;
                } else {
                    installation.resumeInPlace();
                }
            }
            assertThat(restartedWhilePlayersWereImported).isTrue();
            return installation.outcome();
        }
    }

    private static void assertComplete(Outcome outcome) {
        int skippedPlayers = EndToEndArchive.PLAYERS / EndToEndArchive.SKIP_EVERY;
        int skippedChat = EndToEndArchive.CHAT_LINES / (2 * EndToEndArchive.SKIP_EVERY);
        assertThat(outcome.imported(ImportSubject.PLAYERS)).isEqualTo(EndToEndArchive.PLAYERS);
        assertThat(outcome.skipped(ImportSubject.PLAYERS)).isEqualTo(skippedPlayers);
        assertThat(outcome.imported(ImportSubject.CHAT_LOGS)).isEqualTo(EndToEndArchive.CHAT_LINES);
        assertThat(outcome.skipped(ImportSubject.CHAT_LOGS)).isEqualTo(skippedChat);
        assertThat(outcome.imported(ImportSubject.FILES)).isEqualTo(EndToEndArchive.OBJECTS);
        for (ImportSubject single : List.of(ImportSubject.PUNISHMENTS, ImportSubject.NOTIFICATIONS, ImportSubject.LINKS,
            ImportSubject.TICKETS, ImportSubject.REPLAYS, ImportSubject.COMMAND_LOGS, ImportSubject.AUDIT_LOGS, ImportSubject.STAFF,
            ImportSubject.ROLES, ImportSubject.CATEGORIES, ImportSubject.CARDS, ImportSubject.SETTINGS)) {
            assertThat(outcome.imported(single)).as(single.id()).isEqualTo(1);
        }
        assertThat(outcome.imported(ImportSubject.ARTICLES)).isEqualTo(2);
        assertThat(outcome.issueCounts()).containsEntry(IssueCode.STAFF_NOT_RESTORED, 1L)
            .containsEntry(IssueCode.COMMAND_REDACTED, 1L)
            .containsEntry(IssueCode.ARTICLE_ORPHANED, 1L);
        assertThat(outcome.tables().get(PLAYERS.getName())).hasSize(EndToEndArchive.PLAYERS);
        assertThat(outcome.tables().get(CHAT_LOGS.getName())).hasSize(EndToEndArchive.CHAT_LINES);
        assertThat(outcome.tables().get(STORAGE_FILES.getName())).hasSize(EndToEndArchive.OBJECTS);
        assertThat(outcome.tables().get(STAFF_ROLES.getName())).anyMatch(row -> row.contains(EndToEndArchive.ROLE));
        assertThat(outcome.tables().get(STAFF.getName())).singleElement().asString().contains(EndToEndArchive.SELECTED_STAFF);
        assertThat(outcome.tables().get(COMMAND_LOGS.getName())).singleElement().asString().doesNotContain("hunter2");
        assertThat(outcome.serverName()).isEqualTo(EndToEndArchive.SERVER_NAME);
    }

    private record Outcome(Map<ImportSubject, SubjectCount> counts, Map<IssueCode, Long> issueCounts,
                           Map<String, List<String>> tables, String serverName, int kills) {
        private long imported(ImportSubject subject) {
            return counts.getOrDefault(subject, SubjectCount.EMPTY).imported();
        }

        private long skipped(ImportSubject subject) {
            return counts.getOrDefault(subject, SubjectCount.EMPTY).skipped();
        }
    }

    private static final class SimulatedKill extends Error {
        private SimulatedKill() {
            super("The process was killed at a unit boundary", null, false, false);
        }
    }

    private static final class BoundaryKiller implements WriteAttemptHook {
        private final AtomicInteger commitsInThisLife = new AtomicInteger();
        private final AtomicInteger kills = new AtomicInteger();
        private final Semaphore killed = new Semaphore(0);
        private volatile boolean armed;

        @Override
        public void beforeCommit(int attempt) {
            if (!armed || !ImportWorker.THREAD_NAME.equals(Thread.currentThread().getName())) {
                return;
            }
            if (commitsInThisLife.incrementAndGet() > 1) {
                kills.incrementAndGet();
                killed.release();
                throw new SimulatedKill();
            }
        }

        private void arm() {
            armed = true;
        }

        private void newLife() {
            commitsInThisLife.set(0);
        }

        private int kills() {
            return kills.get();
        }

        private boolean consumeKill() {
            return killed.tryAcquire();
        }
    }

    private static final class Installation implements AutoCloseable {
        private final Path dataDirectory;
        private final SqlDatabaseProperties database;
        private final BoundaryKiller killer = new BoundaryKiller();
        private ConfigurableApplicationContext context;
        private String importId;

        private Installation(DialectTarget target, Path dataDirectory) throws IOException {
            this.dataDirectory = Files.createDirectories(dataDirectory);
            this.database = TestDialects.freshDatabase(target);
            this.context = boot();
            InstanceService instance = bean(InstanceService.class);
            assertThat(instance.claim(OWNER, PUBLIC_URL)).isTrue();
            instance.recordOwnerSignIn();
        }

        private ConfigurableApplicationContext boot() {
            SpringApplication application = BackendApplication.application(properties());
            application.addInitializers(starting ->
                starting.getBeanFactory().registerSingleton(WriteAttemptHook.class.getName(), killer));
            return application.run();
        }

        private Map<String, Object> properties() {
            Map<String, Object> properties = new HashMap<>();
            properties.put("modl.data-dir", dataDirectory.toString());
            properties.put("modl.database.url", database.externalUrl().orElse(""));
            properties.put("modl.database.username", Objects.requireNonNullElse(database.username(), ""));
            properties.put("modl.database.password", Objects.requireNonNullElse(database.password(), ""));
            properties.put("modl.instance.admin-email", "");
            properties.put("modl.instance.public-url", "");
            properties.put("modl.setup.code-file", "");
            properties.put("modl.mail.host", "");
            properties.put("modl.ai.provider", "");
            properties.put("modl.storage.type", "local");
            properties.put("server.address", "127.0.0.1");
            properties.put("server.port", "0");
            return properties;
        }

        private void startImportOf(Path archive) throws Exception {
            uploadAndVerify(archive);
            confirm();
        }

        private void uploadAndVerify(Path archive) throws Exception {
            byte[] bytes = Files.readAllBytes(archive);
            HostedImportService service = bean(HostedImportService.class);
            importId = service.beginUpload(server(), new ImportRequests.BeginUpload(bytes.length, "export.zip", null)).id();
            for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
                int length = Math.min(CHUNK_BYTES, bytes.length - offset);
                service.receiveChunk(importId, offset, length, new ByteArrayInputStream(bytes, offset, length));
            }
            service.completeUpload(importId);
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (job().status() == ImportStatus.VERIFYING && System.nanoTime() < deadline) {
                Thread.sleep(POLL);
            }
            assertThat(job().status()).as("reason %s", job().reason()).isEqualTo(ImportStatus.REVIEW);
        }

        private void confirm() {
            bean(HostedImportService.class).start(server(), new ImportRequests.Start(importId,
                Set.of(EndToEndArchive.SELECTED_STAFF), false, RetentionChoice.KEEP_HISTORY));
        }

        private boolean awaitKillOrCompletion() throws InterruptedException {
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < deadline) {
                if (killer.consumeKill()) {
                    return true;
                }
                ImportJob job = job();
                if (job.status() == ImportStatus.COMPLETED) {
                    return awaitCompletionSideEffects();
                }
                assertThat(job.status()).as("reason %s in %s", job.reason(), job.phase()).isEqualTo(ImportStatus.APPLYING);
                Thread.sleep(POLL);
            }
            throw new AssertionError("The import neither completed nor reached a unit boundary in " + job().phase());
        }

        private boolean awaitCompletionSideEffects() throws InterruptedException {
            ImportArchiveFiles files = bean(ImportArchiveFiles.class);
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (System.nanoTime() < deadline) {
                if (killer.consumeKill()) {
                    return true;
                }
                if (!files.exists(importId)) {
                    return false;
                }
                Thread.sleep(POLL);
            }
            throw new AssertionError("The completed import neither removed its archive nor was killed after its commit");
        }

        private void resumeInPlace() {
            bean(ArchiveSession.class).forget();
            bean(ImportRecovery.class).recover();
        }

        private void restartHost() {
            context.close();
            context = boot();
        }

        private BoundaryKiller killer() {
            return killer;
        }

        private ImportJob job() {
            return bean(ImportStates.class).current().orElseThrow();
        }

        private Server server() {
            return bean(InstanceContext.class).instance();
        }

        private Outcome outcome() throws IOException, InterruptedException {
            awaitArchiveRemoved();
            ImportTally tally = job().tally();
            Map<IssueCode, Long> issueCounts = new TreeMap<>();
            tally.issues().forEach((code, entry) -> issueCounts.put(code, entry.count()));
            try (InputStream stored = bean(ObjectStorage.class).read("modl_instance/" + EndToEndArchive.EVIDENCE)) {
                assertThat(new String(stored.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(EndToEndArchive.EVIDENCE_CONTENT);
            }
            return new Outcome(tally.counts(), issueCounts, bean(UnitOfWork.class).read(this::importedTables),
                server().getServerName(), killer.kills());
        }

        private void awaitArchiveRemoved() throws InterruptedException {
            ImportArchiveFiles files = bean(ImportArchiveFiles.class);
            long deadline = System.nanoTime() + PATIENCE.toNanos();
            while (files.exists(importId) && System.nanoTime() < deadline) {
                Thread.sleep(POLL);
            }
            assertThat(files.exists(importId)).isFalse();
        }

        private Map<String, List<String>> importedTables() {
            Map<String, List<String>> tables = new TreeMap<>();
            dump(tables, PLAYERS, DSL.noCondition(), PLAYERS.LAST_LINKED_UPDATE_AT);
            dump(tables, PLAYER_USERNAMES, DSL.noCondition());
            dump(tables, PLAYER_IPS, DSL.noCondition());
            dump(tables, PLAYER_LINKS, DSL.noCondition(), PLAYER_LINKS.LINKED_AT);
            dump(tables, PLAYER_NOTIFICATIONS, DSL.noCondition());
            dump(tables, PUNISHMENTS, DSL.noCondition(), PUNISHMENTS.MODIFIED_AT);
            dump(tables, PUNISHMENT_NOTES, DSL.noCondition());
            dump(tables, PUNISHMENT_EVIDENCE, DSL.noCondition());
            dump(tables, PUNISHMENT_TICKETS, DSL.noCondition());
            dump(tables, TICKETS, DSL.noCondition(), TICKETS.PUBLIC_ACCESS_KEY, TICKETS.VERSION);
            dump(tables, TICKET_REPLIES, DSL.noCondition());
            dump(tables, REPLAYS, DSL.noCondition());
            dump(tables, CHAT_LOGS, DSL.noCondition());
            dump(tables, COMMAND_LOGS, DSL.noCondition());
            dump(tables, AUDIT_LOGS, AUDIT_LOGS.SOURCE.eq(EndToEndArchive.AUDIT_SOURCE));
            dump(tables, STAFF, STAFF.EMAIL.ne(OWNER), STAFF.CREATED_AT, STAFF.UPDATED_AT, STAFF.LAST_SEEN_AT);
            dump(tables, STAFF_TICKET_SUBSCRIPTIONS, DSL.noCondition(), STAFF_TICKET_SUBSCRIPTIONS.LAST_READ_AT);
            dump(tables, STAFF_ROLES, DSL.noCondition(), STAFF_ROLES.CREATED_AT, STAFF_ROLES.UPDATED_AT);
            dump(tables, STAFF_ROLE_PERMISSIONS, DSL.noCondition());
            dump(tables, KNOWLEDGEBASE_CATEGORIES, DSL.noCondition(), KNOWLEDGEBASE_CATEGORIES.CREATED_AT,
                KNOWLEDGEBASE_CATEGORIES.UPDATED_AT);
            dump(tables, KNOWLEDGEBASE_ARTICLES, DSL.noCondition(), KNOWLEDGEBASE_ARTICLES.UPDATED_AT);
            dump(tables, HOMEPAGE_CARDS, DSL.noCondition(), HOMEPAGE_CARDS.UPDATED_AT);
            dump(tables, STORAGE_FILES, DSL.noCondition(), STORAGE_FILES.CREATED_AT);
            dump(tables, SETTINGS, SETTINGS.TYPE.eq("general"), SETTINGS.UPDATED_AT);
            dump(tables, SETUP_STEPS, DSL.noCondition(), SETUP_STEPS.UPDATED_AT);
            return tables;
        }

        private void dump(Map<String, List<String>> tables, Table<?> table, Condition rows, Field<?>... varyingByRun) {
            Set<String> varying = Set.copyOf(Arrays.stream(varyingByRun).map(Field::getName).toList());
            List<Field<?>> compared = Arrays.stream(table.fields())
                .filter(field -> !varying.contains(field.getName()))
                .toList();
            tables.put(table.getName(), bean(DSLContext.class).select(compared).from(table).where(rows).fetch().stream()
                .map(row -> Arrays.toString(row.intoArray()))
                .sorted()
                .toList());
        }

        private <T> T bean(Class<T> type) {
            return context.getBean(type);
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
