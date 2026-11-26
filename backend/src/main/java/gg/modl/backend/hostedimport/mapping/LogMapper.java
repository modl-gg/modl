package gg.modl.backend.hostedimport.mapping;

import static gg.modl.backend.database.jooq.Tables.AUDIT_LOGS;
import static gg.modl.backend.database.jooq.Tables.CHAT_LOGS;

import gg.modl.backend.hostedimport.archive.ArchiveDocument;
import gg.modl.backend.hostedimport.model.ImportSubject;
import gg.modl.backend.hostedimport.model.IssueCode;
import gg.modl.backend.log.service.AuditActor;
import gg.modl.backend.log.service.CredentialCommandRedactor;
import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LogMapper {
    private static final int MILLIS_SHIFT = 20;
    private static final long LINE_MASK = 0xFFFFF;
    private static final String UNKNOWN_USERNAME = "Unknown";
    private static final String PUNISHMENT_ID = "punishmentId";
    private static final String PLAYER_ID = "playerId";

    private final ImportContext context;
    private final ImportFit fit;
    private final Instant chatCutoff;
    private final Instant auditCutoff;

    public LogMapper(ImportContext context, int chatLogDays, int auditLogDays) {
        this.context = context;
        this.fit = context.fit();
        this.chatCutoff = context.acceptedAt().minus(Duration.ofDays(chatLogDays));
        this.auditCutoff = context.acceptedAt().minus(Duration.ofDays(auditLogDays));
    }

    public Optional<PendingPlayerLog> chat(ArchiveDocument source, long line) {
        return playerLog(source, line, source.string("message"), ImportSubject.CHAT_LOGS);
    }

    public Optional<PendingPlayerLog> command(ArchiveDocument source, long line) {
        String command = source.string("command");
        String redacted = command == null ? null : CredentialCommandRedactor.redact(command);
        if (redacted != null && !redacted.equals(command)) {
            context.issues().report(IssueCode.COMMAND_REDACTED, 1);
        }
        return playerLog(source, line, redacted, ImportSubject.COMMAND_LOGS);
    }

    private Optional<PendingPlayerLog> playerLog(ArchiveDocument source, long line, @Nullable String text, ImportSubject subject) {
        UUID uuid = source.uuid("uuid");
        if (uuid == null || text == null) {
            context.issues().report(IssueCode.RECORD_REJECTED, subject.id() + ":" + line);
            context.issues().skipped(subject);
            return Optional.empty();
        }
        Instant sentAt = LogTimes.sentAt(source.raw("timestamp"), context.exportTime(), context.acceptedAt());
        if (sentAt.isBefore(chatCutoff)) {
            context.issues().report(IssueCode.LOG_NOT_KEPT, 1);
            context.issues().skipped(subject);
            return Optional.empty();
        }
        String username = fit.columnIfPresent(CHAT_LOGS.USERNAME, source.text("username"));
        context.issues().imported(subject);
        return Optional.of(new PendingPlayerLog(
            preferredId(sentAt, line),
            uuid.toString(),
            username == null || username.isBlank() ? UNKNOWN_USERNAME : username,
            fit.text(text),
            Objects.requireNonNullElse(fit.columnIfPresent(CHAT_LOGS.SERVER_NAME, source.text("server")), ""),
            sentAt));
    }

    public Optional<PendingAudit> audit(ArchiveDocument source, long line) {
        Instant created = LogTimes.clamp(source.instant("created"), context.exportTime(), context.acceptedAt());
        if (created.isBefore(auditCutoff)) {
            context.issues().report(IssueCode.LOG_NOT_KEPT, 1);
            context.issues().skipped(ImportSubject.AUDIT_LOGS);
            return Optional.empty();
        }
        Map<String, Object> metadata = source.document("metadata")
            .map(document -> context.urls().rewriteMap(document.fields()))
            .orElseGet(LinkedHashMap::new);
        String sourcePlayerId = metadata.get(PLAYER_ID) instanceof String playerId ? playerId : null;
        String playerUuid = context.index().uuidOfSourcePlayer(sourcePlayerId);
        if (sourcePlayerId != null && playerUuid == null) {
            context.issues().report(IssueCode.REFERENCE_DANGLING, sourcePlayerId);
        }
        if (metadata.get(PUNISHMENT_ID) instanceof String punishmentId) {
            metadata.put(PUNISHMENT_ID, context.punishmentIds().reference(punishmentId, playerUuid));
        }
        context.issues().imported(ImportSubject.AUDIT_LOGS);
        return Optional.of(new PendingAudit(
            preferredId(created, line),
            Objects.requireNonNullElse(fit.columnIfPresent(AUDIT_LOGS.LEVEL, source.text("level")), "info"),
            AuditActor.displayNameFor(fit.columnIfPresent(AUDIT_LOGS.SOURCE, source.text("source"))),
            Objects.requireNonNullElse(fit.textIfPresent(source.string("description")), ""),
            metadata,
            playerUuid,
            created));
    }

    static long preferredId(Instant at, long line) {
        return (at.toEpochMilli() << MILLIS_SHIFT) | (line & LINE_MASK);
    }

    public record PendingPlayerLog(long preferredId, String uuid, String username, String text, String serverName, Instant sentAt) {
    }

    public record PendingAudit(long preferredId, String level, String source, String description, Map<String, Object> metadata,
                               @Nullable String playerUuid, Instant createdAt) {
    }
}
