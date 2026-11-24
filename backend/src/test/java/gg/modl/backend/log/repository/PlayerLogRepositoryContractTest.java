package gg.modl.backend.log.repository;

import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;
import static gg.modl.backend.database.jooq.Tables.COMMAND_LOGS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class PlayerLogRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String PLAYER = "player0000000001";

    @TempDir
    Path directory;

    @SqlContractTest
    void chatLinesRoundTripNewestFirstWithAStableTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            ChatLogRepository chat = new JooqChatLogRepository(database.dsl(), database.unitOfWork());
            ChatLogRow oldest = new ChatLogRow(9, PLAYER, null, "first", "", NOW.minusSeconds(60));
            ChatLogRow tie = new ChatLogRow(2, PLAYER, "Steve", "héllo 😀 'quote' %_!", "lobby", NOW);
            ChatLogRow winner = new ChatLogRow(3, PLAYER, "Steve", "line\nbreak", "survival", NOW);
            chat.insertAll(List.of(winner, oldest, tie, new ChatLogRow(4, "player0000000002", "Alex", "other", "lobby", NOW)));

            assertThat(chat.findRecentByPlayer(PLAYER, 10)).containsExactly(winner, tie, oldest);
            assertThat(chat.findRecentByPlayer(PLAYER, 2)).containsExactly(winner, tie);
            assertThat(chat.findRecentByPlayer("missing", 10)).isEmpty();
        }
    }

    @SqlContractTest
    void commandLinesRoundTripNewestFirstWithAStableTieBreak(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            CommandLogRepository commands = new JooqCommandLogRepository(database.dsl(), database.unitOfWork());
            CommandLogRow oldest = new CommandLogRow(9, PLAYER, null, "/spawn", "", NOW.minusSeconds(1));
            CommandLogRow tie = new CommandLogRow(2, PLAYER, "Steve", "/msg Alex hé", "lobby", NOW);
            CommandLogRow winner = new CommandLogRow(3, PLAYER, "Steve", "/help", "lobby", NOW);
            commands.insertAll(List.of(oldest, winner, tie));

            assertThat(commands.findRecentByPlayer(PLAYER, 10)).containsExactly(winner, tie, oldest);
            assertThat(commands.findRecentByPlayer(PLAYER, 1)).containsExactly(winner);
        }
    }

    @SqlContractTest
    void aPlayerIdTooLongToStoreFindsNothingInsteadOfFailing(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            ChatLogRepository chat = new JooqChatLogRepository(database.dsl(), database.unitOfWork());
            String widest = "p".repeat(TextLimits.limit(CHAT_LOGS.PLAYER_ID));
            ChatLogRow row = new ChatLogRow(1, widest, "Steve", "hi", "lobby", NOW);
            chat.insertAll(List.of(row));

            assertThat(chat.findRecentByPlayer(widest, 10)).containsExactly(row);
            assertThat(chat.findRecentByPlayer(widest + "x", 10)).isEmpty();
        }
    }

    @SqlContractTest
    void aRetriedBatchStoresEveryRowOnce(DialectTarget target) {
        RetryInjection retries = new RetryInjection();
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory, retries)) {
            List<ChatLogRow> rows = IntStream.range(0, 1_001)
                .mapToObj(index -> new ChatLogRow(index + 1L, PLAYER, index % 2 == 0 ? null : "Steve", "line " + index, "lobby", NOW))
                .toList();

            new JooqChatLogRepository(database.dsl(), database.unitOfWork()).insertAll(rows);

            assertThat(retries.injected()).isEqualTo(1);
            assertThat(database.dsl().fetchCount(CHAT_LOGS)).isEqualTo(1_001);
            assertThat(database.dsl().fetchCount(CHAT_LOGS, CHAT_LOGS.USERNAME.isNull())).isEqualTo(501);
        }
    }

    @SqlContractTest
    void anOverLongValueStoresNothingFromItsBatch(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, directory)) {
            CommandLogRepository commands = new JooqCommandLogRepository(database.dsl(), database.unitOfWork());

            assertThatThrownBy(() -> commands.insertAll(List.of(
                new CommandLogRow(1, PLAYER, "Steve", "/help", "lobby", NOW),
                new CommandLogRow(2, PLAYER, "Steve", "/help", "s".repeat(65), NOW)))).isInstanceOf(ValueTooLongException.class);

            assertThat(database.dsl().fetchCount(COMMAND_LOGS)).isZero();
        }
    }
}
