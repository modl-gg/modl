package gg.modl.backend.database;

import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.SettingsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.TableRecord;

final class KernelRows {
    static final Instant NOW = SqlTestDatabase.START;

    private KernelRows() {
    }

    static SettingsRecord settings(String type) {
        return new SettingsRecord(type, "{}", 0L, NOW);
    }

    static StaffRecord staff(String id, String email, String username) {
        StaffRecord staff = new StaffRecord();
        staff.setId(id);
        staff.setEmail(email);
        staff.setUsername(username);
        staff.setCreatedAt(NOW);
        staff.setUpdatedAt(NOW);
        return staff;
    }

    static PlayersRecord player(String id) {
        PlayersRecord player = new PlayersRecord();
        player.setId(id);
        player.setMinecraftUuid(UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString());
        player.setUsername("Player_" + id);
        player.setUsernameLower("player_" + id);
        player.setCreatedAt(NOW);
        return player;
    }

    static TicketsRecord ticket(String id) {
        TicketsRecord ticket = new TicketsRecord();
        ticket.setId(id);
        ticket.setType("support");
        ticket.setStatus("open");
        ticket.setPublicAccessKey("key-" + id);
        ticket.setCreatedAt(NOW);
        ticket.setUpdatedAt(NOW);
        return ticket;
    }

    static PunishmentsRecord punishment(String id, String playerId) {
        PunishmentsRecord punishment = new PunishmentsRecord();
        punishment.setId(id);
        punishment.setPlayerId(playerId);
        punishment.setTypeOrdinal(0);
        punishment.setIssuedAt(NOW);
        punishment.setEnforcementCategory("BAN");
        punishment.setActiveUntilAt(NOW.plusSeconds(3600));
        return punishment;
    }

    static void insert(SqlTestDatabase database, TableRecord<?>... rows) {
        insertAll(database, List.of(rows));
    }

    static void insertAll(SqlTestDatabase database, List<? extends TableRecord<?>> rows) {
        database.unitOfWork().write(() -> rows.forEach(row -> database.dsl().insertInto(row.getTable()).set(row).execute()));
    }
}
