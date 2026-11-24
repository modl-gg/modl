package gg.modl.backend.database.sql;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.audit.data.AuditLog;
import gg.modl.backend.database.InsertIfAbsent;
import gg.modl.backend.database.Upserts;
import gg.modl.backend.instance.repository.JooqInstanceRepository;
import gg.modl.backend.log.repository.JooqAuditLogRepository;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentEvidence;
import gg.modl.backend.player.data.punishment.PunishmentNote;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.repository.PlayerRepositories;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.backend.ticket.data.Ticket;
import gg.modl.backend.ticket.data.TicketCategory;
import gg.modl.backend.ticket.data.TicketReply;
import gg.modl.backend.ticket.data.TicketStatus;
import gg.modl.backend.ticket.repository.JooqTicketRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

enum CrashWorkload {
    CLAIM {
        @Override
        void run(Writes writes, String marker, Consumer<String> print) {
            writes.claim(marker);
        }
    },
    PUNISH {
        @Override
        void run(Writes writes, String marker, Consumer<String> print) {
            writes.punish(marker, SqlTestDatabase.START.plusSeconds(marker.equals(FIRST_BAN) ? 0 : 1));
        }
    },
    UNCOMMITTED {
        @Override
        void run(Writes writes, String marker, Consumer<String> print) {
            writes.openTicket(marker);
            writes.punishAndCloseTicket(marker, () -> {
                print.accept(UNCOMMITTED_WRITES);
                pauseUntilKilled();
            });
        }
    },
    CONTINUOUS {
        @Override
        void run(Writes writes, String marker, Consumer<String> print) {
            for (int index = 0; ; index++) {
                String id = marker + "-" + index;
                writes.openTicket(id);
                writes.punishAndCloseTicket(id, () -> {
                });
                print.accept(COMMITTED + id);
            }
        }
    };

    static final String UNCOMMITTED_WRITES = "crash workload holds uncommitted writes";
    static final String COMMITTED = "crash workload committed ";
    static final String FIRST_BAN = "ban-1";
    static final String OWNER_EMAIL = "owner@example.com";
    static final String VICTIM_UUID = "11111111-2222-3333-4444-555555555555";
    static final int NOTES = 40;
    static final int EVIDENCE = 10;
    static final int REPLIES = 5;

    abstract void run(Writes writes, String marker, Consumer<String> print);

    private static void pauseUntilKilled() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class Writes {
        private final SqlTestDatabase database;
        private final PlayerRepositories repositories;
        private final JooqTicketRepository tickets;
        private final JooqAuditLogRepository auditLogs;

        Writes(SqlTestDatabase database) {
            this.database = database;
            this.repositories = PlayerRepositories.on(database);
            this.tickets = new JooqTicketRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent(),
                database.childRows(), database.jsonColumns());
            this.auditLogs = new JooqAuditLogRepository(database.dsl(), database.unitOfWork(), database.sequencer(),
                database.jsonColumns());
        }

        void claim(String serverName) {
            InsertIfAbsent insertIfAbsent = database.insertIfAbsent();
            Upserts upserts = database.upserts();
            JooqInstanceRepository instance = new JooqInstanceRepository(database.dsl(), insertIfAbsent, upserts);
            database.unitOfWork().write(() -> {
                instance.ensure(serverName, SqlTestDatabase.START);
                instance.claim(OWNER_EMAIL, null, SqlTestDatabase.START);
            });
        }

        void punish(String id, Instant issuedAt) {
            database.unitOfWork().write(() -> {
                insertPunishment(id, issuedAt, List.of());
                recordIssued(id);
            });
        }

        void openTicket(String id) {
            Ticket ticket = Ticket.builder()
                .id(id)
                .type(TicketCategory.SUPPORT)
                .subject("crash test")
                .publicAccessKey("key-" + id)
                .status(TicketStatus.OPEN)
                .created(SqlTestDatabase.START)
                .updatedAt(SqlTestDatabase.START)
                .build();
            database.unitOfWork().write(() -> {
                tickets.insertIfAbsent(ticket);
            });
        }

        void punishAndCloseTicket(String id, Runnable beforeCommit) {
            database.unitOfWork().write(() -> {
                insertPunishment(id, SqlTestDatabase.START, List.of(id));
                for (int index = 0; index < REPLIES; index++) {
                    tickets.appendReply(id, TicketReply.builder()
                        .id("reply-" + index)
                        .name("Staff")
                        .content("punished")
                        .staff(true)
                        .created(SqlTestDatabase.START)
                        .build(), SqlTestDatabase.START);
                }
                tickets.closeIfUnlocked(List.of(id), SqlTestDatabase.START);
                recordIssued(id);
                beforeCommit.run();
            });
        }

        private void insertPunishment(String id, Instant issuedAt, List<String> ticketIds) {
            String playerId = repositories.players()
                .findOrCreateIdsByMinecraftUuid(Map.of(VICTIM_UUID, "Victim"), SqlTestDatabase.START)
                .get(VICTIM_UUID);
            List<PunishmentNote> notes = IntStream.range(0, NOTES)
                .mapToObj(index -> new PunishmentNote("note-" + index, "note " + index, issuedAt, "Staff", null))
                .toList();
            List<PunishmentEvidence> evidence = IntStream.range(0, EVIDENCE)
                .mapToObj(index -> new PunishmentEvidence("evidence " + index, null, "text", "Staff", null, issuedAt, null, null, null))
                .toList();
            Punishment punishment = new Punishment(id, 2, "Staff", null, PunishmentIssuerKind.STAFF, issuedAt, issuedAt, new ArrayList<>(),
                new ArrayList<>(notes), new ArrayList<>(evidence), new ArrayList<>(ticketIds),
                new HashMap<>(Map.of("reason", "crash test")));
            if (!repositories.punishments().insertIfAbsent(playerId, punishment, ResolvedCategory.BAN)) {
                throw new IllegalStateException("The punishment " + id + " already exists");
            }
        }

        private void recordIssued(String id) {
            auditLogs.insert(AuditLog.builder()
                .created(SqlTestDatabase.START)
                .level("moderation")
                .source("crash-test")
                .description(id)
                .build());
        }
    }
}
