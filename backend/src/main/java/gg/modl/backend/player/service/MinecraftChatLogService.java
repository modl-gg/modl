package gg.modl.backend.player.service;

import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.infrastructure.validation.RequestValidationLimits;
import gg.modl.backend.log.repository.ChatLogRepository;
import gg.modl.backend.log.repository.ChatLogRow;
import gg.modl.backend.log.repository.CommandLogRepository;
import gg.modl.backend.log.repository.CommandLogRow;
import gg.modl.backend.log.service.CredentialCommandRedactor;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MinecraftChatLogService {
    static final int ENTRIES_PER_UNIT = 500;

    private static final int MAX_FETCH_LIMIT = 500;
    private static final String UNKNOWN_USERNAME = "Unknown";

    private final ChatLogRepository chatLogRepository;
    private final CommandLogRepository commandLogRepository;
    private final PlayerRepository playerRepository;
    private final UnitOfWork unitOfWork;
    private final Sequencer sequencer;
    private final Clock clock;

    public void submitChatLogs(Server server, List<ChatLogCommand> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        submit(
            entries.stream().flatMap(entry ->
                LogLine.of(entry.uuid(), entry.username(), entry.message(), entry.timestamp(), entry.server())).toList(),
            (id, playerId, line) -> new ChatLogRow(id, playerId, line.username(), line.text(), line.server(), line.sentAt()),
            chatLogRepository::insertAll);
    }

    public void submitCommandLogs(Server server, List<CommandLogCommand> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        submit(
            entries.stream()
                .flatMap(entry ->
                    LogLine.of(entry.uuid(), entry.username(), entry.command(), entry.timestamp(), entry.server()))
                .map(LogLine::withoutCredentials)
                .toList(),
            (id, playerId, line) -> new CommandLogRow(id, playerId, line.username(), line.text(), line.server(), line.sentAt()),
            commandLogRepository::insertAll);
    }

    public List<ChatLogEntryView> getChatLogs(Server server, String uuid, int limit) {
        String normalized = UuidUtils.normalize(uuid);
        return recentOfPlayer(normalized, playerId -> chatLogRepository.findRecentByPlayer(playerId, cappedLimit(limit)))
            .stream()
            .map(row -> new ChatLogEntryView(normalized, row.username(), row.message(), row.sentAt().toEpochMilli(), row.serverName()))
            .toList();
    }

    public List<CommandLogEntryView> getCommandLogs(Server server, String uuid, int limit) {
        String normalized = UuidUtils.normalize(uuid);
        return recentOfPlayer(normalized, playerId -> commandLogRepository.findRecentByPlayer(playerId, cappedLimit(limit)))
            .stream()
            .map(row -> new CommandLogEntryView(normalized, row.username(), row.command(), row.sentAt().toEpochMilli(), row.serverName()))
            .toList();
    }

    private <R> List<R> recentOfPlayer(String uuid, Function<String, List<R>> recent) {
        return playerRepository.findByMinecraftUuid(uuid)
            .map(player -> recent.apply(player.getId()))
            .orElse(List.of());
    }

    private <R> void submit(List<LogLine> lines, RowFactory<R> rowFactory, Consumer<List<R>> insertAll) {
        Instant now = clock.instant();
        for (int start = 0; start < lines.size(); start += ENTRIES_PER_UNIT) {
            List<LogLine> chunk = lines.subList(start, Math.min(start + ENTRIES_PER_UNIT, lines.size()));
            unitOfWork.write(() -> store(chunk, now, rowFactory, insertAll));
        }
    }

    private <R> void store(List<LogLine> lines, Instant now, RowFactory<R> rowFactory, Consumer<List<R>> insertAll) {
        Map<String, String> usernameByUuid = new TreeMap<>();
        lines.forEach(line -> usernameByUuid.put(line.uuid(), line.username()));
        Map<String, String> playerIds = playerRepository.findOrCreateIdsByMinecraftUuid(usernameByUuid, now);
        insertAll.accept(lines.stream()
            .map(line -> rowFactory.row(sequencer.next(), resolvedPlayerId(playerIds, line.uuid()), line))
            .toList());
    }

    private static String resolvedPlayerId(Map<String, String> playerIds, String uuid) {
        String playerId = playerIds.get(uuid);
        if (playerId == null) {
            throw new IllegalStateException("findOrCreateIdsByMinecraftUuid returned no id for " + uuid);
        }
        return playerId;
    }

    private static int cappedLimit(int limit) {
        return Math.min(limit, MAX_FETCH_LIMIT);
    }

    @FunctionalInterface
    private interface RowFactory<R> {
        R row(long id, String playerId, LogLine line);
    }

    private record LogLine(String uuid, String username, String text, Instant sentAt, String server) {
        static Stream<LogLine> of(@Nullable String uuid, @Nullable String username, @Nullable String text, long timestamp,
                                  @Nullable String server) {
            String canonicalUuid = UuidUtils.dashed(uuid);
            if (canonicalUuid == null) {
                log.debug("Skipped a log line without a valid player UUID");
                return Stream.empty();
            }
            String name = username == null || username.isBlank() ? UNKNOWN_USERNAME : username;
            return Stream.of(new LogLine(
                canonicalUuid,
                storable(name, TextLimits.limit(CHAT_LOGS.USERNAME)),
                storable(Objects.requireNonNullElse(text, ""), RequestValidationLimits.CHAT_LOG_MESSAGE_MAX_LENGTH),
                Instant.ofEpochMilli(Math.max(timestamp, 0L)),
                storable(Objects.requireNonNullElse(server, ""), TextLimits.limit(CHAT_LOGS.SERVER_NAME))));
        }

        LogLine withoutCredentials() {
            return new LogLine(uuid, username, CredentialCommandRedactor.redact(text), sentAt, server);
        }

        private static String storable(String value, int maxCodePoints) {
            return CappedText.of(StorableText.strip(value), maxCodePoints);
        }
    }

    public record ChatLogCommand(String uuid, String username, String message, long timestamp, @Nullable String server) {
    }

    public record CommandLogCommand(String uuid, String username, String command, long timestamp, @Nullable String server) {
    }

    public record ChatLogEntryView(String uuid, @Nullable String username, String message, long timestamp, String server) {
    }

    public record CommandLogEntryView(String uuid, @Nullable String username, String command, long timestamp, String server) {
    }
}
