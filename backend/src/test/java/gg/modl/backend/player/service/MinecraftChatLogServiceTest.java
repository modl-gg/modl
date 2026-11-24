package gg.modl.backend.player.service;

import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.log.repository.JooqChatLogRepository;
import gg.modl.backend.log.repository.JooqCommandLogRepository;
import gg.modl.backend.player.repository.JooqPlayerRepository;
import gg.modl.backend.player.service.MinecraftChatLogService.ChatLogCommand;
import gg.modl.backend.player.service.MinecraftChatLogService.ChatLogEntryView;
import gg.modl.backend.player.service.MinecraftChatLogService.CommandLogCommand;
import gg.modl.backend.player.service.MinecraftChatLogService.CommandLogEntryView;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class MinecraftChatLogServiceTest {
    private static final String STEVE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String ALEX = "11111111-2222-3333-4444-555555555555";

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path directory;

    @SqlContractTest
    void unknownPlayersAreCreatedOnceAndEveryLineIsStoredDespiteARetry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, new RetryInjection())) {
            MinecraftChatLogService service = service(database);

            service.submitChatLogs(server, List.of(
                new ChatLogCommand(STEVE.toUpperCase(Locale.ROOT), "Steve", "first", 10, "lobby"),
                new ChatLogCommand(ALEX, "Alex", "hello", 20, "lobby"),
                new ChatLogCommand(STEVE, "Steve", "second", 10, "lobby")));
            service.submitCommandLogs(server, List.of(new CommandLogCommand(STEVE, "Steve", "/help", 30, "lobby")));

            assertThat(database.dsl().select(PLAYERS.MINECRAFT_UUID).from(PLAYERS).fetch(PLAYERS.MINECRAFT_UUID))
                .containsExactlyInAnyOrder(STEVE, ALEX);
            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isEqualTo(3);
            assertThat(database.dsl().fetchCount(COMMAND_LOGS)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void lookupByUuidIsCaseInsensitiveAndNewestFirst(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            MinecraftChatLogService service = service(database);
            service.submitChatLogs(server, List.of(
                new ChatLogCommand(STEVE, "Steve", "older", 10, "lobby"),
                new ChatLogCommand(STEVE, "Steve", "tie first", 42, null),
                new ChatLogCommand(STEVE, "Steve", "tie second", 42, "survival")));

            assertThat(service.getChatLogs(server, STEVE.toUpperCase(Locale.ROOT), 100)).containsExactly(
                new ChatLogEntryView(STEVE, "Steve", "tie second", 42, "survival"),
                new ChatLogEntryView(STEVE, "Steve", "tie first", 42, ""),
                new ChatLogEntryView(STEVE, "Steve", "older", 10, "lobby"));
            assertThat(service.getChatLogs(server, STEVE, 1)).hasSize(1);
            assertThat(service.getChatLogs(server, ALEX, 100)).isEmpty();
        }
    }

    @SqlContractTest
    void commandLinesMapToTheApiView(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            MinecraftChatLogService service = service(database);
            service.submitCommandLogs(server, List.of(new CommandLogCommand(ALEX, "Alex", "/msg hi", 42, "survival")));

            assertThat(service.getCommandLogs(server, ALEX.toUpperCase(Locale.ROOT), 200))
                .containsExactly(new CommandLogEntryView(ALEX, "Alex", "/msg hi", 42, "survival"));
        }
    }

    @SqlContractTest
    void passwordsFromOlderPluginsAreNeverStored(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            MinecraftChatLogService service = service(database);

            service.submitCommandLogs(server, List.of(
                new CommandLogCommand(STEVE, "Steve", "/login hunter2", 1, "lobby"),
                new CommandLogCommand(STEVE, "Steve", "/lo\u0000gin s3cret", 2, "lobby"),
                new CommandLogCommand(STEVE, "Steve", "/msg Alex hi", 3, "lobby")));

            assertThat(database.dsl().select(COMMAND_LOGS.COMMAND).from(COMMAND_LOGS).fetch(COMMAND_LOGS.COMMAND))
                .containsExactlyInAnyOrder("/login [REDACTED]", "/login [REDACTED]", "/msg Alex hi");
        }
    }

    @SqlContractTest
    void unvalidatedSyncLinesAreBoundedInsteadOfFailingTheBatch(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            MinecraftChatLogService service = service(database);
            String wideName = "😀".repeat(64);

            service.submitChatLogs(server, List.of(
                new ChatLogCommand("not-a-uuid", "Ghost", "dropped", 1, "lobby"),
                new ChatLogCommand(null, "Ghost", "dropped", 1, "lobby"),
                new ChatLogCommand(STEVE.replace("-", ""), wideName + "extra", null, 5, "s".repeat(90)),
                new ChatLogCommand(ALEX, " ", "unnamed", 6, "lobby")));

            assertThat(service.getChatLogs(server, STEVE, 10))
                .containsExactly(new ChatLogEntryView(STEVE, wideName, "", 5, "s".repeat(64)));
            assertThat(service.getChatLogs(server, ALEX, 10))
                .containsExactly(new ChatLogEntryView(ALEX, "Unknown", "unnamed", 6, "lobby"));
            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isEqualTo(2);
        }
    }

    @SqlContractTest
    void aLongBatchIsWrittenInBoundedUnits(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, retries)) {
            int lines = MinecraftChatLogService.ENTRIES_PER_UNIT * 2 + 1;

            service(database).submitChatLogs(server, IntStream.range(0, lines)
                .mapToObj(index -> new ChatLogCommand(STEVE, "Steve", "line " + index, index, "lobby"))
                .toList());

            assertThat(retries.injected()).isEqualTo(3);
            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isEqualTo(lines);
        }
    }

    @SqlContractTest
    void aSyncUnitStoresBothBatchesOrNeither(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            MinecraftChatLogService service = service(database);

            assertThatThrownBy(() -> database.unitOfWork().write(() -> {
                service.submitChatLogs(server, List.of(new ChatLogCommand(STEVE, "Steve", "hi", 1, "lobby")));
                service.submitCommandLogs(server, List.of(new CommandLogCommand(STEVE, "Steve", "/help", 2, "lobby")));
                throw new IllegalStateException("the sync failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isZero();
            assertThat(database.dsl().fetchCount(COMMAND_LOGS)).isZero();
        }
    }

    private static MinecraftChatLogService service(SqlTestDatabase database) {
        JooqPlayerRepository players = new JooqPlayerRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(),
            database.upserts(), database.childRows(), database.jsonColumns(), database.clock());
        return new MinecraftChatLogService(
            new JooqChatLogRepository(database.dsl(), database.unitOfWork()),
            new JooqCommandLogRepository(database.dsl(), database.unitOfWork()),
            players, database.unitOfWork(), database.sequencer(), database.clock());
    }
}
