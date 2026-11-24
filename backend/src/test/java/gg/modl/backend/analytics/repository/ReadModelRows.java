package gg.modl.backend.analytics.repository;

import gg.modl.backend.database.jooq.tables.records.AuditLogsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayerIpsRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentModificationsRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.TicketRepliesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.database.Sequencer;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.jooq.TableRecord;

public final class ReadModelRows {
    public static final Instant NOW = SqlTestDatabase.START;

    private ReadModelRows() {
    }

    public static String uuidOf(String playerId) {
        return UUID.nameUUIDFromBytes(playerId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static PlayersRecord player(String id, @Nullable Instant firstJoinAt) {
        PlayersRecord player = new PlayersRecord();
        player.setId(id);
        player.setMinecraftUuid(uuidOf(id));
        player.setUsername("Player_" + id);
        player.setUsernameLower("player_" + id);
        player.setCreatedAt(NOW);
        if (firstJoinAt != null) {
            player.setFirstJoinAt(firstJoinAt);
        }
        return player;
    }

    public static PlayerIpsRecord ip(String playerId, String ip, @Nullable String country, Instant firstSeen, Instant lastSeen,
                                     long logins, boolean proxy, boolean hosting) {
        PlayerIpsRecord row = new PlayerIpsRecord();
        row.setPlayerId(playerId);
        row.setIp(ip);
        row.setCountry(country);
        row.setFirstSeenAt(firstSeen);
        row.setLastSeenAt(lastSeen);
        row.setLoginCount(logins);
        row.setIsProxy(proxy);
        row.setIsHosting(hosting);
        return row;
    }

    public static PunishmentsRecord punishment(String id, String playerId, int ordinal, @Nullable String issuerName,
                                               @Nullable String issuerId, Instant issuedAt, Instant activeUntil) {
        PunishmentsRecord punishment = new PunishmentsRecord();
        punishment.setId(id);
        punishment.setPlayerId(playerId);
        punishment.setTypeOrdinal(ordinal);
        punishment.setIssuerName(issuerName);
        punishment.setIssuerNameLower(issuerName == null ? null : issuerName.toLowerCase(Locale.ROOT));
        punishment.setIssuerId(issuerId);
        punishment.setIssuedAt(issuedAt);
        punishment.setEnforcementCategory("BAN");
        punishment.setActiveUntilAt(activeUntil);
        return punishment;
    }

    public static PunishmentModificationsRecord modification(String punishmentId, int seq, String type, Instant at) {
        PunishmentModificationsRecord modification = new PunishmentModificationsRecord();
        modification.setPunishmentId(punishmentId);
        modification.setSeq(seq);
        modification.setId(punishmentId + "-" + seq);
        modification.setType(type);
        modification.setReason("reason");
        modification.setCreatedAt(at);
        return modification;
    }

    public static TicketsRecord ticket(String id, String type, String status, Instant createdAt, Instant updatedAt) {
        TicketsRecord ticket = new TicketsRecord();
        ticket.setId(id);
        ticket.setType(type);
        ticket.setStatus(status);
        ticket.setPublicAccessKey("key-" + id);
        ticket.setCreatedAt(createdAt);
        ticket.setUpdatedAt(updatedAt);
        return ticket;
    }

    public static TicketRepliesRecord reply(String ticketId, int seq, @Nullable String author, boolean staff, Instant at) {
        TicketRepliesRecord reply = new TicketRepliesRecord();
        reply.setTicketId(ticketId);
        reply.setSeq(seq);
        reply.setId(ticketId + "-" + seq);
        reply.setAuthorName(author);
        reply.setAuthorNameLower(author == null ? null : author.toLowerCase(Locale.ROOT));
        reply.setContent("content " + seq);
        reply.setIsStaff(staff);
        reply.setCreatedAt(at);
        return reply;
    }

    public static AuditLogsRecord log(int sequence, String level, String source, String description, Instant at) {
        AuditLogsRecord log = new AuditLogsRecord();
        log.setId(Sequencer.floorAt(at) + sequence);
        log.setLevel(level);
        log.setSource(source);
        log.setDescription(description);
        log.setCreatedAt(at);
        return log;
    }

    public static void insert(SqlTestDatabase database, TableRecord<?>... rows) {
        insertAll(database, List.of(rows));
    }

    public static void insertAll(SqlTestDatabase database, List<? extends TableRecord<?>> rows) {
        database.unitOfWork().write(() -> rows.forEach(row -> database.dsl().insertInto(row.getTable()).set(row).execute()));
    }
}
